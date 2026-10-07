//! `provision-guest`, tested by its transcript and by the files it writes.
//!
//! What can be checked off a guest is the transcript - which commands, with which argv, in which order, and which
//! ones a warm worker skips - and the content of the netplan drop-in and the two units, which is where a display's
//! geometry, its user and its ordering dependency are decided. Every case runs in milliseconds on any host,
//! including the ones that are not root and the one that exhausts a sixty-second wait.

use avl_wire::verb::AgentVerb;
use std::fs;
use std::os::unix::fs::PermissionsExt;

use avl_wire::supervisor::AgentExit;
use expect_test::expect;
use pretty_assertions::assert_eq;

use super::*;
use crate::reply::verb_refusal_code;
use crate::step::tests_support::Transcript;
use crate::testing::run_agent;

/// Three of `linux.GuestPackages`' names and one of its native libraries: enough to exercise the missing/present
/// split without restating a list this verb does not own.
const PACKAGES: [&str; 4] = ["xvfb", "fluxbox", "x11-utils", "libegl1"];

struct Fixture {
    _root: tempfile::TempDir,
    provisioner: LinuxProvisioner<Transcript>,
}

impl Fixture {
    /// A provisioner that is root, whose commands are recorded, and whose written files land under a directory the
    /// test owns.
    fn new() -> Self {
        let root = tempfile::tempdir().unwrap();
        let args = ProvisionGuestArgs {
            worker_data: PathBuf::from("/home/admin/WorkerData"),
            display: ":88".to_owned(),
            user: "admin".to_owned(),
            share_mount: PathBuf::from("/mnt/AirVmShares"),
            packages: PACKAGES.map(str::to_owned).to_vec(),
        };
        let mut provisioner = LinuxProvisioner::new(args, DEFAULT_SCREEN_GEOMETRY.to_owned(), 0, Transcript::default());
        provisioner.root = root.path().to_path_buf();
        Self { _root: root, provisioner }
    }

    fn transcript(&mut self) -> &mut Transcript {
        &mut self.provisioner.runner
    }

    /// Makes one package answer `dpkg-query` as already present.
    fn installed_package(&mut self, name: &str) {
        self.transcript().answers.insert(
            format!("{DPKG_QUERY_TOOL} -W -f=${{Status}} {name}"),
            DPKG_INSTALLED_STATUS.to_owned(),
        );
    }

    fn fail(&mut self, line: &str) {
        self.transcript().fails.insert(line.to_owned());
    }

    fn guest_file(&self, guest_path: &str) -> PathBuf {
        self.provisioner.rooted(guest_path)
    }

    fn unit(&self, name: &str) -> PathBuf {
        self.guest_file(&format!("{SYSTEMD_UNIT_DIRECTORY}/{name}"))
    }

    fn write_guest_file(&self, guest_path: &str, content: &str) {
        let path = self.guest_file(guest_path);
        fs::create_dir_all(path.parent().unwrap()).unwrap();
        fs::write(path, content).unwrap();
    }

    fn provision(&mut self) -> Result<ProvisionReport, AgentRefusal> {
        self.provisioner.provision()
    }

    fn lines(&self) -> Vec<String> {
        self.provisioner.runner.lines()
    }
}

fn apt_install_all() -> String {
    format!("{APT_GET_TOOL} install -y -qq {}", PACKAGES.join(" "))
}

fn enable_units() -> String {
    format!("{SYSTEMCTL_TOOL} enable --now {XVFB_UNIT} {FLUXBOX_UNIT}")
}

const STATE_DIRECTORIES: &str = "install -d -o admin -g admin -m 0755 /home/admin/WorkerData /home/admin/WorkerData/state";
const SHARE_MOUNT_DIRECTORY: &str = "install -d -m 0755 /mnt/AirVmShares";

// --- the argument contract ------------------------------------------------------------------------------------

/// Four positionals plus at least one package, three of the four checked for shape, before anything is read. The
/// script had none of this: a missing argument shifted the display into the user slot and provisioned a worker
/// whose X server ran as an account named `:88`. Every refusal is usage (64): nothing in the guest was touched.
#[test]
fn the_provision_argv_is_checked_before_anything_is_read() {
    let cases: [(&str, &[&str]); 10] = [
        ("no arguments at all", &[]),
        (
            "the share mount and the packages are missing",
            &["/home/admin/WorkerData", ":88", "admin"],
        ),
        ("no packages", &["/home/admin/WorkerData", ":88", "admin", "/mnt/AirVmShares"]),
        ("a relative worker data directory", &["WorkerData", ":88", "admin", "/mnt", "xvfb"]),
        (
            "a display with no colon",
            &["/home/admin/WorkerData", "88", "admin", "/mnt", "xvfb"],
        ),
        // The one the script's `:[0-9]*` glob accepted.
        (
            "a display with a trailing letter",
            &["/home/admin/WorkerData", ":88x", "admin", "/mnt", "xvfb"],
        ),
        ("a relative share mount", &["/home/admin/WorkerData", ":88", "admin", "mnt", "xvfb"]),
        ("an empty worker user", &["/home/admin/WorkerData", ":88", "", "/mnt", "xvfb"]),
        (
            "a package name that is a flag",
            &["/home/admin/WorkerData", ":88", "admin", "/mnt", "--reinstall"],
        ),
        ("an empty package name", &["/home/admin/WorkerData", ":88", "admin", "/mnt", ""]),
    ];
    for (name, argv) in cases {
        let args: Vec<&str> = std::iter::once(AgentVerb::ProvisionGuest.as_str())
            .chain(argv.iter().copied())
            .collect();
        let answered = run_agent(&args, b"");
        assert_eq!((answered.exit, answered.code()), (64, "usage".to_owned()), "{name}");
        assert_eq!(answered.failure()["command"], AgentVerb::ProvisionGuest.as_str(), "{name}");
    }
    // The checks the verb makes after clap parsed, which is where an empty package name lands too.
    let refusal = check_packages(&["xvfb".to_owned(), "-f".to_owned()]).unwrap_err();
    assert_eq!((refusal.code.as_ref(), refusal.exit), ("usage", AgentExit::Usage));
    assert!(refusal.message.contains("\"-f\""), "{}", refusal.message);
    check_packages(&["xvfb".to_owned()]).unwrap();
}

/// There is no fallback install set, and the refusal says what a worker without the list cannot do.
#[test]
fn an_empty_install_set_is_refused_rather_than_defaulted() {
    let refusal = check_packages(&[]).unwrap_err();
    assert_eq!(refusal.exit, AgentExit::Usage);
    for fragment in ["GuestPackages", "xvfb", "SEVERE"] {
        assert!(refusal.message.contains(fragment), "{fragment}: {}", refusal.message);
    }
    let answered = run_agent(
        &[
            AgentVerb::ProvisionGuest.as_str(),
            "/home/admin/WorkerData",
            ":88",
            "admin",
            "/mnt/AirVmShares",
        ],
        b"",
    );
    assert_eq!(answered.exit, 64);
    let message = answered.failure()["error"]["message"].as_str().unwrap().to_owned();
    assert!(message.contains("GuestPackages"), "{message}");
}

/// The geometry is the one value from the guest's own environment, so the variable is part of the contract: unset
/// or empty is the default, an override is taken whole, and a malformed one is usage.
#[test]
fn the_screen_geometry_comes_from_the_guest_environment() {
    assert_eq!(screen_geometry(None).unwrap(), DEFAULT_SCREEN_GEOMETRY);
    assert_eq!(screen_geometry(Some("")).unwrap(), DEFAULT_SCREEN_GEOMETRY);
    assert_eq!(screen_geometry(Some("2560x1440x24")).unwrap(), "2560x1440x24");
    for malformed in ["1920x1080", "fullscreen", "1920x1080x24 -nolisten"] {
        let refusal = screen_geometry(Some(malformed)).unwrap_err();
        assert_eq!((refusal.code.as_ref(), refusal.exit), ("usage", AgentExit::Usage), "{malformed}");
        assert!(refusal.message.contains(SCREEN_VARIABLE), "{}", refusal.message);
    }
}

// --- the guest this verb needs --------------------------------------------------------------------------------

/// An unprivileged run is a class of its own, naming the uid it found, and reaches no step.
#[test]
fn provisioning_refuses_when_it_is_not_root() {
    let mut fixture = Fixture::new();
    fixture.provisioner.euid = 1000;
    let refusal = fixture.provision().unwrap_err();
    assert_eq!(refusal.code, "linux_provision_not_root");
    assert!(refusal.message.contains("1000"), "{}", refusal.message);
    assert!(fixture.lines().is_empty(), "{:?}", fixture.lines());
}

// --- a first boot ---------------------------------------------------------------------------------------------

/// The whole transcript of a fresh worker, in order: the packages first (nothing after could work before them), the
/// display wait last (`systemctl enable` returning is not a display), and `apt-get update` only before an install.
#[test]
fn a_first_boot_provisions_in_the_scripts_order() {
    let mut fixture = Fixture::new();
    let report = fixture.provision().unwrap();
    let mut want: Vec<String> = PACKAGES
        .iter()
        .map(|name| format!("{DPKG_QUERY_TOOL} -W -f=${{Status}} {name}"))
        .collect();
    want.extend([
        format!("{APT_GET_TOOL} update -qq"),
        apt_install_all(),
        format!("{NETPLAN_TOOL} apply"),
        format!("{SYSTEMCTL_TOOL} daemon-reload"),
        enable_units(),
        STATE_DIRECTORIES.to_owned(),
        SHARE_MOUNT_DIRECTORY.to_owned(),
        XDPYINFO_TOOL.to_owned(),
    ]);
    assert_eq!(fixture.lines(), want);
    assert_eq!(
        report,
        ProvisionReport {
            display: ":88".to_owned(),
            screen: DEFAULT_SCREEN_GEOMETRY.to_owned(),
            installed: PACKAGES.map(str::to_owned).to_vec(),
            already_present: vec![],
            netplan_drop_in_written: true,
            display_probes: 1,
        }
    );
    // The report's wire names, which the controller notes whole.
    expect![[r#"{"display":":88","screen":"1920x1080x24","installed":["xvfb","fluxbox","x11-utils","libegl1"],"alreadyPresent":[],"netplanDropInWritten":true,"displayProbes":1}"#]]
        .assert_eq(&serde_json::to_string(&report).unwrap());
}

/// Only what is missing reaches `apt-get`, once, and everything else is reported as found.
#[test]
fn only_the_missing_packages_are_installed() {
    let mut fixture = Fixture::new();
    fixture.installed_package("xvfb");
    fixture.installed_package("x11-utils");
    let report = fixture.provision().unwrap();
    let installs: Vec<String> = fixture
        .lines()
        .into_iter()
        .filter(|line| line.starts_with(&format!("{APT_GET_TOOL} install")))
        .collect();
    assert_eq!(installs, [format!("{APT_GET_TOOL} install -y -qq fluxbox libegl1")]);
    assert_eq!(report.already_present, ["xvfb", "x11-utils"]);
    assert_eq!(report.installed, ["fluxbox", "libegl1"]);
}

/// `DEBIAN_FRONTEND=noninteractive` on both apt invocations and nowhere else.
#[test]
fn both_apt_invocations_are_noninteractive() {
    let mut fixture = Fixture::new();
    fixture.provision().unwrap();
    let noninteractive = [("DEBIAN_FRONTEND".to_owned(), "noninteractive".to_owned())];
    for prefix in [format!("{APT_GET_TOOL} update"), format!("{APT_GET_TOOL} install")] {
        assert_eq!(fixture.provisioner.runner.step(&prefix).env, noninteractive, "{prefix}");
    }
}

// --- a warm boot ----------------------------------------------------------------------------------------------

/// A warm worker pays a few probes and nothing else: no apt, and no rewritten drop-in.
#[test]
fn a_warm_worker_skips_the_steps_that_are_guarded() {
    let mut fixture = Fixture::new();
    for name in PACKAGES {
        fixture.installed_package(name);
    }
    fixture.write_guest_file(NETPLAN_DROP_IN, "edited by hand\n");
    let report = fixture.provision().unwrap();
    assert!(!fixture.provisioner.runner.ran(APT_GET_TOOL), "{:?}", fixture.lines());
    assert!(report.installed.is_empty());
    assert!(!report.netplan_drop_in_written);
}

/// **The one guard that is a correctness rule.** `netplan apply` bounces the interface the controller is talking
/// over, so an existing drop-in - even an operator's edited one - is neither rewritten nor re-applied.
#[test]
fn an_existing_netplan_drop_in_is_neither_rewritten_nor_reapplied() {
    let mut fixture = Fixture::new();
    let edited = "network:\n  version: 2\n  # an operator was here\n";
    fixture.write_guest_file(NETPLAN_DROP_IN, edited);
    fixture.provision().unwrap();
    assert!(!fixture.provisioner.runner.ran(NETPLAN_TOOL), "{:?}", fixture.lines());
    assert_eq!(fs::read_to_string(fixture.guest_file(NETPLAN_DROP_IN)).unwrap(), edited);
}

// --- what provisioning writes ---------------------------------------------------------------------------------

/// The drop-in exists because `tart ip` matches a lease on the MAC; it is 0600 and applied once written.
#[test]
fn the_netplan_drop_in_is_written_private_and_applied() {
    let mut fixture = Fixture::new();
    fixture.provision().unwrap();
    let path = fixture.guest_file(NETPLAN_DROP_IN);
    assert_eq!(fs::metadata(&path).unwrap().permissions().mode() & 0o777, NETPLAN_DROP_IN_MODE);
    assert_eq!(fs::read_to_string(&path).unwrap(), NETPLAN_DROP_IN_CONTENT);
    assert!(NETPLAN_DROP_IN_CONTENT.contains("dhcp-identifier: mac"));
    assert!(fixture.provisioner.runner.ran(&format!("{NETPLAN_TOOL} apply")));
}

/// The two units carry the display, the geometry and the account, and the window manager's carries its ordering
/// dependency on the X server's: a fluxbox left running against a dead X server is exactly the stale property
/// `validate-guest` refuses, so `Requires=` is not decoration.
#[test]
fn the_display_units_carry_what_they_were_told() {
    let mut fixture = Fixture::new();
    fixture.provisioner.screen = "2560x1440x24".to_owned();
    fixture.provisioner.args.user = "worker".to_owned();
    fixture.provision().unwrap();
    for name in [XVFB_UNIT, FLUXBOX_UNIT] {
        let mode = fs::metadata(fixture.unit(name)).unwrap().permissions().mode() & 0o777;
        assert_eq!(mode, UNIT_FILE_MODE, "{name}");
    }
    expect![[r"
        [Unit]
        Description=Air UI worker X server on :88
        After=network.target

        [Service]
        ExecStart=/usr/bin/Xvfb :88 -ac -screen 0 2560x1440x24
        Restart=always
        User=worker

        [Install]
        WantedBy=multi-user.target
    "]]
    .assert_eq(&fs::read_to_string(fixture.unit(XVFB_UNIT)).unwrap());
    expect![[r"
        [Unit]
        Description=Air UI worker window manager on :88
        After=air-xvfb.service
        Requires=air-xvfb.service

        [Service]
        Environment=DISPLAY=:88
        ExecStart=/usr/bin/fluxbox
        Restart=always
        User=worker

        [Install]
        WantedBy=multi-user.target
    "]]
    .assert_eq(&fs::read_to_string(fixture.unit(FLUXBOX_UNIT)).unwrap());
}

/// The overlay is the system template fluxbox copies on its first start, so it is written before the units start
/// fluxbox, world-readable, and rewritten on every boot. `unset` and not Debian's commented `none`: `none` still
/// runs `fbsetbg`, which opens an `xmessage` on a guest without a wallpaper setter.
#[test]
fn the_fluxbox_overlay_unsets_the_style_background_before_the_units_start() {
    let mut fixture = Fixture::new();
    fixture.write_guest_file(FLUXBOX_OVERLAY, "! background: none\n");
    fixture.provision().unwrap();
    let path = fixture.guest_file(FLUXBOX_OVERLAY);
    assert_eq!(fs::read_to_string(&path).unwrap(), FLUXBOX_OVERLAY_CONTENT);
    assert_eq!(FLUXBOX_OVERLAY_CONTENT, "background: unset\n");
    assert_eq!(fs::metadata(&path).unwrap().permissions().mode() & 0o777, FLUXBOX_OVERLAY_MODE);
    // No process writes it: the overlay is this verb's own content, so it is not in the transcript.
    assert!(
        !fixture.lines().iter().any(|line| line.contains("overlay")),
        "{:?}",
        fixture.lines()
    );
}

/// The units are rewritten on every boot, unlike the drop-in: a guard would leave a worker's display running the
/// geometry of whichever boot first created it.
#[test]
fn the_display_units_are_rewritten_on_a_warm_worker() {
    let mut fixture = Fixture::new();
    fixture.write_guest_file(
        &format!("{SYSTEMD_UNIT_DIRECTORY}/{XVFB_UNIT}"),
        "[Unit]\nDescription=a unit from an older controller\n",
    );
    fixture.provision().unwrap();
    let content = fs::read_to_string(fixture.unit(XVFB_UNIT)).unwrap();
    assert!(!content.contains("older controller"), "{content}");
}

/// The state directories are the worker account's and the share mount point is root's: nothing in the guest writes
/// into the mount point, and the shares over it are read-only.
#[test]
fn the_worker_directories_are_created_with_the_ownership_each_needs() {
    let mut fixture = Fixture::new();
    fixture.provision().unwrap();
    let installs: Vec<String> = fixture
        .lines()
        .into_iter()
        .filter(|line| line.starts_with(&format!("{INSTALL_TOOL} ")))
        .collect();
    assert_eq!(installs, [STATE_DIRECTORIES, SHARE_MOUNT_DIRECTORY]);
}

// --- the wait -------------------------------------------------------------------------------------------------

/// The display is probed until it answers, as an X client on the display it was told about, and the probe count
/// reaches the report.
#[test]
fn the_wait_probes_until_the_display_answers() {
    let mut fixture = Fixture::new();
    fixture.transcript().fails_first.insert(XDPYINFO_TOOL.to_owned(), 3);
    let report = fixture.provision().unwrap();
    assert_eq!(report.display_probes, 4);
    assert_eq!(fixture.provisioner.runner.paused, [WAIT_INTERVAL; 3]);
    let probe = fixture.provisioner.runner.step(XDPYINFO_TOOL);
    assert_eq!(probe.env, [("DISPLAY".to_owned(), ":88".to_owned())]);
}

/// A display that never answers refuses rather than passing, after the whole budget and one pause fewer than
/// probes: the last failed attempt is the refusal, not another second of waiting.
#[test]
fn a_display_that_never_answers_refuses_rather_than_passing() {
    let mut fixture = Fixture::new();
    fixture.fail(XDPYINFO_TOOL);
    let refusal = fixture.provision().unwrap_err();
    assert_eq!(
        (refusal.code.as_ref(), refusal.exit),
        ("linux_display_not_answering", AgentExit::Refused)
    );
    for fragment in [":88", XVFB_UNIT, "60 s"] {
        assert!(refusal.message.contains(fragment), "{fragment}: {}", refusal.message);
    }
    let runner = &fixture.provisioner.runner;
    assert_eq!(runner.attempts[XDPYINFO_TOOL], WAIT_ATTEMPTS as usize);
    assert_eq!(runner.paused.len(), WAIT_ATTEMPTS as usize - 1);
}

// --- every other refusal path ---------------------------------------------------------------------------------

/// Each step refuses under its own code with the family's one status, and stops the verb where it stands.
#[test]
fn every_provisioning_step_refuses_with_its_own_code() {
    type Damage = fn(&mut Fixture);
    let cases: [(&str, Damage, &str, &str); 7] = [
        (
            "the package index cannot be refreshed",
            |fixture| fixture.fail(&format!("{APT_GET_TOOL} update -qq")),
            "linux_package_install_failed",
            "apt-get install",
        ),
        (
            "a package cannot be resolved",
            |fixture| fixture.fail(&apt_install_all()),
            "linux_package_install_failed",
            SYSTEMCTL_TOOL,
        ),
        (
            "the netplan drop-in cannot be written",
            // A regular file where the drop-in's directory belongs, so no directory can be made there.
            |fixture| fs::write(fixture.guest_file("/etc"), "not a directory").unwrap(),
            "linux_netplan_dropin_unwritable",
            SYSTEMCTL_TOOL,
        ),
        (
            "systemd will not reload its units",
            |fixture| fixture.fail(&format!("{SYSTEMCTL_TOOL} daemon-reload")),
            "linux_display_service_not_enabled",
            "systemctl enable",
        ),
        (
            "the display units cannot be enabled",
            |fixture| fixture.fail(&enable_units()),
            "linux_display_service_not_enabled",
            INSTALL_TOOL,
        ),
        (
            "the worker state directories cannot be created",
            |fixture| fixture.fail(STATE_DIRECTORIES),
            "linux_worker_directory_not_created",
            XDPYINFO_TOOL,
        ),
        (
            "the share mount point cannot be created",
            |fixture| fixture.fail(SHARE_MOUNT_DIRECTORY),
            "linux_worker_directory_not_created",
            XDPYINFO_TOOL,
        ),
    ];
    for (name, damage, code, stops) in cases {
        let mut fixture = Fixture::new();
        damage(&mut fixture);
        let refusal = fixture.provision().expect_err(name);
        assert_eq!(refusal.code, code, "{name}: {}", refusal.message);
        assert_eq!(refusal.exit, AgentExit::Refused, "{name}");
        assert!(
            !fixture.provisioner.runner.ran(stops),
            "{name}: {stops} ran after the step refused: {:?}",
            fixture.lines()
        );
    }
}

/// A refusal names what it was doing, because the script answered a bare exit integer from an unnamed line.
#[test]
fn a_provisioning_refusal_names_what_it_was_doing() {
    type Failing = fn() -> String;
    let cases: [(Failing, &[&str]); 3] = [
        (apt_install_all, &["xvfb", "libegl1"]),
        (enable_units, &[XVFB_UNIT, FLUXBOX_UNIT]),
        (|| SHARE_MOUNT_DIRECTORY.to_owned(), &["/mnt/AirVmShares"]),
    ];
    for (failing, mentions) in cases {
        let mut fixture = Fixture::new();
        fixture.fail(&failing());
        let refusal = fixture.provision().unwrap_err();
        for fragment in mentions {
            assert!(refusal.message.contains(fragment), "{fragment}: {}", refusal.message);
        }
    }
}

/// The verb's own refusal code, asserted beside the verb.
#[test]
fn the_provision_guest_verb_refuses_under_its_own_name() {
    assert_eq!(verb_refusal_code(AgentVerb::ProvisionGuest), "guest_provision_guest_failed");
}
