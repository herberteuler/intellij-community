//! Giving a Linux worker the Node its lanes run on.
//!
//! # Why the controller stages one at all
//!
//! The `nodejs` of Ubuntu 24.04, the Tart base, is Node 18, and that image carries no `npm`. The `nodejs` of Ubuntu
//! 26.04, the Docker base, is Node 22. Neither major is `NODE_MAJOR`, and the agent CLIs a flow lane drives refuse
//! an older major. So a worker provisioned from the package list had a Node no lane could use. The `check-node`
//! verb refuses such a boot by name.
//!
//! # Why nothing is downloaded and nothing is pushed
//!
//! The archive is a Bazel output the host already holds, and the guest reads the host's Bazel outputs through the
//! read-only share the parity layout builds, at their [`GuestPaths`] paths. So this half resolves one host path,
//! maps it, and the guest verb extracts it in place.

use std::path::PathBuf;
use std::time::Duration;

use avl_base::config::{GUEST_NODE_VERSION, pins};
use avl_base::{Config, Exit, GuestArch, GuestOs, Refusal};
use avl_wire::verb::AgentVerb;

use super::Guest;
use super::agent::{BazelHost, ResolvedPaths};
use super::external::external_file;
use super::supervisor::AgentAccount;
use crate::ctx::Ctx;
use crate::paths::GuestPaths;

/// The timeout of `check-node`, which runs the configured Node once for its version.
const CHECK_NODE_TIMEOUT: Duration = Duration::from_mins(2);

/// The timeout of `stage-node`. It extracts the 57 MB Node archive, which it reads through the Bazel share.
const STAGE_NODE_TIMEOUT: Duration = Duration::from_mins(10);

#[cfg(test)]
mod tests;

/// The Bazel repository the pinned Node archives live in. AIR's own managed-runtime matrix declares them, and the
/// lane reuses them rather than pinning another copy: the archive a worker runs agents on is already the archive
/// the product's runtime manager installs.
const NODE_ARCHIVE_REPOSITORY: &str = "@air_acp_runtime_test_deps";

static RESOLVED_NODE_ARCHIVES: ResolvedPaths = ResolvedPaths::new();

/// The target that holds this guest's Node archive, or `None` for a guest that stages none.
///
/// Only Linux, and that is the design rather than a gap: a macOS worker's Node comes from the sealed golden image,
/// and staging a second one into it would be two answers to which Node that worker runs. The name is built from
/// [`GUEST_NODE_VERSION`], so the controller's version and the label cannot disagree silently: a version this
/// repository does not pin is a label Bazel refuses by name.
///
/// Public for the lane, which puts it in the host build's target set: resolution must not build, so something else
/// has to have fetched the repository and materialized the file.
pub fn node_archive_label(guest_os: GuestOs, guest_arch: GuestArch) -> Option<String> {
    // Node's own archive spelling of the architecture, which the runtime matrix pins under.
    let arch = match guest_arch {
        GuestArch::Arm64 => "arm64",
        GuestArch::X86_64 => "x64",
    };
    match guest_os {
        GuestOs::Linux => Some(format!(
            "{NODE_ARCHIVE_REPOSITORY}//:node-v{GUEST_NODE_VERSION}-linux-{arch}.tar.gz"
        )),
        GuestOs::Macos => None,
    }
}

impl Guest<'_> {
    /// Gives a Linux worker the Node its lanes run on, and then makes that Node prove itself: `stage-node`
    /// extracts the archive, and `check-node` runs `node --version` by the path the controller will exec.
    ///
    /// **After the parity layout is up.** The archive is read through the read-only Bazel share, which the parity
    /// step mounts after [`Guest::provision_linux`] returns; staging inside that function refused every boot of
    /// every Linux worker, before the mount (measured on `air-linux-2` on 2026-08-27, twice).
    ///
    /// **The check is not skipped on a warm worker.** A `pool stop` once lost the tail of the staged `bin/node`,
    /// the receipt reused the tree because a truncated file is still a file, and only this check saw the guest's
    /// `Segmentation fault`.
    ///
    /// Its prerequisite is [`Guest::install_agent`]: both verbs are verbs *of that binary*.
    pub async fn ensure_guest_node(&self, bazel: &dyn BazelHost) -> Result<(), Refusal> {
        self.stage_guest_node(bazel).await?;
        // The configured Node and not the staged one, so an operator's `AIR_VM_NODE` is checked too: that is the one
        // case nothing is staged for, and a check of the staged path would prove nothing about what a run execs.
        let major = pins::guest_node_major().to_string();
        self.invoke_boot_verb(
            AgentAccount::Worker,
            AgentVerb::CheckNode,
            &[self.settings.vm_node.clone(), major],
            CHECK_NODE_TIMEOUT,
        )
        .await
    }

    /// Stages the Node the controller is about to name, a no-op in the guest when it is already there.
    ///
    /// As the worker user and not as root: the account owns the data directory, so the staged tree is the worker's
    /// own - which is what lets an `npm install -g` by that account write into the same `bin` directory the daemon's
    /// PATH already puts first.
    ///
    /// Skipped when the operator overrode the Node: `AIR_VM_NODE` means "run the Node I supplied", and extracting
    /// 57 MB into a directory no run would then read is a first boot's seconds spent on nothing.
    pub(crate) async fn stage_guest_node(&self, bazel: &dyn BazelHost) -> Result<(), Refusal> {
        let settings = self.settings;
        if settings.vm_node != settings.staged_node_binary() {
            self.reporter.note(
                format!(
                    "not staging a Node into {}: this controller runs {}",
                    self.worker(),
                    settings.vm_node
                ),
                Some(&self.scope()),
            );
            return Ok(());
        }
        let archive = resolve_node_archive(self.ctx, settings, bazel).await?;
        // The guest reads the archive through the Bazel share, so it gets the guest path of the host file.
        let archive = GuestPaths::of(settings)?.to_guest(&archive)?;
        self.invoke_boot_verb(
            AgentAccount::Worker,
            AgentVerb::StageNode,
            &[settings.vm_node_root.clone(), archive, GUEST_NODE_VERSION.to_owned()],
            STAGE_NODE_TIMEOUT,
        )
        .await
    }
}

/// Where on the host this guest's Node archive is, or a refusal naming the build to run.
///
/// Asked of Bazel through [`external_file`] unless `AIR_VM_NODE_ARCHIVE` named one. The archive is a *downloaded
/// external* file, so it is rooted on the output base, where the fetch always put it: a boot that builds nothing
/// finds no execution-root symlink.
///
/// Resolution never builds, for the agent's reason: this runs under the worker's lease-operation lock. The lane
/// build carries the label instead, so `run`, `shard`, `flake` and `daemon start` materialize it before any worker
/// is touched; what is left over is a checkout where nothing ever fetched it, and the refusal names the fix.
pub(crate) async fn resolve_node_archive(ctx: &Ctx, settings: &Config, bazel: &dyn BazelHost) -> Result<PathBuf, Refusal> {
    let is_file = |path: &PathBuf| std::fs::metadata(path).is_ok_and(|metadata| metadata.is_file());
    // The named archive, answered by a stat: a boot that names one asks Bazel nothing.
    if let Some(archive) = &settings.vm_node_archive {
        if is_file(archive) {
            return Ok(archive.clone());
        }
        return Err(Refusal::new(
            "guest_node_missing",
            Exit::FAILURE,
            format!("the Node archive AIR_VM_NODE_ARCHIVE names is not a file: {}", archive.display()),
        ));
    }
    let Some(label) = node_archive_label(settings.guest_os, settings.guest_arch) else {
        return Err(Refusal::new(
            "unsupported_guest_os",
            Exit::USAGE,
            format!("there is no Node archive to stage into a {:?} guest", settings.guest_os.as_str()),
        ));
    };
    let repo = settings.host_repo()?;
    if let Some(known) = RESOLVED_NODE_ARCHIVES.get(repo, &label) {
        return Ok(known);
    }
    let archive = external_file(ctx, bazel, &label).await?;
    if !is_file(&archive) {
        return Err(Refusal::new(
            "guest_node_missing",
            Exit::SOFTWARE,
            format!(
                "the Node archive for a {} guest is not fetched: {} does not exist. Fetch it with `./bazel.cmd \
                 build {label}` in {}.",
                settings.guest_os.as_str(),
                archive.display(),
                repo.display()
            ),
        ));
    }
    RESOLVED_NODE_ARCHIVES.remember(repo, &label, &archive);
    Ok(archive)
}
