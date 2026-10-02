//! The check of the home placement that the Starlark rules state at analysis.
//!
//! A launcher that starts `java` directly gets its home from the runfiles tree, and Bazel writes that tree only from
//! paths that analysis knows. So each component states its placement in Starlark too. The composer checks that the
//! placement names every entry of the component manifests and nothing else. A difference fails the build, not the
//! launch.

use std::collections::{BTreeMap, HashSet};

use anyhow::{Result, bail};
use component::manifest::{ComponentEntry, ComponentManifest};
use serde::Deserialize;

/// The most problems that one error lists. The error states the count of the others.
const LISTED_PROBLEMS: usize = 20;

/// The placement of a home, keyed by the destination in slash form.
#[derive(Debug, Clone, Default, PartialEq, Eq, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub(crate) struct Placement {
    /// A file destination and the path of its source, as the component manifest states the source.
    pub(crate) files: BTreeMap<String, String>,
    /// A directory destination and the path of the directory artifact that the home links there. The artifact holds
    /// every entry below the destination at the same relative path.
    pub(crate) trees: BTreeMap<String, String>,
    /// A directory destination and the kind of the component whose component home the home links there.
    pub(crate) homes: BTreeMap<String, String>,
    /// The file destinations that need the executable bit.
    pub(crate) executables: Vec<String>,
}

/// The directory that a home links as a whole.
enum Root<'a> {
    Tree(&'a str),
    Home(&'a str),
}

/// Checks that `placement` states every entry of `manifests` and nothing else.
///
/// An entry below a tree must have its source at the same relative path in the tree. An entry below a component home
/// must belong to that component. Every other file must be a placed file with the same source and the same executable
/// flag, and without an exact mode. A link must be below a tree or a component home, because a runfiles tree holds no
/// declared link. A directory entry outside a root must hold a placed file, because a runfiles tree has no empty
/// directory.
pub(crate) fn check_placement(manifests: &[&ComponentManifest], placement: &Placement) -> Result<()> {
    let executables: HashSet<&str> = placement.executables.iter().map(String::as_str).collect();
    let mut problems = Vec::new();
    let mut placed_files = HashSet::new();
    let mut covered_roots = HashSet::new();
    let mut directories = Vec::new();
    for manifest in manifests {
        for entry in &manifest.entries {
            let path = entry.relative_path();
            if let Some((root, kind)) = covering_root(placement, path) {
                covered_roots.insert(root);
                match (kind, entry) {
                    (Root::Tree(directory), ComponentEntry::ComponentFile { source, .. }) => {
                        let expected = format!("{directory}/{}", path.get(root.len() + 1..).unwrap_or_default());
                        if *source != expected {
                            problems.push(format!("'{path}' comes from {source}, and the tree at '{root}' holds {expected}"));
                        }
                    }
                    (Root::Home(home_kind), _) if *home_kind != manifest.kind => {
                        problems.push(format!(
                            "'{path}' belongs to the component '{}', and '{root}' is the home of '{home_kind}'",
                            manifest.kind
                        ));
                    }
                    _ => {}
                }
                continue;
            }
            match entry {
                ComponentEntry::ComponentFile {
                    source, executable, mode, ..
                } => {
                    match placement.files.get(path) {
                        None => problems.push(format!("'{path}' of the component '{}' is not placed", manifest.kind)),
                        Some(placed) if placed != source => {
                            problems.push(format!("'{path}' comes from {source}, and the placement states {placed}"));
                        }
                        Some(_) => {}
                    }
                    placed_files.insert(path);
                    if *executable != executables.contains(path) {
                        problems.push(format!(
                            "'{path}' is {}executable in the manifest and {}executable in the placement",
                            if *executable { "" } else { "not " },
                            if executables.contains(path) { "" } else { "not " }
                        ));
                    }
                    if let Some(mode) = mode {
                        problems.push(format!("'{path}' has the exact mode {mode:o}, which a runfiles home cannot give"));
                    }
                }
                ComponentEntry::Directory { .. } => directories.push(path),
                ComponentEntry::Symlink { .. } => {
                    problems.push(format!("'{path}' is a link outside a placed directory"));
                }
            }
        }
    }
    for path in placement.files.keys() {
        if !placed_files.contains(path.as_str()) {
            problems.push(format!("the placement states '{path}', which no component manifest names"));
        }
    }
    for root in placement.trees.keys().chain(placement.homes.keys()) {
        if !covered_roots.contains(root.as_str()) {
            problems.push(format!(
                "the placement links '{root}', and no component manifest names an entry there"
            ));
        }
    }
    for path in &placement.executables {
        if !placement.files.contains_key(path) {
            problems.push(format!("the placement marks '{path}' executable, and it is no placed file"));
        }
    }
    for directory in directories {
        let prefix = format!("{directory}/");
        let holds = placement.files.keys().any(|path| path.starts_with(&prefix))
            || placement
                .trees
                .keys()
                .chain(placement.homes.keys())
                .any(|root| root.starts_with(&prefix));
        if !holds {
            problems.push(format!("'{directory}' is an empty directory, which a runfiles home cannot hold"));
        }
    }
    if problems.is_empty() {
        return Ok(());
    }
    let count = problems.len();
    problems.truncate(LISTED_PROBLEMS);
    let mut message = format!("The home placement differs from the component manifests in {count} entries:");
    for problem in &problems {
        message.push_str("\n  ");
        message.push_str(problem);
    }
    if count > LISTED_PROBLEMS {
        message.push_str(&format!("\n  and {} more", count - LISTED_PROBLEMS));
    }
    bail!(message)
}

/// The deepest tree or component home that holds `path`, or `path` itself, with its kind.
fn covering_root<'a>(placement: &'a Placement, path: &str) -> Option<(&'a str, Root<'a>)> {
    let mut current = path;
    loop {
        if let Some((root, directory)) = placement.trees.get_key_value(current) {
            return Some((root.as_str(), Root::Tree(directory.as_str())));
        }
        if let Some((root, kind)) = placement.homes.get_key_value(current) {
            return Some((root.as_str(), Root::Home(kind.as_str())));
        }
        current = current.rsplit_once('/')?.0;
    }
}

#[cfg(test)]
mod tests;
