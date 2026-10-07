//! The Linux worker's guest half: what `provision-guest`, `validate-guest`, `stage-node` and `check-node` are told,
//! what they name in the guest, and how they refuse.
//!
//! `provision-guest` turns a freshly cloned public Ubuntu clone into a UI worker and `validate-guest` proves the
//! result, invoked back to back by the controller as root on *every* boot. What they share is declared here rather
//! than in either of them: the pair's whole value is that the second one checks the first one's work, so a
//! display, a unit name or a directory that only one of them spelled would be a fact they could come to disagree
//! about.
//!
//! # There is no image behind these verbs
//!
//! A macOS worker is cloned from a golden image that a Packer build sealed and an offline audit cleared. A Linux
//! worker has none of that: the base is a public image pinned by digest, the controller installs a fixed package
//! set over it, and the guest holds no checkout, no credentials and no cache. So these verbs run on every boot and
//! must be **idempotent by construction** - each step checks for what it would create, and a warm worker's start
//! pays a few probes and nothing else.
//!
//! # The refusal vocabulary
//!
//! Each check answers a code naming the class of thing that is wrong, and a message carrying what it checked and
//! what it found. The status is [`AgentExit::Refused`](avl_wire::supervisor::AgentExit) for every one of them and
//! `Usage` for an argv the verb cannot act on at all; the set is closed.
//!
//! # What a refusal message may contain
//!
//! Parsed facts - a display, a window id, a soname, a path, a package name - and never a subprocess's own bytes.
//! These refusals travel to a *host controller*, which cannot tell this process's text from a child's. Where a
//! child's account is worth having, it is already on stderr, because a step streams it there.

pub(crate) mod check_node;
pub(crate) mod node;
pub(crate) mod provision;
pub(crate) mod validate;

use std::time::Duration;

use crate::step::Step;

// The display, as a system service.
//
// Two units rather than one, because they fail independently and a worker with an X server and no window manager
// is a distinct condition the validator has its own refusal for: `air-fluxbox.service` requires
// `air-xvfb.service`, so systemd orders them and restarts them separately.
//
// A *system* service rather than something a run starts, which is the whole reason this pair installs anything at
// all. IDE Starter wraps a launch in `xvfb-run --server-num=88` only when it finds DISPLAY unset
// (`XorgWindowManagerHandler`), so a guest with no display gives every IDE in a lane a private X server with no
// window manager on it - and without a window manager the driver cannot resize or raise a window.
pub(crate) const XVFB_UNIT: &str = "air-xvfb.service";
pub(crate) const FLUXBOX_UNIT: &str = "air-fluxbox.service";

/// Debian's system-wide fluxbox style overlay. Fluxbox copies it to `~/.fluxbox/overlay` when an account starts
/// fluxbox for the first time, and applies the copy over every style.
///
/// `provision-guest` writes [`FLUXBOX_OVERLAY_CONTENT`] here, and the Docker image writes the same line at build
/// time. An account that already holds a copy keeps it, so a warm Tart worker keeps its wallpaper until a recycle.
pub(crate) const FLUXBOX_OVERLAY: &str = "/etc/X11/fluxbox/overlay";

/// The overlay that stops a style from setting the root background.
///
/// Debian's styles set a wallpaper, and fluxbox runs `fbsetbg` for it. `fbsetbg` needs a wallpaper setter, `feh`
/// on Ubuntu, and opens an `xmessage` on the display when it finds none. `unset` is the value fluxbox 1.3.7 reads
/// for "leave the root alone". The Debian template comments out `background: none`, and that value still runs
/// `fbsetbg` (measured in the worker image on 2026-09-29). The root then keeps the X server's default, black.
pub(crate) const FLUXBOX_OVERLAY_CONTENT: &str = "background: unset\n";
/// Absolute in the unit files, because systemd's `ExecStart` requires an absolute path and has no PATH to search.
pub(crate) const SYSTEMD_UNIT_DIRECTORY: &str = "/etc/systemd/system";
pub(crate) const XVFB_BINARY: &str = "/usr/bin/Xvfb";
pub(crate) const FLUXBOX_BINARY: &str = "/usr/bin/fluxbox";

/// The budget each verb waits a display unit out on: one probe a second for a minute.
///
/// One budget for the X server (`provision-guest`) and the window manager (`validate-guest`), because systemd
/// starts both units in one `enable --now`, so a boot that gave them different minutes would be describing one
/// start-up with two numbers. A minute is long against a warm worker, where the first probe of each answers, and
/// short against a first boot, where `apt-get` has just installed Xvfb and fluxbox.
pub(crate) const WAIT_ATTEMPTS: u32 = 60;
pub(crate) const WAIT_INTERVAL: Duration = Duration::from_secs(1);

/// The whole budget, as a refusal message names it.
pub(crate) fn wait_budget() -> String {
    format!("{} s", (WAIT_INTERVAL * WAIT_ATTEMPTS).as_secs())
}

/// The one network file `provision-guest` writes. [`provision`] says what it fixes and why rewriting it on a warm
/// worker would be worse than leaving it.
pub(crate) const NETPLAN_DROP_IN: &str = "/etc/netplan/99-air-dhcp-identifier.yaml";

// The guest tools the verbs run by bare name, resolved through the guest's own PATH.
//
// `xdpyinfo` and `xprop` come from `x11-utils`, which is in `linux.GuestPackages` for the second of them:
// `XorgWindowManagerHandler` shells out to `xprop` to decide whether a window manager is registered and reads its
// absence as no window manager at all. So the tool this pair probes the display with is the tool the driver will
// probe it with.
pub(crate) const XDPYINFO_TOOL: &str = "xdpyinfo";
pub(crate) const XPROP_TOOL: &str = "xprop";
pub(crate) const LDD_TOOL: &str = "ldd";
pub(crate) const DPKG_QUERY_TOOL: &str = "dpkg-query";
pub(crate) const APT_GET_TOOL: &str = "apt-get";
pub(crate) const SYSTEMCTL_TOOL: &str = "systemctl";
pub(crate) const NETPLAN_TOOL: &str = "netplan";
pub(crate) const INSTALL_TOOL: &str = "install";
pub(crate) const DISPLAY_VARIABLE: &str = "DISPLAY";

/// The dynamic loaders of one guest architecture, and the JBR platform whose programs name them.
pub(crate) struct Loaders {
    /// The loader a glibc program of [`Self::jbr_platform`] names. It is the ELF's own `PT_INTERP`, so it is what
    /// the kernel loads before one instruction of the JBR runs.
    pub glibc: &'static str,
    /// The loader a musl guest has instead, named for the refusal alone: an Alpine base is the realistic way to
    /// arrive here, and a refusal that says musl is the difference between one sentence and an afternoon. It is
    /// statted and never run: musl's `ldd` is the loader itself and answers a usage block.
    pub musl: &'static str,
    /// The JBR build the lane stages on this architecture, as a refusal names it.
    pub jbr_platform: &'static str,
}

/// The loaders of an arm64 guest: a Tart worker, and a Docker worker on an Apple silicon host.
///
/// **The JBR is a glibc build.** Measured on `air-linux-2` on 2026-08-27: `ldd` on the staged `jbr/bin/java` names
/// `/lib/ld-linux-aarch64.so.1` and resolves `libc.so.6` out of `/lib/aarch64-linux-gnu/`.
pub(crate) const AARCH64_LOADERS: Loaders = Loaders {
    glibc: "/lib/ld-linux-aarch64.so.1",
    musl: "/lib/ld-musl-aarch64.so.1",
    jbr_platform: "linux_aarch64",
};

/// The loaders of an x86_64 guest: a Docker worker on an x86_64 host.
///
/// These are the fixed paths of the ABI. The x86-64 psABI names the glibc loader, and musl names its loader
/// `ld-musl-<arch>.so.1`. No worker has measured them yet.
pub(crate) const X86_64_LOADERS: Loaders = Loaders {
    glibc: "/lib64/ld-linux-x86-64.so.2",
    musl: "/lib/ld-musl-x86_64.so.1",
    jbr_platform: "linux_x64",
};

/// The loaders of the architecture this agent was built for.
///
/// That architecture is the guest's, because the controller installs the agent build of the guest's architecture
/// (`agent_label` in `avl-host-sys`). So a build-time choice is correct, and the agent probes nothing at run time.
pub(crate) const LOADERS: Loaders = if cfg!(target_arch = "x86_64") {
    X86_64_LOADERS
} else {
    AARCH64_LOADERS
};

/// The EWMH property that answers "is a window manager registered".
///
/// Read on the root window and then on the window it names, which is how `XorgWindowManagerHandler.isWmRunning`
/// reads it. Mirroring the driver's own probe is the point of the check: one that passed where the driver will
/// fail would move the failure back into the lane while claiming the guest was validated.
pub(crate) const SUPPORTING_WM_CHECK_PROPERTY: &str = "_NET_SUPPORTING_WM_CHECK";

/// One step that talks to the X server on `display`.
///
/// A per-step assignment rather than a process-wide one, because these verbs run several commands against one
/// display and nothing else in the process needs it; it overrides whatever the exec channel had.
pub(crate) fn on_display(step: Step, display: &str) -> Step {
    step.env(DISPLAY_VARIABLE, display)
}

/// Reads one window id out of `xprop`'s answer: the first field after a standalone `#` token, lowercased -
/// `_NET_SUPPORTING_WM_CHECK(WINDOW): window id # 0x400009` answers `0x400009`.
///
/// Lowercased because the two `xprop` invocations are compared against each other and X tools are not consistent
/// about hex case; the driver lowercases for the same reason. An answer with no `#` in it is the empty string,
/// which is what `xprop`'s own `not found.` line produces; both callers act on that differently, so it is not an
/// error here.
pub(crate) fn supporting_window_id(output: &str) -> String {
    for line in output.lines() {
        let mut fields = line.split_whitespace().skip_while(|field| *field != "#");
        if fields.next().is_some()
            && let Some(window) = fields.next()
        {
            return window.to_lowercase();
        }
    }
    String::new()
}

#[cfg(test)]
mod tests {
    use super::*;

    /// The parse is the scripts' `awk '{ for (i = 1; i <= NF; i++) if ($i == "#") { print tolower($(i + 1)); exit }
    /// }'`: the first field after a standalone `#`, lowercased, because the two hops are compared against each
    /// other and X tools are not consistent about hex case.
    #[test]
    fn the_supporting_window_id_is_read_the_way_the_driver_reads_it() {
        let cases = [
            ("_NET_SUPPORTING_WM_CHECK(WINDOW): window id # 0x40000A\n", "0x40000a"),
            ("_NET_SUPPORTING_WM_CHECK(WINDOW): window id # 0x40000a", "0x40000a"),
            ("_NET_SUPPORTING_WM_CHECK:  not found.\n", ""),
            ("", ""),
            // A trailing `#` with nothing after it must not read past the end of the line.
            ("_NET_SUPPORTING_WM_CHECK(WINDOW): window id #\n", ""),
            // The first `#` wins and the scan stops, which is `awk`'s `exit`.
            ("first: # 0xAAA\nsecond: # 0xBBB\n", "0xaaa"),
            // A `#` glued to its neighbour is not a standalone token.
            ("window id #0x1\n", ""),
        ];
        for (output, want) in cases {
            assert_eq!(supporting_window_id(output), want, "{output:?}");
        }
    }

    #[test]
    fn the_wait_budget_is_a_minute() {
        assert_eq!(wait_budget(), "60 s");
    }
}
