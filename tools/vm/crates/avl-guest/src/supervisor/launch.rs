//! The launch of the detached supervisor: one spawn point, and the unit a systemd guest needs it to run in.
//!
//! `start` spawns the `supervise` verb and then leaves. The supervisor spawns the run's child, which is the daemon
//! JVM, and the IDE comes up under that. So this one spawn decides what the whole tree belongs to.
//!
//! # What the systemd launch survives
//!
//! The controller reaches a Tart Linux worker through `tart-guest-agent`, which systemd runs as
//! `tart-guest-agent.service`, and every verb of this agent is a child of that unit. The unit keeps systemd's
//! default `KillMode=control-group`: an agent exit makes systemd send SIGTERM to every process still in the unit's
//! cgroup. A new session does not leave that cgroup, so `setsid` cannot help. Measured on 2026-08-26: under load
//! the agent exited twice with "context canceled", and each exit killed a daemon and the IDE it held, the first of
//! them minutes past its launch (ADR 0110).
//!
//! # A transient service, and not a scope
//!
//! A `systemd-run --scope` was measured first, on air-linux-3, and it does not survive the kill. A scope needs the
//! system manager, so the guest asks for one through `sudo`, and sudo stays alive as the parent of what it runs,
//! in the agent's cgroup. A transient service has no such parent: PID 1 starts the unit, `systemd-run` exits as
//! soon as the manager has it, and the supervisor keeps only the unit. In the same trial that service lived.
//!
//! # What a container launch survives
//!
//! A Docker worker runs no systemd. Its pid 1 is `docker-init`, which starts `air-display`, and that starts Xvfb and
//! fluxbox. `/run/systemd/system` is absent, and `systemd-run` answers "System has not been booted with systemd as
//! init system". Measured on air-docker-1 on 2026-09-29, where every lane run refused `supervise_unit_refused`.
//! The exec channel there is `docker exec`, and no unit holds what it starts. A child that leaves the exec's
//! session is reparented to `docker-init` and outlives the end of the exec. So a container takes the released,
//! session-detached spawn of the macOS guest. [`LaunchHost::current`] picks the launch from the init that runs,
//! not from the OS. On air-docker-1 on 2026-09-29 that spawn survived a whole lane iteration: an
//! `ide daemon restart` run passed 8 of 8 tests, which ran for 234 s.
//!
//! # What the unit changes
//!
//! The unit owns the run's whole process tree, so `KillMode=control-group` now reaches the daemon when the
//! supervisor itself dies. The orphan cancel keeps its job on the macOS guest and in a container. Under a unit it
//! finds a child that is already gone, which is one state fewer rather than a state lost.

use std::ffi::{OsStr, OsString};
use std::fs::File;
use std::io;
use std::os::unix::ffi::OsStrExt;
use std::os::unix::process::CommandExt;
use std::path::Path;
use std::process::{Command, Stdio};

use avl_wire::supervisor::Command as Verb;

use crate::reply::AgentRefusalExt;
use crate::reply::{AgentRefusal, quoted};

/// The *system* manager, reached through passwordless sudo, because there is no user manager for this account
/// (`XDG_RUNTIME_DIR is invalid or not set`). The public base image gives the worker account that sudo.
const SUDO_BINARY: &str = "/usr/bin/sudo";
const SYSTEMD_RUN_BINARY: &str = "/usr/bin/systemd-run";

/// Names the transient service one run's supervisor lives in. `air-` marks a unit this repository created, and
/// the run id completes it; every character a run id may have is one a unit name may have.
const SUPERVISE_UNIT_PREFIX: &str = "air-supervise-";

/// The exec channel's own variables. Nothing under the channel gets them: a supervised child has no use for the
/// worker's capability, and a `--setenv` becomes a unit property `systemctl show` prints to any account.
pub(crate) const CHANNEL_VARIABLES: [&str; 3] = ["TART_VM_TOKEN", "TART_VM_WORKER", "PARALLELS_VM_TOKEN"];

/// What the launch of the supervisor runs under: the guest's own OS and init, never the controller's. A runtime
/// value, so every launch is built and tested on whatever host runs the tests.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum LaunchHost {
    /// A Linux VM whose init is systemd. The supervisor gets a transient service of its own.
    LinuxSystemd,
    /// A Linux guest with no systemd, which is a Docker container. Its init is `docker-init`. The supervisor is the
    /// released spawn.
    LinuxNoSystemd,
    /// A macOS guest. The supervisor is the released spawn.
    Macos,
}

impl LaunchHost {
    /// The word the supervisor log names this host with.
    pub(crate) const fn as_str(self) -> &'static str {
        match self {
            Self::LinuxSystemd => "linux-systemd",
            Self::LinuxNoSystemd => "linux-no-systemd",
            Self::Macos => "macos",
        }
    }

    pub(crate) fn current() -> Self {
        if cfg!(target_os = "linux") {
            Self::linux(Path::new("/"))
        } else {
            Self::Macos
        }
    }

    /// The launch host of a Linux guest whose file system starts at `root`. The one place that detects the init.
    ///
    /// systemd is the init exactly when `/run/systemd/system` is a directory. That is the test `sd_booted(3)`
    /// makes, and systemd creates the directory early in its own boot. A Docker container has no such directory:
    /// its pid 1 is `docker-init`, and a `systemd-run` there refuses. The OS alone cannot tell the two apart, so the
    /// detection reads the file system at run time.
    pub(crate) fn linux(root: &Path) -> Self {
        if root.join("run/systemd/system").is_dir() {
            Self::LinuxSystemd
        } else {
            Self::LinuxNoSystemd
        }
    }
}

/// How one guest starts the detached supervisor.
#[derive(Clone, Debug, PartialEq, Eq)]
pub(crate) struct SuperviseLaunch {
    pub argv: Vec<OsString>,
    /// The transient service the supervisor runs in; `None` on a guest with no systemd.
    ///
    /// It also says what `argv` is. With a unit, `argv` is a client that asks the manager for the supervisor and
    /// exits, so its status is a verdict to read. Without one, `argv` is the supervisor itself, which outlives
    /// this process and is released rather than waited for.
    pub unit: Option<String>,
}

/// Composes the launch of one run's supervisor. Pure: every input is an argument.
pub(crate) fn supervise_launch_for(
    host: LaunchHost,
    self_exe: &Path,
    root: &Path,
    run_id: &str,
    log_path: &Path,
    uid: u32,
    gid: u32,
    environment: &[(OsString, OsString)],
) -> Result<SuperviseLaunch, AgentRefusal> {
    let supervise: Vec<OsString> = vec![
        self_exe.into(),
        Verb::Supervise.as_str().into(),
        "--root".into(),
        root.into(),
        "--run".into(),
        run_id.into(),
    ];
    if host != LaunchHost::LinuxSystemd {
        return Ok(SuperviseLaunch {
            argv: supervise,
            unit: None,
        });
    }
    // systemd substitutes `$NAME` and `${NAME}` in a unit's own argv, so a path holding a dollar sign would reach
    // the supervisor as something else or as nothing. A named refusal keeps it from being a silent substitution.
    for element in [self_exe, root, log_path] {
        if element.as_os_str().as_bytes().contains(&b'$') {
            return Err(AgentRefusal::failure(
                "supervise_unit_path_unsupported",
                format!(
                    "{} cannot be a systemd unit argument: systemd substitutes a dollar sign",
                    quoted(&element.to_string_lossy())
                ),
            ));
        }
    }
    let unit = format!("{SUPERVISE_UNIT_PREFIX}{run_id}.service");
    let mut argv: Vec<OsString> = [
        SUDO_BINARY,
        "-n",
        SYSTEMD_RUN_BINARY,
        // `--collect` removes the unit once it deactivates; `--quiet` keeps the client's own "Running as unit"
        // line out of the supervisor log.
        "--collect",
        "--quiet",
    ]
    .into_iter()
    .map(OsString::from)
    .collect();
    argv.push(format!("--unit={unit}").into());
    // The account this process already runs as, by number, so the supervisor keeps the credentials it has.
    argv.push(format!("--uid={uid}").into());
    argv.push(format!("--gid={gid}").into());
    // PID 1 starts the unit, so it inherits no descriptor from this process: the log is a property. The open file
    // `start` still hands the launcher creates the log under the worker account, before root appends to it.
    for stream in ["StandardOutput", "StandardError"] {
        argv.push(concat_os(&[
            OsStr::new(&format!("--property={stream}=append:")),
            log_path.as_os_str(),
        ]));
    }
    for (name, value) in unit_environment(environment) {
        argv.push(concat_os(&[OsStr::new("--setenv="), &name, OsStr::new("="), &value]));
    }
    argv.extend(supervise);
    Ok(SuperviseLaunch { argv, unit: Some(unit) })
}

fn concat_os(parts: &[&OsStr]) -> OsString {
    let mut joined = OsString::new();
    for part in parts {
        joined.push(part);
    }
    joined
}

/// The environment the transient service carries, sorted so one environment composes one argv.
///
/// Stated in full, because PID 1 hands a unit nothing. The supervisor copies it into the run's child, which is how
/// DISPLAY reaches the IDE (the channel's `setsid --wait /usr/bin/env DISPLAY=…`), so this list is load bearing.
fn unit_environment(environment: &[(OsString, OsString)]) -> Vec<(OsString, OsString)> {
    let mut kept: Vec<(OsString, OsString)> = environment
        .iter()
        .filter(|(name, _)| !name.is_empty() && !CHANNEL_VARIABLES.iter().any(|channel| name == channel))
        .cloned()
        .collect();
    kept.sort();
    kept
}

/// Makes `command` the leader of a new session, and so of a new process group whose id is its pid. A session
/// rather than just a group: it also detaches from the exec channel's terminal. The group is what lets a cancel
/// reach a child's children, and its id is half of the identity recorded for the run.
pub(crate) fn detach_into_own_session(command: &mut Command) {
    // SAFETY: the closure runs between fork and exec, and calls only setsid(2), which is async-signal-safe and
    // touches no memory of the parent.
    unsafe {
        command.pre_exec(|| nix::unistd::setsid().map(drop).map_err(io::Error::from));
    }
}

impl SuperviseLaunch {
    /// Spawns the launch with `log` as both of its streams, and answers what became of it.
    ///
    /// A unit is waited for, and that wait is the loud half: the client leaves as soon as the manager has the
    /// unit, so the wait costs one round trip and answers what `systemd-run` decided. Without it an absent or
    /// refusing launcher would surface as a 15 s `start_timeout` with no reason. There is no fallback to the
    /// released spawn, deliberately: on a systemd guest that spawn is the one the exec channel reaps.
    ///
    /// The refusal states this process's own facts and never the launcher's bytes: the unit, the run, how the
    /// launcher ended, and the log that holds its whole account.
    pub(super) fn start(&self, log: &File, log_path: &Path, run_id: &str) -> Result<(), AgentRefusal> {
        let Some((program, arguments)) = self.argv.split_first() else {
            return Err(AgentRefusal::internal("the supervisor launch is empty"));
        };
        let mut command = Command::new(program);
        command
            .args(arguments)
            .stdin(Stdio::null())
            .stdout(log.try_clone().map_err(AgentRefusal::internal)?)
            .stderr(log.try_clone().map_err(AgentRefusal::internal)?);
        detach_into_own_session(&mut command);
        let Some(unit) = &self.unit else {
            // Released on purpose: dropping a `Child` neither kills nor waits, and the supervisor outlives this
            // process, which only reports that it began.
            command.spawn().map_err(AgentRefusal::internal)?;
            return Ok(());
        };
        let launcher = Path::new(program).display();
        let ended = match command.spawn().and_then(|mut child| child.wait()) {
            Ok(status) if status.success() => return Ok(()),
            Ok(status) => format!("{launcher} ended with {status}"),
            Err(error) => format!("{launcher} could not run: {error}"),
        };
        Err(AgentRefusal::failure(
            "supervise_unit_refused",
            format!(
                "cannot start {unit} for {run_id}: {ended}; the launcher's own account is in {}",
                log_path.display()
            ),
        ))
    }
}
