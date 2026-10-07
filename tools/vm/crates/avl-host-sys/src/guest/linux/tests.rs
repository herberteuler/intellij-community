use std::collections::HashSet;
use std::path::Path;

use avl_base::config::{GUEST_NODE_VERSION, pins};
use avl_base::report::{Mode, Terminal};
use avl_base::{Environment, GuestOs, Selection};
use pretty_assertions::assert_eq;

use super::*;
use crate::testing::fixture_backend;

fn settings() -> Config {
    Config::load(
        Selection {
            backend: fixture_backend(GuestOs::Linux),
            guest_os: GuestOs::Linux,
        },
        &Environment::from_pairs([("HOME", "/Users/air"), ("TART_BIN", "tart")]),
        Path::new("/repo/scripts"),
    )
    .unwrap()
}

// The package list is the one place these names live, so what each group is for is pinned: without the first the
// guest has no display and no window manager, and without the second the IDE starts and logs one SEVERE line about
// Skiko while the lane keeps running.
#[test]
fn the_guest_packages_cover_the_display_and_the_ides_own_native_dependencies() {
    let present: HashSet<&str> = GUEST_PACKAGES.iter().copied().collect();
    assert_eq!(present.len(), GUEST_PACKAGES.len(), "a package is listed twice");
    for needed in ["xvfb", "fluxbox", "x11-utils"] {
        assert!(present.contains(needed), "the display group is missing {needed}");
    }
    // No Node and no npm: Ubuntu's `nodejs` is too old for the agent CLIs and it packages no `npm`; the controller
    // stages a pinned Node archive instead.
    for refused in ["nodejs", "npm"] {
        assert!(!present.contains(refused), "{refused} is in the install set");
    }
    for needed in [
        "libegl1",
        "libgtk-3-0t64",
        "libxdamage1",
        "libxfixes3",
        "libasound2t64",
        "libatk1.0-0t64",
        "libatk-bridge2.0-0t64",
        "libatspi2.0-0t64",
        "libnss3",
    ] {
        assert!(present.contains(needed), "the IDE's native dependencies are missing {needed}");
    }
    // The recorder encodes the video itself.
    assert!(
        !present.contains("ffmpeg"),
        "the guest installs ffmpeg, which the trace recorder no longer needs"
    );
}

// Positional contract with the guest agent's `validate-guest`: the display, then the runtime root whose shared
// objects it sweeps - not the IDE root, where nothing is staged and the sweep would find nothing on every boot.
#[test]
fn the_validate_argv_passes_what_the_guest_is_checked_against() {
    let resolved = settings();
    let argv = validate_argv(&resolved);
    assert_eq!(argv, [resolved.guest_display.clone(), resolved.guest_runtime_root()]);
    for word in &argv {
        assert!(!GUEST_PACKAGES.contains(&word.as_str()), "the package list leaked in: {argv:?}");
    }
}

// This verb runs before the share the Node is staged from is mounted, so a Node here refused every Linux boot.
#[test]
fn the_validate_argv_names_no_node() {
    let major = pins::guest_node_major().to_string();
    for word in validate_argv(&settings()) {
        assert!(
            !word.contains("node") && word != major,
            "the self-check is told about a Node: {word}"
        );
    }
}

// The pinned major and the pinned version are the same number, and the controller's own Node path carries it.
#[test]
fn the_controllers_node_path_carries_the_pinned_major() {
    let resolved = settings();
    let major = pins::guest_node_major();
    assert!(major > 0);
    assert!(GUEST_NODE_VERSION.starts_with(&format!("{major}.")), "{GUEST_NODE_VERSION}");
    assert!(resolved.vm_node.contains(&major.to_string()), "{}", resolved.vm_node);
    // Absolute, so `check-node` reads the binary the supervisor will run, not the first on the guest's PATH.
    assert!(resolved.vm_node.starts_with('/'), "{}", resolved.vm_node);
    // The staged one, by the path the controller stages it to.
    assert_eq!(resolved.vm_node, resolved.staged_node_binary());
}

// Positional contract with the guest agent's `provision-guest`: the order is asserted rather than the set, because
// the verb checks the shapes of three of the four values, and only if both sides agree on which slot is which.
#[test]
fn the_provision_argv_passes_every_value_the_verb_needs() {
    let resolved = settings();
    let argv = provision_argv(&resolved);
    let head = [
        resolved.vm_data.clone(),
        resolved.guest_display.clone(),
        resolved.vm_user.clone(),
        resolved.guest.share_mount.to_owned(),
    ];
    assert_eq!(argv[..4], head);
    // The package list is spread as trailing arguments, so the verb's install set is this list.
    assert_eq!(argv[4..], *GUEST_PACKAGES);
    assert!(!resolved.guest_display.is_empty());
    // The mount point, not one share's path under it.
    assert_eq!(argv[3], "/mnt/AirVmShares");
    // The screen geometry reaches the verb through the guest's own environment, never the argv.
    assert!(argv.iter().all(|word| !word.contains("1920x1080")), "{argv:?}");
}

#[test]
fn provisioning_is_reported_because_silence_reads_as_a_hang() {
    let (reporter, stdout, stderr) = Reporter::in_memory("vm");
    reporter.set_mode(Mode::Human(Terminal::default()));
    note_provisioning(&reporter, "air-linux-1");
    let said = stderr.text();
    assert!(said.contains("air-linux-1") && said.contains("X server"), "{said}");
    assert!(stdout.is_empty(), "progress went to stdout: {}", stdout.text());
}
