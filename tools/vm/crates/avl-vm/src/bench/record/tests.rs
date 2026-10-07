use std::collections::BTreeMap;

use pretty_assertions::assert_eq;

use super::{
    CLASSES_HIDDEN, CLASSES_JAR, CLASSES_JDK, CLASSES_NAMED, CLASSES_PLATFORM, CLASSES_PLUGINS, EDITOR_HIGHLIGHTED, LaunchFacts, RunId,
    RunKind, WELCOME_BECAME_VISIBLE, collect,
};
use crate::bench::arm::Arm;
use crate::bench::testing::fixture_session;

/// The plugin of the Markdown classes of the project fixture.
const MARKDOWN: &str = "org.intellij.plugins.markdown";

fn counts(pairs: &[(&str, u64)]) -> BTreeMap<String, u64> {
    pairs.iter().map(|(name, count)| ((*name).to_owned(), *count)).collect()
}

fn id(arm: Arm) -> RunId {
    RunId {
        arm,
        kind: RunKind::Measured,
        index: 1,
    }
}

#[test]
fn a_non_modal_run_passes_the_gate_and_has_the_welcome_spans() {
    let (_dir, session) = fixture_session();
    let record = collect(&session.join("non-modal-run-01"), &id(Arm::NonModal), LaunchFacts::default());
    assert_eq!(record.reason, None);
    assert!(record.valid);
    for metric in [
        WELCOME_BECAME_VISIBLE,
        "frameBecameInteractive",
        "totalDuration",
        "classLoading.requests",
        "pluginClasses",
        "ProjectManager.openAsync",
        "welcome screen painted",
        "welcome left toolbar first fill",
        "welcome right tab body: createContent trial.started.banner",
    ] {
        assert!(record.metrics.contains_key(metric), "{metric}: {:?}", record.metrics.keys());
    }
    assert!(!record.metrics.keys().any(|metric| metric.ends_with(": scheduled")));
    assert!(record.edt_samples.is_some_and(|samples| samples > 0));
    assert_eq!(record.metrics.get("classLoading.count"), None, "the report key is a request count");
    assert!(!record.triggers.is_empty(), "the profile gives the class-loading triggers");
    assert!(
        record.define_share.is_some_and(|share| share > 0.0 && share < 1.0),
        "{:?}",
        record.define_share
    );
}

#[test]
fn a_modal_run_without_the_report_is_valid_with_a_note() {
    let (_dir, session) = fixture_session();
    let record = collect(&session.join("modal-run-01"), &id(Arm::Modal), LaunchFacts::default());
    assert!(record.valid, "{:?}", record.reason);
    assert_eq!(
        record.notes,
        vec!["no startup-stats.json".to_owned(), "no class-load.log".to_owned()]
    );
    assert_eq!(record.metrics.get("pluginClasses"), Some(&157.0));
    assert_eq!(
        record.classes_by_plugin.values().sum::<u64>(),
        157,
        "the plugin log alone gives the map"
    );
}

#[test]
fn the_gate_names_what_is_missing() {
    let (_dir, session) = fixture_session();
    let non_modal = session.join("non-modal-run-01");
    let record = collect(&non_modal, &id(Arm::Modal), LaunchFacts::default());
    assert_eq!(
        record.reason.as_deref(),
        Some("welcome.screen.became.visible has is_modal=false, and the modal arm expects is_modal=true")
    );
    assert!(record.metrics.is_empty(), "a failed run reports no number");

    std::fs::write(non_modal.join("log/idea.log"), "nothing\n").expect("a log");
    let record = collect(&non_modal, &id(Arm::NonModal), LaunchFacts::default());
    assert_eq!(
        record.reason.as_deref(),
        Some("log/idea.log has no line \"Opened the welcome screen project\"")
    );

    let failure = LaunchFacts {
        failure: Some("no welcome.screen.became.visible within 180 s".to_owned()),
        ..LaunchFacts::default()
    };
    let record = collect(&session.join("modal-run-01"), &id(Arm::Modal), failure);
    assert_eq!(record.reason.as_deref(), Some("no welcome.screen.became.visible within 180 s"));

    std::fs::remove_file(session.join("modal-run-01/fus.jsonl")).expect("a removal");
    let record = collect(&session.join("modal-run-01"), &id(Arm::Modal), LaunchFacts::default());
    assert_eq!(record.reason.as_deref(), Some("no fus.jsonl"));
}

#[test]
fn a_truncated_trace_is_a_note_and_an_unknown_report_is_a_failure() {
    let (_dir, session) = fixture_session();
    let run = session.join("non-modal-run-01");
    let trace = std::fs::read_to_string(run.join("opentelemetry.json")).expect("a trace");
    std::fs::write(run.join("opentelemetry.json"), &trace[..trace.len() / 2]).expect("a trace");
    let record = collect(&run, &id(Arm::NonModal), LaunchFacts::default());
    assert!(record.valid, "{:?}", record.reason);
    assert!(
        record
            .notes
            .contains(&"opentelemetry.json is truncated; the reader closed it".to_owned())
    );

    let report = std::fs::read_to_string(run.join("startup-stats.json")).expect("a report");
    std::fs::write(run.join("startup-stats.json"), report.replace("\"38\"", "\"40\"")).expect("a report");
    let record = collect(&run, &id(Arm::NonModal), LaunchFacts::default());
    assert_eq!(
        record.reason.as_deref(),
        Some("startup-stats.json: version 40 is not supported, only 38")
    );
}

#[test]
fn a_run_directory_name_round_trips() {
    for name in [
        "modal-prime",
        "non-modal-run-07",
        "open-project-run-12",
        "project-prime",
        "open-project-prime",
    ] {
        assert_eq!(RunId::parse(name).map(|id| id.dir_name()).as_deref(), Some(name));
    }
    for name in ["template", "modal-run-00", "sideways-run-01", "modal-run-x"] {
        assert_eq!(RunId::parse(name), None, "{name}");
    }
}

#[test]
fn a_project_run_passes_the_highlighted_gate_without_a_fus_log() {
    let (_dir, session) = fixture_session();
    assert_eq!(
        RunId::parse("project-run-01"),
        Some(RunId {
            arm: Arm::Project,
            kind: RunKind::Measured,
            index: 1
        })
    );
    let record = collect(&session.join("project-run-01"), &id(Arm::Project), LaunchFacts::default());
    assert_eq!(record.reason, None);
    assert_eq!(record.notes, vec!["no fus.jsonl".to_owned()]);
    let metric = |name: &str| record.metrics.get(name).copied();
    assert_eq!(
        metric(EDITOR_HIGHLIGHTED),
        Some(2950.0),
        "the span of the trace wins over the report"
    );
    assert_eq!(metric("editor restoring till paint"), Some(620.0));
    assert_eq!(metric("editor restoring"), Some(180.5));
    assert_eq!(metric("project frame creating"), Some(218.1));
    assert_eq!(metric(WELCOME_BECAME_VISIBLE), None);
}

/// The class-load log gives the counts by source. With the plugin log it gives the owners, and with the report the
/// counts before each anchor: the anchor plus `jvm.loadingTime`, 358 ms, on the JVM clock.
#[test]
fn a_project_run_counts_the_classes_of_the_jvm_log_before_each_anchor() {
    let (_dir, session) = fixture_session();
    let record = collect(&session.join("project-run-01"), &id(Arm::Project), LaunchFacts::default());
    assert_eq!(record.reason, None);
    let classes: BTreeMap<&str, f64> = record
        .metrics
        .iter()
        .filter(|(metric, _)| metric.starts_with("classes."))
        .map(|(metric, value)| (metric.as_str(), *value))
        .collect();
    assert_eq!(
        classes,
        BTreeMap::from([
            (CLASSES_NAMED, 26.0),
            (CLASSES_HIDDEN, 3.0),
            (CLASSES_JDK, 5.0),
            (CLASSES_JAR, 2.0),
            (CLASSES_PLATFORM, 8.0),
            (CLASSES_PLUGINS, 11.0),
            ("classes.named@editor highlighting completed", 20.0),
            ("classes.named@quit", 25.0),
        ])
    );
    assert_eq!(record.metrics.get("pluginClasses"), Some(&43.0));
    assert_eq!(record.classes_by_plugin.get(MARKDOWN), Some(&3));
    assert_eq!(record.classes_by_plugin.values().sum::<u64>(), 11);
    assert_eq!(record.classes_by_module.get("intellij.markdown.core"), Some(&1));
    assert_eq!(
        record.classes_by_plugin_at.keys().collect::<Vec<_>>(),
        ["editor highlighting completed", "quit"]
    );
    assert_eq!(
        record.classes_by_plugin_at.get(EDITOR_HIGHLIGHTED),
        Some(&counts(&[
            ("com.intellij", 2),
            ("com.jetbrains.performancePlugin", 1),
            (MARKDOWN, 3)
        ]))
    );
}

/// A bound moves with `jvm.loadingTime`, the offset of the JVM clock to the process clock.
#[test]
fn the_anchor_counts_move_with_the_jvm_clock() {
    let (_dir, session) = fixture_session();
    let run = session.join("project-run-01");
    let report = std::fs::read_to_string(run.join("startup-stats.json")).expect("a report");
    let at_highlighting = |loading_time: &str| {
        let shifted = report.replace(r#""loadingTime":358"#, &format!(r#""loadingTime":{loading_time}"#));
        std::fs::write(run.join("startup-stats.json"), shifted).expect("a report");
        let record = collect(&run, &id(Arm::Project), LaunchFacts::default());
        let markdown = record
            .classes_by_plugin_at
            .get(EDITOR_HIGHLIGHTED)
            .and_then(|counts| counts.get(MARKDOWN).copied());
        (record.metrics.get("classes.named@editor highlighting completed").copied(), markdown)
    };
    assert_eq!(at_highlighting("358"), (Some(20.0), Some(3)));
    assert_eq!(at_highlighting("0"), (Some(18.0), Some(2)), "the bound is 2950 ms on the JVM clock");
    assert_eq!(
        at_highlighting("1000"),
        (Some(22.0), Some(3)),
        "the bound is 3950 ms on the JVM clock"
    );
}

/// A missing class-load log is a note, and so is a log of another shape. Neither fails the run. A report without
/// `jvm.loadingTime` leaves the counts without anchors.
#[test]
fn a_missing_or_bad_class_log_is_a_note_and_a_report_without_the_jvm_offset_has_no_anchors() {
    let (_dir, session) = fixture_session();
    let run = session.join("project-run-01");
    let report = std::fs::read_to_string(run.join("startup-stats.json")).expect("a report");
    std::fs::write(run.join("startup-stats.json"), report.replace(r#""jvm":{"loadingTime":358},"#, "")).expect("a report");
    let record = collect(&run, &id(Arm::Project), LaunchFacts::default());
    assert!(record.valid, "{:?}", record.reason);
    assert_eq!(
        record.notes,
        [
            "no fus.jsonl",
            "startup-stats.json has no jvm.loadingTime, so the class counts have no anchors"
        ]
    );
    assert_eq!(record.metrics.get(CLASSES_NAMED), Some(&26.0));
    assert!(!record.metrics.keys().any(|metric| metric.contains('@')), "{:?}", record.metrics);
    assert!(record.classes_by_plugin_at.is_empty());

    std::fs::write(run.join("class-load.log"), "[0.1s][class,load] a.A\n").expect("a log");
    let record = collect(&run, &id(Arm::Project), LaunchFacts::default());
    assert!(record.valid, "{:?}", record.reason);
    assert_eq!(
        record.notes,
        [
            "no fus.jsonl",
            "class-load.log: line 1: [0.1s][class,load] a.A: no ` source: ` after the class name",
        ]
    );
    assert_eq!(record.metrics.get(CLASSES_NAMED), None);
    assert_eq!(
        record.classes_by_plugin.get(MARKDOWN),
        Some(&3),
        "the plugin log alone gives the map"
    );

    std::fs::remove_file(run.join("class-load.log")).expect("a removal");
    let record = collect(&run, &id(Arm::Project), LaunchFacts::default());
    assert_eq!(record.notes, ["no fus.jsonl", "no class-load.log"]);
}

#[test]
fn the_highlighted_gate_reads_the_report_without_a_trace_and_names_what_is_missing() {
    let (_dir, session) = fixture_session();
    let run = session.join("project-run-01");
    std::fs::remove_file(run.join("opentelemetry.json")).expect("a removal");
    let record = collect(&run, &id(Arm::Project), LaunchFacts::default());
    assert_eq!(record.reason, None);
    assert_eq!(
        record.metrics.get(EDITOR_HIGHLIGHTED).copied(),
        Some(2950.4),
        "the instant event of the report"
    );

    let report = std::fs::read_to_string(run.join("startup-stats.json")).expect("a report");
    std::fs::write(run.join("startup-stats.json"), report.replace(EDITOR_HIGHLIGHTED, "editor opened")).expect("a report");
    let record = collect(&run, &id(Arm::Project), LaunchFacts::default());
    assert_eq!(
        record.reason.as_deref(),
        Some("no \"editor highlighting completed\" in the trace or the report")
    );
    assert!(record.metrics.is_empty(), "a failed run reports no number");

    std::fs::remove_file(run.join("startup-stats.json")).expect("a removal");
    let record = collect(&run, &id(Arm::Project), LaunchFacts::default());
    assert_eq!(record.reason.as_deref(), Some("no opentelemetry.json and no startup-stats.json"));
}
