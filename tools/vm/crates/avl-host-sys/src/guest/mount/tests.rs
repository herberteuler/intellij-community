use avl_base::GuestOs;
use pretty_assertions::assert_eq;

use super::*;
use crate::guest::testing::{FIXTURE_GUESTS, FakeChannel, Host, failed};

// Every binary absolute, because `sudo -H`'s secure_path on a macOS guest has no /sbin: a bare `mount` was "command
// not found", the sweep unmounted nothing while exiting 0, and the mount then failed "Resource busy".
#[test]
fn remount_script_spells_every_binary_absolutely() {
    for &guest_os in FIXTURE_GUESTS {
        let host = Host::new(guest_os);
        let guest = host.settings.guest;
        let script = remount_script(&host.settings);
        for required in [
            format!("{} | /usr/bin/awk", guest.mount_binary),
            format!(r#"{0} "$mounted" || {0} -f "$mounted""#, guest.umount_binary),
            guest.mount_shares.to_owned(),
            r#"/bin/mkdir -p "$MOUNT""#.to_owned(),
            r#"/bin/rm -f "$LIST""#.to_owned(),
        ] {
            assert!(script.contains(&required), "{guest_os:?} script is missing {required:?}:\n{script}");
        }
        // Both substitutions: macOS prints `tag on /Volumes/X (AppleVirtIOFS, …)` and Linux `tag on /mnt/X type
        // virtiofs (rw,…)`. Stripping only the parenthesis left ` type virtiofs` attached, and `umount` answered "no
        // mount point specified" for the whole string.
        assert!(script.contains(r#"sub(/ type .*/, "", $2); sub(/ \(.*/, "", $2)"#), "{script}");
        assert!(script.contains(guest.mounted_filesystem), "{script}");
    }
}

// The script really does reduce both guests' `mount` lines to the bare mount point: run through a real `awk` over
// one line of each spelling.
// The sweep runs through the host's `/bin/sh` and `awk`.
#[cfg(unix)]
#[test]
fn the_remount_sweep_reduces_both_mount_spellings_to_the_mount_point() {
    for guest_os in [GuestOs::Macos, GuestOs::Linux] {
        let host = Host::new(guest_os);
        let guest = host.settings.guest;
        let script = remount_script(&host.settings);
        let sweep = script.lines().find(|line| line.contains("/usr/bin/awk")).unwrap();
        let awk = &sweep[sweep.find("/usr/bin/awk").unwrap()..sweep.find(" > ").unwrap()];
        // Both spellings in one listing, and the filter widened to both: what is under test is the reduction.
        let listing = "tag on /Volumes/X Y (AppleVirtIOFS, local)\ntag on /mnt/Z type virtiofs (rw,relatime)\n\
                       /dev/disk1 on / (apfs)\n";
        let awk = awk.replace(&format!("/{}/", guest.mounted_filesystem), "/^tag /");
        let shell = format!("printf '%s' '{listing}' | {awk}");
        let output = std::process::Command::new("/bin/sh").args(["-c", &shell]).output().unwrap();
        assert_eq!(
            String::from_utf8_lossy(&output.stdout),
            "/Volumes/X Y\n/mnt/Z\n",
            "{guest_os:?}: {shell}"
        );
    }
}

#[tokio::test]
async fn remount_shares_runs_the_script_it_just_wrote() {
    let host = Host::new(GuestOs::Linux);
    let channel = FakeChannel::new("air-linux-1");
    host.guest(&channel).remount_shares().await.unwrap();
    let script = format!("{}/state/remount-shares.sh", host.settings.vm_data);
    let write = channel.saw("/usr/bin/tee").expect("the script was written");
    assert!(write.argv.contains(&script), "{:?}", channel.lines());
    // The bytes travel on stdin, which is the only channel a guest exec has for content.
    assert_eq!(write.options.stdin.as_deref(), Some(remount_script(&host.settings).as_bytes()));
    assert_eq!(channel.lines().last().unwrap(), &format!("/usr/bin/sudo -H /bin/sh {script}"));
}

#[tokio::test]
async fn require_share_mounted_probes_inside_the_repository_share() {
    let host = Host::new(GuestOs::Linux);
    let channel = FakeChannel::new("air-linux-1");
    let name = &host.settings.repo_share_name;
    let mount = host.guest(&channel).require_share_mounted(name, Some(".git")).await.unwrap();
    assert_eq!(mount, format!("{}/{name}", host.settings.guest.share_mount));
    assert_eq!(channel.lines(), [format!("/bin/test -e {mount}/.git")]);
}

// No probe path is "probe the mount point itself", which is all the Bazel share can be asked: it has no entry that
// is guaranteed to be there.
#[tokio::test]
async fn require_share_mounted_probes_the_mount_point_itself() {
    let host = Host::new(GuestOs::Linux);
    let channel = FakeChannel::new("air-linux-1");
    let mount = host
        .guest(&channel)
        .require_share_mounted(&host.settings.bazel_share_name, None)
        .await
        .unwrap();
    assert_eq!(channel.lines(), [format!("/bin/test -e {mount}")]);
}

#[tokio::test]
async fn require_share_mounted_refuses_an_unmounted_share() {
    let host = Host::new(GuestOs::Linux);
    let channel = FakeChannel::answering("air-linux-1", |_| Ok(failed(1, "")));
    let refusal = host
        .guest(&channel)
        .require_share_mounted(&host.settings.repo_share_name, None)
        .await
        .unwrap_err();
    assert_eq!(refusal.code, "guest_share_unmounted");
}
