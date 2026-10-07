//! `stage-node`, tested by its transcript and by what it publishes.
//!
//! The one thing this verb decides is *whether* to run `tar` at all, and then it publishes a tree only after it
//! has proved that tree runnable. A stubbed extraction holds both properties; what the stub writes is what a Node
//! archive leaves behind.

use avl_wire::verb::AgentVerb;
use std::cell::RefCell;
use std::fs;
use std::path::PathBuf;
use std::rc::Rc;

use pretty_assertions::assert_eq;

use super::*;
use crate::cli::StageNodeArgs;
use crate::step::StepError;
use crate::step::tests_support::exited;
use crate::testing::{RunningProcess, finished_pid, run_agent};

const VERSION: &str = "24.19.0";
const WHOLE_ARCHIVE: &[&str] = &["bin/node", "bin/npm", "bin/npx", "lib/node_modules/npm/package.json"];

/// Stands in for `tar`: `contents` is what the extraction leaves under the directory `-C` names, and `fails` is
/// the extraction that did not happen at all.
#[derive(Default)]
struct Extraction {
    commands: Vec<String>,
    contents: Vec<&'static str>,
    fails: bool,
}

type Shared = Rc<RefCell<Extraction>>;

fn extraction_runner(extraction: Shared) -> impl FnMut(&Step) -> Result<String, StepError> {
    move |step: &Step| {
        let mut extraction = extraction.borrow_mut();
        extraction.commands.push(step.command_line());
        if extraction.fails {
            return Err(exited(step, 2));
        }
        let Some(index) = step.argv.iter().position(|word| word == "-C") else {
            panic!("the extraction was given no destination: {}", step.command_line());
        };
        let destination = PathBuf::from(&step.argv[index + 1]);
        for name in &extraction.contents {
            let path = destination.join(name);
            fs::create_dir_all(path.parent().unwrap()).unwrap();
            fs::write(&path, name).unwrap();
        }
        Ok(String::new())
    }
}

struct Fixture {
    _root: tempfile::TempDir,
    args: StageNodeArgs,
    extraction: Shared,
}

impl Fixture {
    fn new() -> Self {
        let root = tempfile::tempdir().unwrap();
        let archive = root.path().join(format!("node-v{VERSION}-linux-arm64.tar.gz"));
        fs::write(&archive, "archive bytes").unwrap();
        let args = StageNodeArgs {
            node_root: root.path().join("node"),
            archive,
            version: VERSION.to_owned(),
        };
        let extraction = Rc::new(RefCell::new(Extraction {
            contents: WHOLE_ARCHIVE.to_vec(),
            ..Extraction::default()
        }));
        Self {
            _root: root,
            args,
            extraction,
        }
    }

    fn stage(&self) -> Result<NodeStageReport, AgentRefusal> {
        NodeStager::new(&self.args, extraction_runner(self.extraction.clone())).stage()
    }

    fn published(&self) -> PathBuf {
        self.args.node_root.join(VERSION)
    }

    fn extractions(&self) -> usize {
        self.extraction.borrow().commands.len()
    }
}

/// A worker with no Node gets one, and the report names the two binaries the lane needs. `npm` is half the point
/// of staging an archive: Ubuntu 24.04 packages none.
#[test]
fn a_fresh_worker_gets_the_staged_node_and_its_npm() {
    let fixture = Fixture::new();
    let report = fixture.stage().unwrap();
    assert!(!report.reused);
    assert_eq!(report.version, VERSION);
    assert!(is_file(Path::new(&report.node)) && is_file(Path::new(&report.npm)), "{report:?}");
    assert_eq!(PathBuf::from(&report.node), fixture.published().join("bin/node"));
    // One extraction, of this archive, into a staging root rather than onto the published one, with the archive's
    // own `node-v24.19.0-linux-arm64/` prefix stripped.
    let commands = fixture.extraction.borrow().commands.clone();
    assert_eq!(commands.len(), 1, "{commands:?}");
    let command = &commands[0];
    assert!(
        command.starts_with(&format!("{TAR_BINARY} xzf {}", fixture.args.archive.display())),
        "{command}"
    );
    assert!(command.ends_with("--strip-components=1"), "{command}");
    assert!(!command.contains(&format!("-C {} ", fixture.published().display())), "{command}");
}

/// A warm worker pays a receipt read and three stats: the extraction is the one step of a Linux boot that would be
/// expensive twice.
#[test]
fn a_warm_worker_reuses_the_staged_node_and_extracts_nothing() {
    let fixture = Fixture::new();
    let first = fixture.stage().unwrap();
    let second = fixture.stage().unwrap();
    assert!(second.reused);
    assert_eq!((&second.root, &second.node, &second.npm), (&first.root, &first.node, &first.npm));
    assert_eq!(fixture.extractions(), 1);
    // The receipt records what `bin/node` measured, which is the field a torn tree fails.
    let receipt: NodeStageState = serde_json::from_slice(&fs::read(fixture.published().join(NODE_STATE_FILE)).unwrap()).unwrap();
    assert_eq!(receipt.node_size, fs::metadata(&first.node).unwrap().len());
    assert!(receipt.node_size > 0);
}

/// The receipt qualifies a directory, not its presence: an extraction that died halfway leaves a tree with no
/// receipt, and a `pool stop` can take the tail of `bin/node` (measured on `air-linux-2`, 2026-08-27).
type Damage<'a> = Box<dyn Fn() + 'a>;

#[test]
fn a_staged_directory_without_a_receipt_is_staged_again() {
    let fixture = Fixture::new();
    fixture.stage().unwrap();
    let published = fixture.published();
    let receipt = published.join(NODE_STATE_FILE);
    let schema = avl_wire::stage::SCHEMA_VERSION;
    let damages: Vec<(&str, Damage<'_>)> = vec![
        ("the receipt is gone", Box::new(|| fs::remove_file(&receipt).unwrap())),
        ("the receipt is not JSON", Box::new(|| fs::write(&receipt, "{").unwrap())),
        (
            "the receipt names another version",
            Box::new(|| {
                fs::write(&receipt, format!(r#"{{"schemaVersion":{schema},"version":"18.19.1"}}"#)).unwrap();
            }),
        ),
        (
            "node itself is gone",
            Box::new(|| fs::remove_file(node_binary(&published)).unwrap()),
        ),
        ("npm is gone", Box::new(|| fs::remove_file(npm_binary(&published)).unwrap())),
        (
            "node lost its tail to the disk",
            Box::new(|| {
                fs::File::options()
                    .write(true)
                    .open(node_binary(&published))
                    .unwrap()
                    .set_len(2)
                    .unwrap();
            }),
        ),
        (
            "the receipt records no size",
            Box::new(|| {
                fs::write(&receipt, format!(r#"{{"schemaVersion":{schema},"version":"{VERSION}"}}"#)).unwrap();
            }),
        ),
    ];
    for (name, damage) in damages {
        damage();
        let before = fixture.extractions();
        let report = fixture.stage().unwrap();
        assert!(!report.reused, "{name}: a damaged staged Node was reused");
        assert_eq!(fixture.extractions(), before + 1, "{name}");
    }
}

/// A killed stage leaves its staging tree under the node root. The next stage removes the tree of a pid that no
/// longer runs and keeps the tree of a running one.
#[test]
fn stage_node_removes_the_staging_tree_of_a_stage_that_no_longer_runs() {
    let fixture = Fixture::new();
    let running = RunningProcess::start();
    let abandoned = fixture.args.node_root.join(format!(".stage-{VERSION}-{}", finished_pid()));
    let in_progress = fixture.args.node_root.join(format!(".stage-22.22.0-{}", running.pid()));
    for tree in [&abandoned, &in_progress] {
        fs::create_dir_all(tree.join("bin")).unwrap();
        fs::write(tree.join("bin/node"), "a torn node").unwrap();
    }

    fixture.stage().unwrap();
    assert!(!abandoned.exists(), "the tree of a finished stage survived");
    assert!(in_progress.is_dir(), "the tree of a running stage was removed");
}

/// Every way of not getting a runnable Node answers its own code, and none leaves a directory behind for the next
/// boot's receipt check to have an opinion about.
#[test]
fn every_stage_node_failure_refuses_with_its_own_code() {
    type Damage = fn(&Fixture);
    let cases: [(&str, Damage, &str); 5] = [
        (
            "the archive is not there",
            |fixture| fs::remove_file(&fixture.args.archive).unwrap(),
            "linux_node_archive_missing",
        ),
        (
            "the archive is a directory",
            |fixture| {
                fs::remove_file(&fixture.args.archive).unwrap();
                fs::create_dir_all(&fixture.args.archive).unwrap();
            },
            "linux_node_archive_missing",
        ),
        (
            "the extraction failed",
            |fixture| fixture.extraction.borrow_mut().fails = true,
            "linux_node_extract_failed",
        ),
        // A macOS tarball extracts cleanly and holds no Linux `node`; the first exec inside a lane would find out.
        (
            "the archive holds no node",
            |fixture| fixture.extraction.borrow_mut().contents = vec!["bin/npm", "README.md"],
            "linux_node_incomplete",
        ),
        (
            "the archive holds no npm",
            |fixture| fixture.extraction.borrow_mut().contents = vec!["bin/node"],
            "linux_node_incomplete",
        ),
    ];
    for (name, damage, code) in cases {
        let fixture = Fixture::new();
        damage(&fixture);
        let refusal = fixture.stage().expect_err(name);
        assert_eq!(refusal.code, code, "{name}: {}", refusal.message);
        assert_eq!(refusal.exit, avl_wire::supervisor::AgentExit::Refused, "{name}");
        assert!(!fixture.published().exists(), "{name}: a failed staging published a directory");
        let left: Vec<_> = fs::read_dir(&fixture.args.node_root)
            .map(|entries| entries.map(|entry| entry.unwrap().file_name()).collect())
            .unwrap_or_default();
        assert!(left.is_empty(), "{name}: a failed staging left {left:?} behind");
    }
}

/// The repair is always about which file the guest was pointed at: a share that did not mount and an output base
/// that moved are the same missing path from in here.
#[test]
fn a_stage_node_refusal_names_the_archive() {
    let fixture = Fixture::new();
    fs::remove_file(&fixture.args.archive).unwrap();
    let refusal = fixture.stage().unwrap_err();
    assert!(
        refusal.message.contains(&*fixture.args.archive.to_string_lossy()),
        "{}",
        refusal.message
    );
}

/// Exactly three values, checked before anything is read. The version is checked hardest, because it becomes a
/// directory name: a separator would stage a tree where the controller does not look.
#[test]
fn the_stage_node_argv_is_exactly_three_checked_values() {
    let cases: [(&str, &[&str]); 9] = [
        ("no arguments", &[]),
        ("two arguments", &["/node", "/a.tar.gz"]),
        ("a fourth argument", &["/node", "/a.tar.gz", VERSION, "linux-arm64"]),
        ("a relative node root", &["node", "/a.tar.gz", VERSION]),
        ("a relative archive", &["/node", "a.tar.gz", VERSION]),
        ("a version with a leading v", &["/node", "/a.tar.gz", "v24.19.0"]),
        ("a version of two components", &["/node", "/a.tar.gz", "24.19"]),
        ("a version that is a path", &["/node", "/a.tar.gz", "../24.19.0"]),
        ("an empty version", &["/node", "/a.tar.gz", ""]),
    ];
    for (name, argv) in cases {
        let args: Vec<&str> = std::iter::once(AgentVerb::StageNode.as_str()).chain(argv.iter().copied()).collect();
        let answered = run_agent(&args, b"");
        assert_eq!((answered.exit, answered.code()), (64, "usage".to_owned()), "{name}");
    }
}

/// The verb end to end: the envelope carries the report, and a refusal carries its own code under the verb.
#[test]
fn the_stage_node_verb_answers_in_the_envelope() {
    let fixture = Fixture::new();
    fs::remove_file(&fixture.args.archive).unwrap();
    let answered = run_agent(
        &[
            AgentVerb::StageNode.as_str(),
            &fixture.args.node_root.to_string_lossy(),
            &fixture.args.archive.to_string_lossy(),
            VERSION,
        ],
        b"",
    );
    assert_eq!(answered.exit, 70);
    assert_eq!(answered.code(), "linux_node_archive_missing");
    assert_eq!(answered.failure()["command"], AgentVerb::StageNode.as_str());
}
