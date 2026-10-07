//! `check-node`: the two Node refusals a boot still owes an operator.
//!
//! These cases were `validate-guest`'s until 2026-08-27 and moved with the check: both codes, both messages and
//! the whole-component major match are the ones a Linux boot answered before. Nothing was weakened on the way: a
//! Node of the wrong major is still refused, not warned about.

use avl_wire::verb::AgentVerb;
use std::path::PathBuf;

use avl_wire::supervisor::AgentExit;
use pretty_assertions::assert_eq;

use super::*;
use crate::reply::verb_refusal_code;
use crate::step::StepError;
use crate::step::tests_support::Transcript;
use crate::step::tests_support::exited;
use crate::testing::run_agent;

/// The major the check reads, and a version of it whose patch level moves freely.
const MAJOR: u32 = 24;
const VERSION: &str = "v24.10.0";

/// The Node this verb is told to check, by the shape a Linux worker's really has: under the worker's data
/// directory, where `stage-node` puts it, and not `/usr/bin/node`.
const NODE: &str = "/home/admin/WorkerData/node/24.19.0/bin/node";

fn checker(answer: &str, major: u32) -> NodeChecker<Transcript> {
    let mut transcript = Transcript::default();
    transcript.answers.insert(format!("{NODE} --version"), format!("{answer}\n"));
    let args = CheckNodeArgs {
        node_binary: PathBuf::from(NODE),
        node_major: major,
    };
    NodeChecker::new(&args, transcript)
}

/// The happy path: the configured binary is asked for its version by the path it was given, and what it answered
/// is reported. Never a bare `node`: a guest where `node` exists somewhere else fails as a bare `exit 127` from
/// the only command that mattered.
#[test]
fn the_checked_node_is_asked_by_the_path_it_was_given() {
    let mut checker = checker(VERSION, MAJOR);
    let report = checker.check().unwrap();
    assert_eq!(
        report,
        NodeCheckReport {
            node: NODE.to_owned(),
            version: VERSION.to_owned()
        }
    );
    assert_eq!(checker.runner.lines(), [format!("{NODE} --version")]);
}

/// The trailing dot in the `v24.` prefix is the whole major check; the patch level is deliberately free.
#[test]
fn the_guest_node_major_is_matched_as_a_whole_component() {
    let cases = [
        ("v24.10.0", true),
        ("v24.0.0", true),
        ("v24.11.2", true),
        // The one a prefix without the dot would have accepted.
        ("v240.1.0", false),
        ("v2.4.0", false),
        ("v18.19.1", false),
        ("v24", false),
    ];
    for (version, accepted) in cases {
        let result = checker(version, MAJOR).check();
        if accepted {
            // Reported as the guest answered it, which pins *which* Node the supervisor will get.
            assert_eq!(result.unwrap().version, version);
        } else {
            let refusal = result.expect_err(version);
            assert_eq!(refusal.code, "linux_node_major_mismatch", "{version}");
        }
    }
}

/// A Node of the wrong major is **refused**, never noted, and under the family's one status.
#[test]
fn a_wrong_major_is_a_refusal_and_not_a_report() {
    let refusal = checker("v18.19.1", MAJOR).check().unwrap_err();
    assert_eq!(refusal.exit, AgentExit::Refused);
}

/// The major is what the controller told this verb, not a number this file holds.
#[test]
fn the_node_major_checked_is_the_one_the_controller_passed() {
    checker(VERSION, 26).check().unwrap_err();
    checker("v26.1.0", 26).check().unwrap();
}

/// A Node that does not run is its own refusal, naming the binary and how it ended - which path was read, and
/// that the run supervisor's children are Node programs.
#[test]
fn a_node_that_does_not_run_is_refused_by_name() {
    let args = CheckNodeArgs {
        node_binary: PathBuf::from(NODE),
        node_major: MAJOR,
    };
    let runner = |step: &Step| -> Result<String, StepError> { Err(exited(step, 127)) };
    let refusal = NodeChecker::new(&args, runner).check().unwrap_err();
    assert_eq!(refusal.code, "linux_node_not_runnable", "{}", refusal.message);
    for fragment in [NODE, "127", "Node programs"] {
        assert!(refusal.message.contains(fragment), "{fragment}: {}", refusal.message);
    }
}

/// The mismatch names the binary, its answer and the pin, and where the pin and the staging live, because the
/// answer is always one of the two.
#[test]
fn the_major_mismatch_names_the_binary_the_answer_and_the_pin() {
    let refusal = checker("v18.19.1", MAJOR).check().unwrap_err();
    for fragment in [NODE, "v18.19.1", "24", "stage-node", "config.GuestNodeMajor"] {
        assert!(refusal.message.contains(fragment), "{fragment}: {}", refusal.message);
    }
}

/// Exactly two values, checked before anything runs: a wrong argument is a wiring defect in the controller, so it
/// is a usage refusal. A pin nobody wrote would refuse every boot.
#[test]
fn the_check_node_argv_is_exactly_two_checked_values() {
    let cases: [(&str, &[&str]); 8] = [
        ("no arguments", &[]),
        ("one argument", &[NODE]),
        ("a third argument", &[NODE, "24", "/ide"]),
        ("a Node resolved on PATH rather than by path", &["node", "24"]),
        ("a Node major that is a word", &[NODE, "twenty-four"]),
        ("a Node major of zero", &[NODE, "0"]),
        ("a negative Node major", &[NODE, "-24"]),
        ("an empty Node major", &[NODE, ""]),
    ];
    for (name, argv) in cases {
        let args: Vec<&str> = std::iter::once(AgentVerb::CheckNode.as_str()).chain(argv.iter().copied()).collect();
        let answered = run_agent(&args, b"");
        assert_eq!((answered.exit, answered.code()), (64, "usage".to_owned()), "{name}");
    }
}

/// The verb's own refusal code, and the verb end to end: a Node that is not there refuses by name, exit 70, in
/// the envelope of this verb.
#[test]
fn the_check_node_verb_refuses_under_its_own_name() {
    assert_eq!(verb_refusal_code(AgentVerb::CheckNode), "guest_check_node_failed");
    let answered = run_agent(&[AgentVerb::CheckNode.as_str(), "/nonexistent/air/node", "24"], b"");
    assert_eq!(answered.exit, 70);
    assert_eq!(answered.code(), "linux_node_not_runnable");
    assert_eq!(answered.failure()["command"], AgentVerb::CheckNode.as_str());
}
