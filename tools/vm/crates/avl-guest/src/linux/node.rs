//! `stage-node`: giving a Linux worker the Node its lanes run on.
//!
//! # Why a worker cannot get its Node from a package
//!
//! The `nodejs` of Ubuntu 24.04, the Tart base, is Node 18, and the image has no `npm` at all (measured on
//! `air-linux-2`). The `nodejs` of Ubuntu 26.04, the Docker base, is Node 22 (measured with `apt-cache policy` on
//! 2026-09-29). Neither major is `NODE_MAJOR`. The agent CLIs a flow lane drives are Node programs, and a current
//! one refuses an older major. A Node archive carries `npm` with it, which is why removing the package added none.
//!
//! # Why nothing is downloaded and nothing is pushed
//!
//! The archive is already on the host, checksum-pinned in Bazel, and the guest reads the host's Bazel outputs at
//! identical absolute paths through the read-only share. So the controller resolves one host path and this verb
//! extracts it in place, the way the JBR is staged: one archive, no donor, no hardlinks.
//!
//! # Idempotence, and why the receipt records a size
//!
//! A warm worker runs this verb again, so a receipt under the version's own directory short-circuits it: one read
//! of a small JSON file and three stats, and no `tar`. The receipt is written into the staging root *before* the
//! rename, so a directory that carries one is a directory the rename published whole.
//!
//! A `pool stop` can still lose the tail of the tree: measured on `air-linux-2` on 2026-08-27, a stop after a
//! first boot left `bin/node` at 54,525,952 bytes of 122,077,656, the next boot reused it because it was still a
//! file, and every `node` answered `Segmentation fault`. So the receipt records `bin/node`'s size, and a shorter
//! binary is restaged. A flush inside the guest does not prevent this and was measured not to: what is lost is the
//! *host* process's writes to the disk image, and `tart stop` ends that process. Detection is the whole repair.

use std::fs;
use std::path::{Path, PathBuf};

use serde::{Deserialize, Serialize};

use crate::cli::StageNodeArgs;
use crate::clock::now_stamp;
use crate::reply::AgentRefusal;
use crate::reply::AgentRefusalExt;
use crate::stage::{self, StagingTree, TAR_BINARY, is_file};
use crate::step::{Runner, Step};

#[cfg(test)]
mod tests;

/// The receipt one staged Node version leaves behind.
const NODE_STATE_FILE: &str = "node.json";

/// The receipt, and the only thing that qualifies a directory as already staged. The version is recorded as well
/// as being the directory's name, so a tree moved or renamed by hand is restaged rather than trusted.
#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
struct NodeStageState {
    schema_version: u32,
    version: String,
    staged_at: String,
    /// What `bin/node` measured when the receipt was written. A receipt without it reads 0, which matches no real
    /// binary, so such a worker restages once: the safe direction.
    node_size: u64,
}

/// What a passing `stage-node` answers. `reused` is the field worth reading: it is true on every boot after the
/// first, and a warm worker that answers false is losing its node root between boots.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub(crate) struct NodeStageReport {
    pub version: String,
    pub root: String,
    pub node: String,
    pub npm: String,
    pub reused: bool,
}

fn node_binary(root: &Path) -> PathBuf {
    root.join("bin").join("node")
}

fn npm_binary(root: &Path) -> PathBuf {
    root.join("bin").join("npm")
}

fn display(path: &Path) -> String {
    path.to_string_lossy().into_owned()
}

/// Stages one Node archive. The runner is the seam: what is worth pinning is that `tar` is asked for exactly this
/// archive, into a staging root, and that a warm worker does not ask at all.
pub(crate) struct NodeStager<R> {
    node_root: PathBuf,
    archive: PathBuf,
    /// Pattern-checked by the argv parser, so it is a single path component here.
    version: String,
    runner: R,
}

impl<R: Runner> NodeStager<R> {
    pub(crate) fn new(args: &StageNodeArgs, runner: R) -> Self {
        Self {
            node_root: args.node_root.clone(),
            archive: args.archive.clone(),
            version: args.version.clone(),
            runner,
        }
    }

    fn version_root(&self) -> PathBuf {
        self.node_root.join(&self.version)
    }

    /// Extracts the archive, unless the version this worker was asked for is already complete.
    pub(crate) fn stage(&mut self) -> Result<NodeStageReport, AgentRefusal> {
        let final_root = self.version_root();
        if let Some(complete) = read_complete_install(&final_root, &self.version) {
            return Ok(complete);
        }
        // Refused rather than passed to `tar`: a share that did not mount answers "no such file" for every path
        // under it, and a message naming the archive says which share.
        if !is_file(&self.archive) {
            return Err(AgentRefusal::refused(
                "linux_node_archive_missing",
                format!(
                    "the Node archive is not a file: {}; the guest reads it through the read-only Bazel share at the \
                     host's own path, so a missing one is a share that did not mount or an output base that moved",
                    self.archive.display()
                ),
            ));
        }
        fs::create_dir_all(&self.node_root).map_err(|error| {
            AgentRefusal::refused(
                "linux_node_root_unwritable",
                format!("the node root {} could not be created: {error}", self.node_root.display()),
            )
        })?;
        // A stage killed before its rename leaves its staging tree behind, and only this verb writes under the root.
        stage::remove_abandoned_staging_trees(&self.node_root);
        // Beside the destination and renamed onto it, so a failed extraction never leaves a half-tree the next
        // boot's receipt check would have to tell from a whole one.
        let staging = StagingTree::new(&self.node_root, &self.version);
        self.extract_into(staging, &final_root)
    }

    fn extract_into(&mut self, staging: StagingTree, final_root: &Path) -> Result<NodeStageReport, AgentRefusal> {
        let temporary_root = staging.path();
        fs::create_dir_all(temporary_root).map_err(|error| {
            AgentRefusal::refused(
                "linux_node_root_unwritable",
                format!("the staging root {} could not be created: {error}", temporary_root.display()),
            )
        })?;
        // `--strip-components=1` drops the archive's own prefix, such as `node-v24.19.0-linux-arm64/`, so
        // `bin/node` lands directly under the version's root. Silent, for `stage`'s reason: `tar`'s bytes would be
        // guest output travelling into an envelope. The archive is checksum-pinned, so a corrupt one reproduces on
        // demand.
        let extract = Step::new([
            TAR_BINARY.to_owned(),
            "xzf".to_owned(),
            display(&self.archive),
            "-C".to_owned(),
            display(temporary_root),
            "--strip-components=1".to_owned(),
        ])
        .silent();
        self.runner.run(&extract).map_err(|error| {
            AgentRefusal::refused(
                "linux_node_extract_failed",
                format!("the Node archive {} could not be extracted: {error}", self.archive.display()),
            )
        })?;
        // Checked after extraction rather than trusted: the archive's *name* is the only thing that said which
        // platform's Node it holds, and a macOS tarball passes `tar` and fails at the first exec inside a lane.
        for (required, shown) in [(node_binary(temporary_root), "bin/node"), (npm_binary(temporary_root), "bin/npm")] {
            if !is_file(&required) {
                return Err(AgentRefusal::refused(
                    "linux_node_incomplete",
                    format!(
                        "the Node archive {} holds no {shown}; a Node archive carries npm beside node, which is why \
                         the guest package list names neither",
                        self.archive.display()
                    ),
                ));
            }
        }
        // Measured and recorded before the rename publishes the tree, so the receipt describes the file beside it.
        let node_size = fs::metadata(node_binary(temporary_root))
            .map_err(|error| {
                AgentRefusal::refused(
                    "linux_node_extract_failed",
                    format!("the Node extracted from {} could not be measured: {error}", self.archive.display()),
                )
            })?
            .len();
        let receipt = NodeStageState {
            schema_version: avl_wire::stage::SCHEMA_VERSION,
            version: self.version.clone(),
            staged_at: now_stamp(),
            node_size,
        };
        stage::write_json_file(&temporary_root.join(NODE_STATE_FILE), &receipt).map_err(|error| {
            AgentRefusal::refused(
                "linux_node_root_unwritable",
                format!("the staged Node's receipt could not be written: {error}"),
            )
        })?;
        stage::remove_tree(final_root);
        staging.publish(final_root).map_err(|error| {
            AgentRefusal::refused(
                "linux_node_root_unwritable",
                format!("the staged Node could not be published at {}: {error}", final_root.display()),
            )
        })?;
        Ok(NodeStageReport {
            version: self.version.clone(),
            root: display(final_root),
            node: display(&node_binary(final_root)),
            npm: display(&npm_binary(final_root)),
            reused: false,
        })
    }
}

/// The staged version a warm boot may keep, or `None`, which is always safe: it means the extraction happens.
fn read_complete_install(root: &Path, version: &str) -> Option<NodeStageReport> {
    let state: NodeStageState = serde_json::from_slice(&fs::read(root.join(NODE_STATE_FILE)).ok()?).ok()?;
    if state.schema_version != avl_wire::stage::SCHEMA_VERSION || state.version != version {
        return None;
    }
    let (node, npm) = (node_binary(root), npm_binary(root));
    if !is_file(&node) || !is_file(&npm) {
        return None;
    }
    // The size is what a pair of `is_file` calls cannot say: a worker that lost the tail of this binary still has a
    // file at that path. A receipt recording no size proves nothing.
    if state.node_size == 0 || fs::metadata(&node).ok()?.len() != state.node_size {
        return None;
    }
    Some(NodeStageReport {
        version: version.to_owned(),
        root: display(root),
        node: display(&node),
        npm: display(&npm),
        reused: true,
    })
}
