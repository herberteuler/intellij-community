//! The host's process table without a host process: what a runner probes in a hermetic suite.

use std::sync::Mutex;

use avl_base::sync::lock;
use avl_host_sys::{ProcessTable, PsField};

/// The pid of the first declared process. It is above the pid limit of every host, so a signal to a fake pid reaches no
/// real process.
const FIRST_PID: i32 = 0x4000_0000;

/// The start time of every declared process. The pid tells two processes apart.
const START_TIME: &str = "Sun Aug 23 00:00:00 2026";

/// A [`ProcessTable`] that holds only the processes a suite declares. Every other pid is no such process, and that
/// includes the pid of the suite itself.
#[derive(Default)]
pub struct FakeProcesses {
    commands: Mutex<Vec<String>>,
}

impl FakeProcesses {
    /// Declares a live process that runs `command`, and answers its pid.
    pub fn start(&self, command: &str) -> i32 {
        let mut commands = lock(&self.commands);
        commands.push(command.to_owned());
        FIRST_PID + i32::try_from(commands.len() - 1).expect("a suite declares fewer than 2^31 processes")
    }
}

impl ProcessTable for FakeProcesses {
    fn field(&self, pid: i32, field: PsField) -> Option<String> {
        let index = usize::try_from(pid.checked_sub(FIRST_PID)?).ok()?;
        let command = lock(&self.commands).get(index)?.clone();
        Some(match field {
            PsField::StartTime => START_TIME.to_owned(),
            PsField::Command => command,
            PsField::State => "S".to_owned(),
        })
    }
}
