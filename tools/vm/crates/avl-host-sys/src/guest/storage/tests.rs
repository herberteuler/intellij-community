//! What these assert on is argv and *order*: the guest agent and its boot verbs have to reach the guest in the one
//! sequence where each step's destination already exists and nothing runs against a worker whose installer failed.
//! `AIR_VM_GUEST_AGENT_SOURCE` names the agent so the install resolves without Bazel, which is why every
//! provisioning case passes [`ForbiddenBazel`]: that is an assertion, not a shortcut - a staging step that came back would
//! fail the test rather than pass it.

use avl_base::GuestOs;
use avl_base::config::pins;
use avl_base::format::words;
use pretty_assertions::assert_eq;

use super::*;
use crate::guest::testing::{FakeChannel, ForbiddenBazel, Host, failed, has, node_archive_host, prose, said};
use crate::paths::GuestPaths;
use crate::proc::Captured;

fn linux_provisioning() -> LinuxProvisioning {
    LinuxProvisioning {
        provision_argv: Some(words(["/data", ":88", "admin", "/mnt/AirVmShares", "xvfb"])),
        // What `linux::validate_argv` passes: the display, and the runtime root the sweep reads. No Node - that pair
        // travels later in the boot.
        validate_argv: words([":88", "/data/daemon-runtime"]),
    }
}

fn provisioned_host() -> Host {
    let mut host = Host::new(GuestOs::Linux);
    host.settings.vm_agent_source = Some(host.agent_source("agent bytes"));
    host
}

// Provisioning ends with the guest proving itself, not with the installer exiting 0. The assertion is the whole
// sequence: an order that ran either verb before the install would run a binary that is not there. **No
// `stage-node`**, and the transcript is exact so that none can come back: that verb reads the archive through a
// share the parity step mounts after this function returns.
#[tokio::test]
async fn provision_linux_ends_with_the_guest_proving_itself() {
    let host = provisioned_host();
    let settings = &host.settings;
    let channel = FakeChannel::new("air-linux-1");
    host.guest(&channel)
        .provision_linux(&ForbiddenBazel, &linux_provisioning())
        .await
        .unwrap();
    let sudo = "/usr/bin/sudo -H";
    let as_worker = format!("{sudo} -u {}", settings.vm_user);
    let (agent, data) = (&settings.vm_agent, &settings.vm_data);
    assert_eq!(
        channel.lines(),
        [
            format!("{sudo} /bin/mkdir -p {data}/state"),
            format!("{sudo} {} -R {} {data}", settings.guest.chown, settings.vm_user),
            format!("{as_worker} /usr/bin/tee {agent}.incoming"),
            format!("{as_worker} /bin/chmod 700 {agent}.incoming"),
            format!("{as_worker} /bin/mv -f {agent}.incoming {agent}"),
            format!("{sudo} {agent} provision-guest /data :88 admin /mnt/AirVmShares xvfb"),
            format!("{sudo} {agent} validate-guest :88 /data/daemon-runtime"),
        ]
    );
    assert_eq!(channel.calls()[2].options.stdin.as_deref(), Some(&b"agent bytes"[..]));
    // Both verbs are root: `provision-guest` refuses any other effective uid, so a `-u` in front of either would be a
    // refused boot on every Linux worker.
    for verb in ["provision-guest", "validate-guest"] {
        let call = channel.saw(verb).expect("the verb ran");
        assert!(!has(&call.argv, "-u"), "{verb} ran as the worker: {:?}", call.argv);
    }
}

// A Docker worker's image installs the packages and its entrypoint starts the display, so `provision-guest` does
// not run. The agent install and `validate-guest` still do: the self-check exists to doubt exactly that image.
#[tokio::test]
async fn provision_linux_without_a_provision_argv_still_validates_the_guest() {
    let host = provisioned_host();
    let settings = &host.settings;
    let channel = FakeChannel::new("air-linux-1");
    let provisioning = LinuxProvisioning {
        provision_argv: None,
        ..linux_provisioning()
    };
    host.guest(&channel).provision_linux(&ForbiddenBazel, &provisioning).await.unwrap();
    let lines = channel.lines();
    assert!(channel.saw("provision-guest").is_none(), "{lines:?}");
    assert!(
        lines.contains(&format!(
            "/usr/bin/sudo -H {} validate-guest :88 /data/daemon-runtime",
            settings.vm_agent
        )),
        "{lines:?}"
    );
    assert!(
        lines
            .iter()
            .any(|line| line.contains(&format!("/bin/mv -f {0}.incoming {0}", settings.vm_agent))),
        "the agent was not installed: {lines:?}"
    );
}

// `stage-node` first, because `check-node` runs what it extracts; and both as the *worker*, unlike the two boot
// verbs, because a root-owned staged tree would be one the daemon's own account cannot add to.
#[tokio::test]
async fn ensure_guest_node_stages_the_node_and_then_checks_it() {
    let host = Host::new(GuestOs::Linux);
    let settings = &host.settings;
    let (bazel, archive) = node_archive_host(&host);
    let channel = FakeChannel::new("air-linux-1");
    host.guest(&channel).ensure_guest_node(&bazel).await.unwrap();
    let as_worker = format!("/usr/bin/sudo -H -u {} {}", settings.vm_user, settings.vm_agent);
    assert_eq!(
        channel.lines(),
        [
            format!(
                "{as_worker} stage-node {} {} {}",
                settings.vm_node_root,
                GuestPaths::of(settings).unwrap().to_guest(&archive).unwrap(),
                avl_base::config::GUEST_NODE_VERSION
            ),
            // The configured Node and the pinned major, so an operator's `AIR_VM_NODE` is checked too.
            format!("{as_worker} check-node {} {}", settings.vm_node, pins::guest_node_major()),
        ]
    );
}

// A staging that refused stops the boot there: a Node that was not extracted is a Node whose `--version` would
// report the staging failure as a missing binary, two steps from the cause.
#[tokio::test]
async fn ensure_guest_node_does_not_check_a_node_it_could_not_stage() {
    let host = Host::new(GuestOs::Linux);
    let (bazel, archive) = node_archive_host(&host);
    std::fs::remove_file(&archive).unwrap();
    let channel = FakeChannel::new("air-linux-1");
    let refusal = host.guest(&channel).ensure_guest_node(&bazel).await.unwrap_err();
    assert_eq!(refusal.code, "guest_node_missing");
    assert!(channel.saw("check-node").is_none(), "{:?}", channel.lines());
}

// An operator's `AIR_VM_NODE` stages nothing and is still **checked**: the case where a wrong Node is most likely,
// because the path is the operator's own.
#[tokio::test]
async fn ensure_guest_node_checks_an_operators_own_node() {
    let mut host = Host::new(GuestOs::Linux);
    host.settings.vm_node = "/usr/local/bin/node".to_owned();
    let (bazel, _) = node_archive_host(&host);
    let channel = FakeChannel::new("air-linux-1");
    host.guest(&channel).ensure_guest_node(&bazel).await.unwrap();
    assert_eq!(
        channel.lines(),
        [format!(
            "/usr/bin/sudo -H -u {} {} check-node /usr/local/bin/node {}",
            host.settings.vm_user,
            host.settings.vm_agent,
            pins::guest_node_major()
        )]
    );
    assert_eq!(bazel.state().queries, 0);
}

// A worker that was never asked to prove itself is refused rather than provisioned, before the guest is touched: a
// half-provisioned worker is worse than an unprovisioned one.
#[tokio::test]
async fn provision_linux_refuses_without_a_self_check() {
    let host = provisioned_host();
    let channel = FakeChannel::new("air-linux-1");
    let provisioning = LinuxProvisioning {
        validate_argv: Vec::new(),
        ..linux_provisioning()
    };
    let refusal = host
        .guest(&channel)
        .provision_linux(&ForbiddenBazel, &provisioning)
        .await
        .unwrap_err();
    assert_eq!(refusal.code, "linux_validation_unset");
    assert!(channel.calls().is_empty(), "{:?}", channel.lines());
}

// An installer that failed leaves nothing to validate, and a validator run against it would report the *display* as
// the problem when the cause was an `apt-get` that never finished.
#[tokio::test]
async fn provision_linux_does_not_validate_a_worker_whose_installer_failed() {
    let host = provisioned_host();
    let channel = FakeChannel::answering("air-linux-1", |argv| {
        Ok(if has(argv, "provision-guest") {
            failed(100, "E: Unable to fetch some archives")
        } else {
            Captured::default()
        })
    });
    let refusal = host
        .guest(&channel)
        .provision_linux(&ForbiddenBazel, &linux_provisioning())
        .await
        .unwrap_err();
    assert_eq!(refusal.code, "guest_agent_failed");
    assert!(channel.saw("validate-guest").is_none(), "{:?}", channel.lines());
}

// The refusal a boot verb answers is the *agent's own*, carried whole - the entire reason these go through
// `invoke_agent` rather than `as_root`, whose refusal withholds a guest's output by contract.
#[tokio::test]
async fn provision_linux_reports_the_verbs_own_refusal() {
    let host = provisioned_host();
    let refusal = r#"{"schemaVersion":1,"ok":false,"command":"validate-guest","error":{"code":"linux_window_manager_missing","message":"no window manager is registered on :88"}}"#;
    let channel = FakeChannel::answering("air-linux-1", move |argv| {
        Ok(if has(argv, "validate-guest") {
            failed(70, refusal)
        } else {
            Captured::default()
        })
    });
    let refusal = host
        .guest(&channel)
        .provision_linux(&ForbiddenBazel, &linux_provisioning())
        .await
        .unwrap_err();
    assert_eq!(refusal.code, "linux_window_manager_missing");
    assert!(
        refusal.message.contains("no window manager is registered on :88"),
        "{}",
        refusal.message
    );
    assert_eq!(refusal.exit, Exit::SOFTWARE);
}

// A green boot's two reports are the only account of it that survives, so both are noted - as the document, which is
// what keeps the note from going stale against the guest's report types.
#[tokio::test]
async fn provision_linux_notes_what_each_verb_reported() {
    let host = provisioned_host();
    let provisioned = r#"{"display":":88","installed":["fluxbox"],"alreadyPresent":["xvfb"],"displayProbes":3}"#;
    let validated = r#"{"display":":88","windowManager":"0x400001","windowManagerProbes":2,"sharedObjects":41}"#;
    let envelope = |verb: &str, data: &str| format!(r#"{{"schemaVersion":1,"ok":true,"command":"{verb}","data":{data}}}"#);
    let (provision_reply, validate_reply) = (envelope("provision-guest", provisioned), envelope("validate-guest", validated));
    let channel = FakeChannel::answering("air-linux-1", move |argv| {
        Ok(if has(argv, "provision-guest") {
            said(&provision_reply)
        } else if has(argv, "validate-guest") {
            said(&validate_reply)
        } else {
            Captured::default()
        })
    });
    let (reporter, notes) = prose();
    host.guest_reporting(&channel, &reporter)
        .provision_linux(&ForbiddenBazel, &linux_provisioning())
        .await
        .unwrap();
    let text = notes.text();
    for wanted in [
        format!("provision-guest reported {provisioned}"),
        format!("validate-guest reported {validated}"),
    ] {
        assert!(text.contains(&wanted), "{wanted:?} is not in\n{text}");
    }
    // The worker is on the line, because a pool-wide operation notes several boots into one log.
    assert!(text.contains("air-linux-1"), "{text}");
}

// A verb that answered something this controller cannot read still succeeded: a boot is not failed over a progress
// line.
#[tokio::test]
async fn provision_linux_survives_a_verb_that_reported_nothing_readable() {
    let host = provisioned_host();
    let channel = FakeChannel::spoke("air-linux-1", "not an envelope");
    let (reporter, notes) = prose();
    host.guest_reporting(&channel, &reporter)
        .provision_linux(&ForbiddenBazel, &linux_provisioning())
        .await
        .unwrap();
    assert!(!notes.text().contains("not an envelope"), "{}", notes.text());
}

// A `data: null` is still what the verb answered, so it is noted; only an absent `data` is nothing to note.
#[tokio::test]
async fn provision_linux_notes_a_null_report() {
    let host = provisioned_host();
    let channel = FakeChannel::answering("air-linux-1", move |argv| {
        Ok(if has(argv, "provision-guest") {
            said(r#"{"schemaVersion":1,"ok":true,"command":"provision-guest","data":null}"#)
        } else if has(argv, "validate-guest") {
            said(r#"{"schemaVersion":1,"ok":true,"command":"validate-guest"}"#)
        } else {
            Captured::default()
        })
    });
    let (reporter, notes) = prose();
    host.guest_reporting(&channel, &reporter)
        .provision_linux(&ForbiddenBazel, &linux_provisioning())
        .await
        .unwrap();
    let text = notes.text();
    assert!(text.contains("provision-guest reported null"), "{text}");
    assert!(!text.contains("validate-guest reported"), "{text}");
}

// --- worker storage --------------------------------------------------------------------------------------------

// The worker's actual disk, not the configured default for a new one: the guest script's safety gate is that `/` is
// a single internal APFS store of exactly the size it was told.
// A macOS guest runs on Tart only, and a Windows host drives only Docker.
#[cfg(unix)]
#[tokio::test]
async fn ensure_worker_storage_sends_the_workers_own_disk_size() {
    let host = Host::new(GuestOs::Macos);
    let data = host.settings.vm_data.clone();
    // Nothing retired is left behind on this worker.
    let channel = FakeChannel::answering("air-macos-1", move |argv| {
        Ok(if has(argv, "/bin/test") && !has(argv, &data) {
            failed(1, "")
        } else {
            Captured::default()
        })
    });
    host.guest(&channel).ensure_worker_storage(400).await.unwrap();
    assert_eq!(
        channel.lines()[0],
        format!(
            "/usr/bin/sudo -H /usr/local/sbin/air-init-worker-storage 400000000000 {} {}",
            host.settings.vm_data, host.settings.vm_user
        )
    );
    assert!(channel.saw("/bin/rmdir").is_none());
}

// `rmdir` and never `rm -r`: a non-empty directory means something does use it, which is a discovery to make loudly
// later rather than a tree to delete now.
// A macOS guest runs on Tart only, and a Windows host drives only Docker.
#[cfg(unix)]
#[tokio::test]
async fn ensure_worker_storage_rmdirs_the_retired_directories() {
    let host = Host::new(GuestOs::Macos);
    let channel = FakeChannel::new("air-macos-1");
    let (reporter, notes) = prose();
    host.guest_reporting(&channel, &reporter).ensure_worker_storage(120).await.unwrap();
    let removed: Vec<String> = channel
        .calls()
        .into_iter()
        .filter(|call| has(&call.argv, "/bin/rmdir"))
        .map(|call| call.argv.last().unwrap().clone())
        .collect();
    let data = &host.settings.vm_data;
    assert_eq!(
        removed,
        [
            format!("{data}/checkout"),
            format!("{data}/bazel-output"),
            format!("{data}/bazel-disk-cache"),
        ]
    );
    assert!(notes.text().contains("removed the retired checkout directory"), "{}", notes.text());
}

// A macOS guest runs on Tart only, and a Windows host drives only Docker.
#[cfg(unix)]
#[tokio::test]
async fn ensure_worker_storage_refuses_a_worker_that_never_initialized() {
    let host = Host::new(GuestOs::Macos);
    let channel = FakeChannel::answering("air-macos-1", |argv| {
        Ok(if has(argv, "/bin/test") {
            failed(1, "")
        } else {
            Captured::default()
        })
    });
    let refusal = host.guest(&channel).ensure_worker_storage(120).await.unwrap_err();
    assert_eq!(refusal.code, "worker_storage_missing");
}

// The readiness a run needs: the parity layout first, then the run roots made and handed to the worker - and on a
// Tart macOS worker the storage and the SSH identity before either.
// A macOS guest runs on Tart only, and a Windows host drives only Docker.
#[cfg(unix)]
#[tokio::test]
async fn ensure_ready_checks_the_layout_then_prepares_the_run_roots() {
    let host = Host::new(GuestOs::Linux);
    crate::guest::write_init_receipt(&host.settings, "air-linux-1").unwrap();
    let channel = FakeChannel::new("air-linux-1");
    host.guest(&channel)
        .ensure_ready(&crate::guest::testing::Peers::default())
        .await
        .unwrap();
    let settings = &host.settings;
    let roots = [settings.vm_data.as_str(), &settings.vm_runs_root].join(" ");
    let lines = channel.lines();
    // A Linux worker is not asked about SSH keys: nothing here reaches it over SSH.
    assert!(!lines.iter().any(|line| line.contains("air-ensure-ssh-host-keys")), "{lines:?}");
    assert_eq!(
        &lines[lines.len() - 2..],
        [
            format!("/usr/bin/sudo -H /bin/mkdir -p {roots}"),
            format!("/usr/bin/sudo -H {} {} {roots}", settings.guest.chown, settings.vm_user),
        ]
    );

    let macos = Host::new(GuestOs::Macos);
    let bare = FakeChannel::answering("air-macos-1", |argv| {
        Ok(if has(argv, "/bin/test") {
            failed(1, "")
        } else {
            Captured::default()
        })
    });
    let refusal = macos
        .guest(&bare)
        .ensure_ready(&crate::guest::testing::Peers::default())
        .await
        .unwrap_err();
    assert_eq!(refusal.code, "worker_storage_missing");
    assert_eq!(bare.calls().len(), 1, "{:?}", bare.lines());
}
