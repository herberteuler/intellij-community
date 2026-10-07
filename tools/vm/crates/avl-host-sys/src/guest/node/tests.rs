//! The seam is [`FakeBazel`], for the reason the agent install's is: two Bazel round-trips are seconds against an
//! idle server and worse against a busy one, and this resolution runs on every boot.

use avl_base::{GuestArch, GuestOs};
use pretty_assertions::assert_eq;

use super::*;
use crate::guest::testing::{FakeChannel, Host, node_archive_host, prose};
use crate::paths::GuestPaths;

// A Linux worker gets the pinned archive, by the guest path the controller will then name, as the account that reads
// it: what this extracts is executed by that account, and an `npm install -g` writes into the same directory later.
#[tokio::test]
async fn stage_guest_node_tells_the_guest_where_the_archive_is() {
    let host = Host::new(GuestOs::Linux);
    let settings = &host.settings;
    let (bazel, archive) = node_archive_host(&host);
    let channel = FakeChannel::new("air-linux-1");
    host.guest(&channel).stage_guest_node(&bazel).await.unwrap();
    assert_eq!(
        channel.lines(),
        [format!(
            "/usr/bin/sudo -H -u {} {} stage-node {} {} {GUEST_NODE_VERSION}",
            settings.vm_user,
            settings.vm_agent,
            settings.vm_node_root,
            GuestPaths::of(settings).unwrap().to_guest(&archive).unwrap()
        )]
    );
    // The controller names the very binary the archive will publish, so what runs is what this staged.
    assert_eq!(settings.vm_node, format!("{}/{GUEST_NODE_VERSION}/bin/node", settings.vm_node_root));
}

// The resolution asks Bazel two questions and neither of them builds: a `cquery` and an `info`, because a build
// under the worker's lease-operation lock would spend minutes where every other step spends seconds.
#[tokio::test]
async fn resolving_the_node_archive_never_builds() {
    let host = Host::new(GuestOs::Linux);
    let (bazel, archive) = node_archive_host(&host);
    let ctx = Ctx::background();
    let resolved = resolve_node_archive(&ctx, &host.settings, &bazel).await.unwrap();
    assert_eq!(resolved, archive);
    let state = bazel.state();
    assert_eq!(state.queries, 2);
    assert_eq!(state.last_query, ["info", "output_base"]);
    // Memoized, so a second boot of the same worker asks nothing.
    resolve_node_archive(&ctx, &host.settings, &bazel).await.unwrap();
    assert_eq!(bazel.state().queries, 2);
}

// An archive nothing fetched is refused by name, and the refusal carries the command that fixes it.
#[tokio::test]
async fn an_unfetched_node_archive_is_refused_with_the_build_to_run() {
    let host = Host::new(GuestOs::Linux);
    let (bazel, archive) = node_archive_host(&host);
    std::fs::remove_file(&archive).unwrap();
    let refusal = resolve_node_archive(&Ctx::background(), &host.settings, &bazel).await.unwrap_err();
    assert_eq!(refusal.code, "guest_node_missing");
    let label = node_archive_label(GuestOs::Linux, GuestArch::Arm64).unwrap();
    for named in [archive.to_string_lossy().as_ref(), &label, "bazel.cmd build"] {
        assert!(refusal.message.contains(named), "no {named:?}: {}", refusal.message);
    }
}

// The label is built from the pinned version, so the two cannot disagree silently. A macOS worker stages nothing:
// its Node comes from the sealed golden image.
#[test]
fn only_a_linux_guest_has_a_node_archive_label() {
    assert_eq!(
        node_archive_label(GuestOs::Linux, GuestArch::Arm64).unwrap(),
        format!("@air_acp_runtime_test_deps//:node-v{GUEST_NODE_VERSION}-linux-arm64.tar.gz")
    );
    // Node spells x86_64 `x64`, and the runtime matrix pins the archive under that name.
    assert_eq!(
        node_archive_label(GuestOs::Linux, GuestArch::X86_64).unwrap(),
        format!("@air_acp_runtime_test_deps//:node-v{GUEST_NODE_VERSION}-linux-x64.tar.gz")
    );
    for arch in [GuestArch::Arm64, GuestArch::X86_64] {
        assert_eq!(node_archive_label(GuestOs::Macos, arch), None);
    }
}

// An operator's `AIR_VM_NODE` means "run the Node I supplied", so nothing is staged and no Bazel question is asked -
// and it is said out loud, because a worker running an operator's Node is the state a later `node --version`
// refusal would otherwise be read against.
#[tokio::test]
async fn stage_guest_node_is_skipped_when_the_operator_supplied_a_node() {
    let mut host = Host::new(GuestOs::Linux);
    host.settings.vm_node = "/usr/local/bin/node".to_owned();
    let (bazel, _) = node_archive_host(&host);
    let channel = FakeChannel::new("air-linux-1");
    let (reporter, said) = prose();
    host.guest_reporting(&channel, &reporter).stage_guest_node(&bazel).await.unwrap();
    assert!(channel.calls().is_empty(), "{:?}", channel.lines());
    assert_eq!(bazel.state().queries, 0);
    assert!(said.text().contains("/usr/local/bin/node"), "{}", said.text());
}

// A named archive is answered by a stat, and a boot that names one asks Bazel nothing; a name that is not a file is
// refused as what it is.
#[tokio::test]
async fn a_named_node_archive_is_used_without_asking_bazel() {
    let mut host = Host::new(GuestOs::Linux);
    let (bazel, archive) = node_archive_host(&host);
    host.settings.vm_node_archive = Some(archive.clone());
    let ctx = Ctx::background();
    assert_eq!(resolve_node_archive(&ctx, &host.settings, &bazel).await.unwrap(), archive);
    assert_eq!(bazel.state().queries, 0);
    host.settings.vm_node_archive = Some(host.dir().to_owned());
    assert_eq!(
        resolve_node_archive(&ctx, &host.settings, &bazel).await.unwrap_err().code,
        "guest_node_missing"
    );
}
