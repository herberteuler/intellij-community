//! `provision-guest`: making a freshly cloned public Ubuntu clone into a UI worker, from inside the guest.
//!
//! The first of the two verbs the controller runs as root on every boot; `validate-guest` immediately checks its
//! work. Six steps, and the order is load bearing three times: the packages come first because the X server, the
//! window manager and `xprop` are all in them, the fluxbox overlay comes before the units because fluxbox copies it
//! on its first start, and the wait for the display comes last because it is the only step that can tell a display
//! that came up from one that failed.
//!
//! # What this verb may assume about the guest
//!
//! A digest-pinned Ubuntu 24.04 arm64 clone with `dpkg`, `apt-get`, `systemd` and `netplan`. Where the work is a
//! tool's own - `apt`'s dependency resolution, `dpkg`'s installed-state database, `install`'s name-to-uid
//! ownership handling, `netplan`'s renderer - it shells out. Where the work is writing this verb's own content into
//! a file, it is done natively.
//!
//! # Idempotence, step by step
//!
//! A warm worker runs all six again. The package install is guarded because `apt-get` on an already satisfied
//! install set is seconds nobody needs to spend; the netplan drop-in is guarded because *applying* it again would
//! bounce the network the controller is talking to this guest over; the overlay, the units, the directories and
//! the wait are unguarded because re-writing an inert file, re-running `install -d` and re-probing a display cost
//! milliseconds and remove a class of drift.

use avl_wire::verb::AgentVerb;
use serde::Serialize;
use std::fs;
use std::io;
use std::os::unix::fs::PermissionsExt;
use std::path::{Path, PathBuf};

use super::{
    APT_GET_TOOL, DPKG_QUERY_TOOL, FLUXBOX_BINARY, FLUXBOX_OVERLAY, FLUXBOX_OVERLAY_CONTENT, FLUXBOX_UNIT, INSTALL_TOOL, NETPLAN_DROP_IN,
    NETPLAN_TOOL, SYSTEMCTL_TOOL, SYSTEMD_UNIT_DIRECTORY, WAIT_ATTEMPTS, WAIT_INTERVAL, XDPYINFO_TOOL, XVFB_BINARY, XVFB_UNIT, on_display,
    wait_budget,
};
use crate::cli::ProvisionGuestArgs;
use crate::reply::AgentRefusalExt;
use crate::reply::{AgentRefusal, quoted};
use crate::shape;
use crate::step::{Runner, Step};

/// The verb this module answers, for its messages.
const VERB: AgentVerb = AgentVerb::ProvisionGuest;

#[cfg(test)]
mod tests;

/// The guest environment variable that overrides the X screen geometry.
///
/// The one value this verb reads that the controller did not tell it. Nothing on the host sets it - there is no
/// `Config` field and no argv slot - so it is a knob for an operator debugging a worker by hand, and a
/// controller-side default would be the second place for a default to be wrong.
pub(crate) const SCREEN_VARIABLE: &str = "AIR_VM_SCREEN";

/// 1920x1080 at 24-bit, which is what the lane's screenshots are taken against; a smaller screen changes which
/// components a driver can reach without scrolling, so this is a lane-visible number rather than a preference.
pub(crate) const DEFAULT_SCREEN_GEOMETRY: &str = "1920x1080x24";

/// Netplan refuses to be quiet about a world-readable configuration file; systemd reads the units as root and
/// nothing else has to.
const NETPLAN_DROP_IN_MODE: u32 = 0o600;
const UNIT_FILE_MODE: u32 = 0o644;
/// Fluxbox reads the template as the worker account, so it is world-readable like a unit.
const FLUXBOX_OVERLAY_MODE: u32 = 0o644;

/// The one `dpkg-query` answer that means a package is present and usable, compared whole: `deinstall ok
/// config-files` and `install ok half-configured` are packages `apt-get install` still has work to do on.
const DPKG_INSTALLED_STATUS: &str = "install ok installed";

/// The drop-in, byte for byte the script's heredoc. `en*` and not one interface name: a Tart guest's interface is
/// `enp0s1` today and the match keeps this from being a fact about one hypervisor's device naming.
const NETPLAN_DROP_IN_CONTENT: &str = "network:
  version: 2
  ethernets:
    all-en:
      match:
        name: \"en*\"
      dhcp4: true
      dhcp-identifier: mac
";

/// The X screen geometry, from [`SCREEN_VARIABLE`] (`None` or empty when unset). A malformed one is a usage
/// refusal: nothing in the guest has been touched yet.
///
/// The shape is the one `Xvfb -screen 0` accepts: width, height, depth. It is validated because the geometry is
/// interpolated into a unit file, and a malformed one produces an `air-xvfb.service` that starts, exits and is
/// restarted forever by `Restart=always` - indistinguishable, for 60 seconds, from an X server that is merely slow.
pub(crate) fn screen_geometry(value: Option<&str>) -> Result<String, AgentRefusal> {
    let screen = value.filter(|value| !value.is_empty()).unwrap_or(DEFAULT_SCREEN_GEOMETRY);
    if !shape::is_digit_groups(screen, 'x', 3) {
        return Err(AgentRefusal::usage(format!(
            "{VERB} expects a screen geometry like {DEFAULT_SCREEN_GEOMETRY}, and \
             {SCREEN_VARIABLE} is {}",
            quoted(screen)
        )));
    }
    Ok(screen.to_owned())
}

/// Refuses an install set the verb cannot act on. There is no default: the script's was the display half of
/// `linux.GuestPackages` as it then was and none of the native libraries added to it later, so a controller that
/// passed none would have silently provisioned a worker without `libegl1` or the JCEF set.
pub(crate) fn check_packages(packages: &[String]) -> Result<(), AgentRefusal> {
    if packages.is_empty() {
        return Err(AgentRefusal::usage(format!(
            "{VERB} was given no packages to install; linux.GuestPackages is the list and it is \
             not optional - a worker without xvfb, fluxbox or x11-utils cannot open a window, and one \
             without the Skiko and JCEF libraries starts an IDE that logs one SEVERE line and measures nothing"
        )));
    }
    // A name that is not one would reach `apt-get` as an argument, and `apt-get install -y` reads a leading dash as
    // a flag of its own. Refused, so the message names the argument instead of quoting apt's opinion of it.
    if let Some(name) = packages.iter().find(|name| name.is_empty() || name.starts_with('-')) {
        return Err(AgentRefusal::usage(format!("{VERB} was given {} as a package name", quoted(name))));
    }
    Ok(())
}

/// What a passing provisioning answers: what it did, not just that it finished. The controller notes it whole on
/// every boot. A warm worker answers an empty `installed`, which is the one observable difference between "this
/// worker was already provisioned" and "this worker was provisioned again".
#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct ProvisionReport {
    pub display: String,
    pub screen: String,
    /// What this boot installed; with `already_present`, the install set the controller passed.
    pub installed: Vec<String>,
    pub already_present: Vec<String>,
    /// False on every boot after the first; see [`LinuxProvisioner::write_netplan_drop_in`].
    pub netplan_drop_in_written: bool,
    /// How many `xdpyinfo` attempts the display took to answer: 1 on a warm worker, more on a first boot. A number
    /// that starts creeping up is the earliest visible sign of an X server becoming slow to start.
    pub display_probes: u32,
}

/// Provisions one booted worker. The steps worth pinning are properties of the *transcript* - which commands,
/// with which argv, in which order, and which ones a warm worker skips - which is what a swapped runner answers.
pub(crate) struct LinuxProvisioner<R> {
    pub args: ProvisionGuestArgs,
    pub screen: String,
    /// Prefixes the guest paths this verb writes natively: `/` in the guest, a temporary directory in a test.
    /// Paths that only appear inside an argv are not re-rooted; nothing executes them in a test.
    pub root: PathBuf,
    pub euid: u32,
    pub runner: R,
}

impl<R: Runner> LinuxProvisioner<R> {
    pub(crate) fn new(args: ProvisionGuestArgs, screen: String, euid: u32, runner: R) -> Self {
        Self {
            args,
            screen,
            root: PathBuf::from("/"),
            euid,
            runner,
        }
    }

    fn rooted(&self, guest_path: &str) -> PathBuf {
        self.root.join(guest_path.trim_start_matches('/'))
    }

    /// The script, in the script's order.
    pub(crate) fn provision(&mut self) -> Result<ProvisionReport, AgentRefusal> {
        // Root first, because every step after it writes a system file, installs a package or talks to systemd,
        // and an unprivileged run would fail four steps in with whichever of those complained first.
        if self.euid != 0 {
            return Err(AgentRefusal::refused(
                "linux_provision_not_root",
                format!(
                    "{VERB} installs packages, system units and a netplan drop-in, so it must run \
                     as root, and its effective uid is {}",
                    self.euid
                ),
            ));
        }
        let (installed, already_present) = self.install_packages()?;
        let netplan_drop_in_written = self.write_netplan_drop_in()?;
        self.write_fluxbox_overlay()?;
        self.install_display_units()?;
        self.create_worker_directories()?;
        let display_probes = self.await_display()?;
        Ok(ProvisionReport {
            display: self.args.display.clone(),
            screen: self.screen.clone(),
            installed,
            already_present,
            netplan_drop_in_written,
            display_probes,
        })
    }

    /// Installs what the guest does not already have, and nothing else.
    ///
    /// One `dpkg-query` per package and one `apt-get install` for the whole remainder: the probes are local and
    /// cost milliseconds, while a second `apt-get` would be a second chance for a transient mirror failure to leave
    /// the worker holding part of the list. `apt-get update` runs only when something is missing, because
    /// refreshing an index on a warm worker is the largest avoidable cost in a boot.
    ///
    /// `DEBIAN_FRONTEND=noninteractive` on both apt invocations: a `debconf` prompt in a guest nobody watches hangs
    /// until the controller's exec timeout, which reads as a boot that never finished.
    fn install_packages(&mut self) -> Result<(Vec<String>, Vec<String>), AgentRefusal> {
        let (mut missing, mut present) = (Vec::new(), Vec::new());
        for name in self.args.packages.clone() {
            // A `dpkg-query` that fails is a package that is not installed, as the script read it; anything
            // `apt-get` then cannot resolve refuses below, by name.
            let status = self
                .runner
                .run(&Step::new([DPKG_QUERY_TOOL, "-W", "-f=${Status}", &name]).capture())
                .unwrap_or_default();
            if status.trim() == DPKG_INSTALLED_STATUS {
                present.push(name);
            } else {
                missing.push(name);
            }
        }
        if missing.is_empty() {
            return Ok((missing, present));
        }
        let refused = |what: String| AgentRefusal::refused("linux_package_install_failed", what);
        let update = Step::new([APT_GET_TOOL, "update", "-qq"]);
        self.runner
            .run(&noninteractive(update))
            .map_err(|error| refused(format!("apt-get update failed before installing {}: {error:#}", missing.join(" "))))?;
        let install = Step::new(
            [APT_GET_TOOL, "install", "-y", "-qq"]
                .into_iter()
                .chain(missing.iter().map(String::as_str)),
        );
        self.runner
            .run(&noninteractive(install))
            .map_err(|error| refused(format!("apt-get could not install {}: {error:#}", missing.join(" "))))?;
        Ok((missing, present))
    }

    /// Makes the guest identify itself to DHCP by its MAC address.
    ///
    /// `tart ip` reads the host's DHCP lease file and matches on the MAC. Ubuntu's DHCP client sends a DUID-EN
    /// client identifier by default, which overwrites `hw_address` in that lease file and leaves nothing to match.
    ///
    /// **Written once and then left alone**, the one guard here that is a correctness rule rather than a saved
    /// second. `netplan apply` bounces the interfaces - including the one the controller runs this command over -
    /// so a warm worker that re-applied its network configuration would drop the exec channel mid-provisioning.
    /// The file's presence is the whole mechanism and its content is deliberately not compared: a drop-in an
    /// operator edited must not be silently overwritten and re-applied.
    ///
    /// `netplan apply`'s failure is ignored, as the script's `|| true` ignored it: the drop-in is on disk and takes
    /// effect at the next boot regardless.
    fn write_netplan_drop_in(&mut self) -> Result<bool, AgentRefusal> {
        let path = self.rooted(NETPLAN_DROP_IN);
        if fs::metadata(&path).is_ok_and(|info| info.is_file()) {
            return Ok(false);
        }
        write_guest_file(&path, NETPLAN_DROP_IN_MODE, NETPLAN_DROP_IN_CONTENT).map_err(|error| {
            AgentRefusal::refused(
                "linux_netplan_dropin_unwritable",
                format!(
                    "the DHCP client-identifier drop-in could not be written to {NETPLAN_DROP_IN}: {error}; \
                     without it Ubuntu sends a DUID-EN identifier, which overwrites hw_address in the host's \
                     lease file and leaves `tart ip` nothing to match on"
                ),
            )
        })?;
        let _ = self.runner.run(&Step::new([NETPLAN_TOOL, "apply"]).silent());
        Ok(true)
    }

    /// Writes the system-wide fluxbox overlay that keeps the style from setting a wallpaper.
    ///
    /// Before the units, because fluxbox copies the template into the worker account's home on its first start,
    /// and that start is the `enable --now` of the next step. Written unconditionally, like the units: the file is
    /// inert until a first start reads it, and a rewrite costs nothing. A copy an account already holds is not
    /// touched, so a warm worker keeps the wallpaper it has, and a recycled one starts without `fbsetbg`.
    fn write_fluxbox_overlay(&self) -> Result<(), AgentRefusal> {
        let path = self.rooted(FLUXBOX_OVERLAY);
        write_guest_file(&path, FLUXBOX_OVERLAY_MODE, FLUXBOX_OVERLAY_CONTENT).map_err(|error| {
            AgentRefusal::refused(
                "linux_fluxbox_overlay_unwritable",
                format!(
                    "the fluxbox overlay could not be written to {}: {error}; without it the style runs \
                     fbsetbg, which opens an xmessage on the display when no wallpaper setter is installed",
                    path.display()
                ),
            )
        })
    }

    /// Writes the X server and the window manager as system services and starts them.
    ///
    /// Written unconditionally: a unit file is inert until `daemon-reload` and `enable --now` on a running unit is
    /// a no-op, so re-writing them guarantees a worker's units match the controller that provisioned it, where a
    /// guard would leave a display running the geometry of whichever boot first created it. `enable --now` because
    /// `enable` survives the reboot a worker gets when the host power-cycles it and `--now` makes this boot's
    /// display exist without one. Its chatter is dropped; its failure is not.
    fn install_display_units(&mut self) -> Result<(), AgentRefusal> {
        let units = [(XVFB_UNIT, self.xvfb_unit_content()), (FLUXBOX_UNIT, self.fluxbox_unit_content())];
        for (name, content) in units {
            let path = self.rooted(&format!("{SYSTEMD_UNIT_DIRECTORY}/{name}"));
            write_guest_file(&path, UNIT_FILE_MODE, &content).map_err(|error| {
                AgentRefusal::refused(
                    "linux_display_unit_unwritable",
                    format!("{name} could not be written to {}: {error}", path.display()),
                )
            })?;
        }
        let not_enabled = |what: String| AgentRefusal::refused("linux_display_service_not_enabled", what);
        self.runner.run(&Step::new([SYSTEMCTL_TOOL, "daemon-reload"])).map_err(|error| {
            not_enabled(format!(
                "systemctl daemon-reload failed after writing {XVFB_UNIT} and {FLUXBOX_UNIT}: {error:#}"
            ))
        })?;
        self.runner
            .run(&Step::new([SYSTEMCTL_TOOL, "enable", "--now", XVFB_UNIT, FLUXBOX_UNIT]).silent())
            .map_err(|error| {
                not_enabled(format!(
                    "systemctl could not enable and start {XVFB_UNIT} and {FLUXBOX_UNIT}: {error:#}"
                ))
            })?;
        Ok(())
    }

    /// The X server unit. `-ac` disables X's host-based access control, which lets the worker account's own IDE
    /// connect without an `.Xauthority` this pair would have to distribute; the display is loopback-only inside a
    /// disposable guest that holds no credentials. `Restart=always` because an X server that died takes every
    /// window with it, and a worker that restarts its display is one a later lane can still use.
    pub(crate) fn xvfb_unit_content(&self) -> String {
        let (display, screen, user) = (&self.args.display, &self.screen, &self.args.user);
        format!(
            "[Unit]
Description=Air UI worker X server on {display}
After=network.target

[Service]
ExecStart={XVFB_BINARY} {display} -ac -screen 0 {screen}
Restart=always
User={user}

[Install]
WantedBy=multi-user.target
"
        )
    }

    /// The window manager unit. `Requires=` plus `After=` on the X server's, so systemd orders the two and stops
    /// the window manager when its display goes away - a fluxbox left running against a dead X server is exactly
    /// the stale `_NET_SUPPORTING_WM_CHECK` root property `validate-guest`'s second hop exists to catch.
    pub(crate) fn fluxbox_unit_content(&self) -> String {
        let (display, user) = (&self.args.display, &self.args.user);
        format!(
            "[Unit]
Description=Air UI worker window manager on {display}
After={XVFB_UNIT}
Requires={XVFB_UNIT}

[Service]
Environment=DISPLAY={display}
ExecStart={FLUXBOX_BINARY}
Restart=always
User={user}

[Install]
WantedBy=multi-user.target
"
        )
    }

    /// Creates the writable state the parity layout diverts into, and the share mount point, which has to exist
    /// before the read-only shares can be mounted over it.
    ///
    /// `install -d` rather than a native create plus chown: `-o admin -g admin` resolves an account *name*, and
    /// resolving it here would put a second answer to "what is this account's uid" in the guest (1000 here, 501 on
    /// a macOS worker).
    fn create_worker_directories(&mut self) -> Result<(), AgentRefusal> {
        let owner = self.args.user.as_str();
        let worker_data = self.args.worker_data.to_string_lossy().into_owned();
        let state = self.args.worker_data.join("state").to_string_lossy().into_owned();
        let share_mount = self.args.share_mount.to_string_lossy().into_owned();
        let steps = [
            Step::new([INSTALL_TOOL, "-d", "-o", owner, "-g", owner, "-m", "0755", &worker_data, &state]),
            // Root-owned: nothing in the guest writes into the mount point, and the shares over it are read-only.
            Step::new([INSTALL_TOOL, "-d", "-m", "0755", &share_mount]),
        ];
        for step in steps {
            self.runner.run(&step).map_err(|error| {
                AgentRefusal::refused(
                    "linux_worker_directory_not_created",
                    format!("{} failed: {error:#}", step.command_line()),
                )
            })?;
        }
        Ok(())
    }

    /// Waits for the display to answer, rather than declaring success on `systemctl enable`.
    ///
    /// A worker whose X server is still starting and one whose X server failed are the same observation until a
    /// client connects: `systemctl` says `active` for both, and `Restart=always` keeps saying it. `xdpyinfo` and
    /// not a port probe, because it is an X client: it completes a connection and reads the server's own
    /// description of the display, which is what the IDE's toolkit will do. Silent, because its chatter would be
    /// 59 `unable to open display` lines in front of the one refusal that matters; the count reaches the report.
    fn await_display(&mut self) -> Result<u32, AgentRefusal> {
        let probe = on_display(Step::new([XDPYINFO_TOOL]).silent(), &self.args.display);
        for attempt in 1..=WAIT_ATTEMPTS {
            if self.runner.run(&probe).is_ok() {
                return Ok(attempt);
            }
            if attempt < WAIT_ATTEMPTS {
                self.runner.pause(WAIT_INTERVAL);
            }
        }
        Err(AgentRefusal::refused(
            "linux_display_not_answering",
            format!(
                "the X display {} did not answer {XDPYINFO_TOOL} within {} of {XVFB_UNIT} being enabled; \
                 {XVFB_UNIT} is serving nothing, and every IDE this worker starts would open its own headless \
                 display with no window manager on it",
                self.args.display,
                wait_budget()
            ),
        ))
    }
}

fn noninteractive(step: Step) -> Step {
    step.env("DEBIAN_FRONTEND", "noninteractive")
}

/// Writes one of this verb's own files, creating its directory. The mode is set explicitly because both files are
/// written over an existing copy on every boot but the first, and a create mode applies only to a new file.
fn write_guest_file(path: &Path, mode: u32, content: &str) -> io::Result<()> {
    if let Some(parent) = path.parent() {
        fs::create_dir_all(parent)?;
    }
    fs::write(path, content)?;
    fs::set_permissions(path, fs::Permissions::from_mode(mode))
}
