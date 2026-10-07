//! Turning a freshly cloned public Ubuntu image into a worker.
//!
//! # Why this is guest code and not a backend
//!
//! There is no Linux hypervisor, and there should not be. Linux is a *guest* axis: a Linux worker runs on the Tart
//! backend, is reached by `tart exec`, and is cloned, started, stopped and deleted by exactly the code a macOS
//! worker is. What it changes is what the *guest* needs, which is why this module holds a package list and the argv
//! of two guest-agent verbs, beside the rest of the guest setup. Its one refusal - no suspend, because
//! `tart run --suspendable` fails with "You can only suspend macOS VMs" - lives on the Tart backend.
//!
//! # Why it is so much shorter than the macOS side
//!
//! Most of the Tart backend exists to make a *sealed* image trustworthy. A Linux worker needs none of that: the
//! base is a public image pinned by digest, the controller installs a fixed package set over it on every boot, and
//! the guest holds no checkout, no credentials and no cache. What a macOS image build buys by auditing, a Linux
//! worker gets by being disposable.
//!
//! What it does need is a display. A Linux guest has no seat until something starts an X server on it, and IDE
//! Starter starts its own per-launch `xvfb-run` when DISPLAY is unset - which would give every IDE in a lane a
//! private display with no window manager on it. The guest service this installs is what makes DISPLAY already
//! set.

use avl_base::{Config, Reporter, Scope};

#[cfg(test)]
mod tests;

/// The packages a worker needs beyond the base image.
///
/// `x11-utils` is not decoration: `XorgWindowManagerHandler` shells out to `xprop` to decide whether a window
/// manager is registered, and reads its absence as no window manager at all.
///
/// **No Node here, and no `npm`.** The lane's tests need both, and neither Linux base supplies them in a usable
/// form. Ubuntu 24.04, the Tart base, has `nodejs` 18 and packages no `npm` at all. Ubuntu 26.04, the Docker base,
/// has `nodejs` 22 (the `apt-cache policy` candidate on 2026-09-29). Both are below `NODE_MAJOR`, and a current
/// `@openai/codex` refuses an older major. So the controller stages a checksum-pinned Node archive instead, which
/// carries `npm` with it (the `stage-node` verb).
///
/// The second group is what the IDE's *own* native libraries link against, which a server image does not carry:
/// the JBR ships the `.so` files, not their dependencies. Measured with `ldd` on `air-linux-1` (2026-08-19):
/// `libEGL.so.1` is the one Skiko needs, and the other five are what `libcef.so` and `libjcef.so` need. Their
/// absence is quiet in the worst way - `Failed to preload Skiko` is one SEVERE line in `idea.log` while the lane
/// keeps running - so add to this list rather than letting a guest discover it.
///
/// `t64` suffixes are the 64-bit-`time_t` rename of Ubuntu 24.04, and Ubuntu 26.04 keeps them. The Tart workers
/// install these names on the Tart base (24.04). On 2026-09-29, `apt-cache policy` resolved every name here in an
/// arm64 container of the Docker base (26.04). A base-image bump is a reason to re-check every name here.
pub const GUEST_PACKAGES: &[&str] = &[
    "xvfb",
    "fluxbox",
    "x11-utils",
    // Skiko, which the IDE preloads for Compose rendering.
    "libegl1",
    // The JBR's own GTK lookup, which otherwise prints "Looking for GTK3 library... Not found." on every launch.
    "libgtk-3-0t64",
    // JCEF.
    "libxdamage1",
    "libxfixes3",
    "libasound2t64",
    "libatk1.0-0t64",
    "libatk-bridge2.0-0t64",
    "libatspi2.0-0t64",
    // `libcef.so` and `libjcef.so` link NSS: `libnss3.so`, `libnssutil3.so`, `libsmime3.so` and `libnspr4.so`, all
    // of which this one package carries. `validate-guest` refused a worker without it on 2026-09-29.
    "libnss3",
    // The merge-conflict and worktree scenarios spawn `git` from the lane IDE. The Tart base ships it; the Docker
    // base does not, and five `ui` scenarios failed without it on 2026-09-30.
    "git",
    // No video encoder: `air-trace-record` encodes the per-scenario video itself.
];

/// What the guest agent's `provision-guest` verb is told: the data root, the X display, the worker account, the
/// share mount point, then every package.
///
/// Positional, in the order the verb reads them: an absolute path, a `:88`, an account name and an absolute path are
/// four shapes no transposition survives, and the verb checks three of them - so a swap is refused by name rather
/// than installed. The share mount point is the directory the read-only shares are mounted over, not one share's
/// path under it. The package list is spread as trailing arguments, which makes [`GUEST_PACKAGES`] the single list.
/// The X screen geometry is deliberately absent: it reaches the verb as `AIR_VM_SCREEN` in the guest's own
/// environment, an operator's knob with no controller-side default.
///
/// A Tart Linux worker only. A Docker worker runs no `provision-guest`: its image installs [`GUEST_PACKAGES`] at
/// build time, and its entrypoint starts the display (see [`super::LinuxProvisioning::provision_argv`]).
pub fn provision_argv(settings: &Config) -> Vec<String> {
    [
        settings.vm_data.as_str(),
        &settings.guest_display,
        &settings.vm_user,
        settings.guest.share_mount,
    ]
    .into_iter()
    .chain(GUEST_PACKAGES.iter().copied())
    .map(str::to_owned)
    .collect()
}

/// What the guest agent's `validate-guest` verb is told: the display, and the runtime root whose shared objects it
/// runs `ldd` over.
///
/// Neither is the package list: the validator reads what the guest *has*, and one handed the list would need a
/// soname-to-package mapping to use it - a third copy. **No Node** either: this verb runs right after
/// `provision-guest`, before the controller mounts the read-only Bazel share the Node archive is read through, so
/// a Node check here refused every boot ahead of the staging it was meant to prove; `check-node` has it now. And
/// the root is [`Config::guest_runtime_root`], where the JBR and JCEF are, not the IDE root, where nothing is
/// staged and the sweep would find nothing on every boot.
pub fn validate_argv(settings: &Config) -> Vec<String> {
    vec![settings.guest_display.clone(), settings.guest_runtime_root()]
}

/// Reports what the guest is about to spend a minute or two on.
///
/// Worth a line of its own because this is the longest single step of a fresh Linux worker's first boot, and
/// silence there reads as a hang. The agent install is named first because it comes first and is not free (15.4 s
/// of pushing the binary, measured).
pub fn note_provisioning(reporter: &Reporter, worker: &str) {
    reporter.note(
        format!("provisioning {worker}: guest agent, X server, window manager, and worker storage"),
        Some(&Scope::worker(worker)),
    );
}
