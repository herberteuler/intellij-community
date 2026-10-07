//! `check-node`: proving the Node the controller will exec runs, and is the major the lane pins.
//!
//! # Why this is not a step of `validate-guest`
//!
//! It was one, and that placement stopped every boot of a Linux worker. `validate-guest` runs immediately after
//! `provision-guest`, which is *before* the controller mounts the read-only Bazel share. `stage-node` reads the
//! Node archive through that share, so it cannot run before the mount either - and a self-check that read the
//! staged Node ahead of the staging refused every boot by name, before the step that would have made the archive
//! readable. Nothing needs the guest's Node until the daemon starts, so it is its own verb, run by the controller
//! directly after `stage-node`.
//!
//! # What it refuses
//!
//! `linux_node_not_runnable` and `linux_node_major_mismatch`. Neither is a warning: a worker whose Node is the
//! wrong major is refused here, at a point where a correct one exists, which is the whole difference this verb
//! makes.

use serde::Serialize;

use crate::cli::CheckNodeArgs;
use crate::reply::AgentRefusalExt;
use crate::reply::{AgentRefusal, quoted};
use crate::step::{Runner, Step};

#[cfg(test)]
mod tests;

/// What a passing `check-node` answers: which Node the run supervisor will get, with its version as the guest
/// spelled it, so a boot's log pins *which* Node a worker runs rather than only that one exists.
#[derive(Clone, Debug, PartialEq, Eq, Serialize)]
pub(crate) struct NodeCheckReport {
    pub node: String,
    pub version: String,
}

/// The guest as this verb reads it: two values it was told, and one seam.
pub(crate) struct NodeChecker<R> {
    /// The Node binary *by the path the controller will exec*, not a name to resolve on PATH. That distinction is
    /// the whole check: the run supervisor's children are Node programs, and a guest where `node` exists somewhere
    /// else fails as a bare `exit 127` from the only command that mattered.
    node: String,
    /// The major the controller pins (`config.GuestNodeMajor`). Told rather than held, so this guest is not the
    /// second place the pin could be wrong.
    major: u32,
    pub runner: R,
}

impl<R: Runner> NodeChecker<R> {
    pub(crate) fn new(args: &CheckNodeArgs, runner: R) -> Self {
        Self {
            node: args.node_binary.to_string_lossy().into_owned(),
            major: args.node_major,
            runner,
        }
    }

    /// Proves the configured Node runs *and is the pinned major*.
    ///
    /// `--version` and not an existence check, because a Node whose binary is on disk and whose interpreter cannot
    /// start is the same 127. The major is the half a bare 127 cannot describe: a Node that runs and is too old
    /// fails *inside* an agent CLI, in the CLI's own words, at whatever point in a run first reached it.
    pub(crate) fn check(&mut self) -> Result<NodeCheckReport, AgentRefusal> {
        let answered = self
            .runner
            .run(&Step::new([self.node.as_str(), "--version"]).capture())
            .map_err(|error| {
                AgentRefusal::refused(
                    "linux_node_not_runnable",
                    format!(
                        "{} does not run, and the run supervisor's children are Node programs, so every one of \
                         them would fail as a bare exit 127: {error:#}",
                        self.node
                    ),
                )
            })?;
        let found = answered.trim();
        if !is_node_major(found, self.major) {
            return Err(AgentRefusal::refused(
                "linux_node_major_mismatch",
                format!(
                    "{} answers {} and this lane pins Node {}: the agent CLIs a flow lane drives refuse an older \
                     major, so a run would fail in a CLI's own words instead of here; the guest's Node is staged \
                     by the stage-node verb and the pin is config.GuestNodeMajor",
                    self.node,
                    quoted(found),
                    self.major
                ),
            ));
        }
        Ok(NodeCheckReport {
            node: self.node.clone(),
            version: found.to_owned(),
        })
    }
}

/// Whether `version` (`v24.10.0`) is of this major. The trailing dot is the whole check: without it a `v240`
/// satisfies a pin of 24. The image pair reads its Node the same way.
pub(crate) fn is_node_major(version: &str, major: u32) -> bool {
    version.starts_with(&format!("v{major}."))
}
