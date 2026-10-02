//! The component home: the directory of one plugin component as a directory artifact.
//!
//! The command `component-home --component-manifest=<file> --plugin-directory=plugins/<name> --output-dir=<directory>`
//! writes every entry of the manifest into the output directory, without the plugin directory prefix. A launcher
//! links the output at `plugins/<name>` of its runfiles home. A complex plugin needs it, because its remainder tree
//! and its reused jars share directories, and a runfiles tree cannot merge two artifacts into one directory.

use std::fs;
use std::path::{Path, PathBuf};

use anyhow::{Context as _, Result, bail};
use component::manifest::{ComponentEntry, ComponentManifest};
use component::paths;

/// Writes the entries of `manifest` below `plugin_directory` into `output`, which must be absent or empty.
///
/// A file is a clone of its source where the file system can make one, with the declared mode. A link keeps its target
/// text. A directory gets its mode after its children exist, the deepest first. Every entry must be below
/// `plugin_directory`. A directory entry of `plugin_directory` itself is the output.
pub(crate) fn write_component_home(manifest: &ComponentManifest, plugin_directory: &str, output: &Path) -> Result<()> {
    if !plugin_directory.starts_with("plugins/") || plugin_directory.matches('/').count() != 1 || plugin_directory.ends_with('/') {
        bail!("'{plugin_directory}' is not `plugins/<directory>`");
    }
    let prefix = format!("{plugin_directory}/");
    match fs::read_dir(output) {
        Ok(mut children) => {
            if children.next().is_some() {
                bail!("The component home must be empty: {}", output.display());
            }
        }
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => {}
        Err(error) => return Err(error).with_context(|| output.display().to_string()),
    }
    create_directories(output)?;
    let mut links = Vec::new();
    let mut directories = Vec::new();
    for entry in &manifest.entries {
        let path = entry.relative_path();
        if path == plugin_directory {
            if let ComponentEntry::Directory { mode, .. } = entry {
                directories.push((String::new(), *mode));
                continue;
            }
            bail!(
                "'{path}' of the component '{}' is the plugin directory and no directory",
                manifest.kind
            );
        }
        let Some(relative) = path.strip_prefix(&prefix) else {
            bail!("'{path}' of the component '{}' is not below {plugin_directory}", manifest.kind);
        };
        let destination = destination(output, relative);
        match entry {
            ComponentEntry::Directory { mode, .. } => {
                create_directories(&destination)?;
                directories.push((relative.to_owned(), *mode));
            }
            ComponentEntry::Symlink { symlink_target, .. } => links.push((destination, symlink_target.as_str())),
            ComponentEntry::ComponentFile {
                source, executable, mode, ..
            } => {
                create_directories(destination.parent().expect("a destination is below the output"))?;
                let source = paths::absolute_path(source)?;
                fscopy::copy_with_attributes(&source, &destination)?;
                fscopy::set_distribution_file_mode(&destination, *executable, *mode)?;
            }
        }
    }
    for (link, target) in links {
        create_directories(link.parent().expect("a link is below the output"))?;
        let target_is_directory = cfg!(windows)
            && fs::metadata(link.parent().unwrap_or(output).join(paths::from_slash(target).as_ref()))
                .is_ok_and(|metadata| metadata.is_dir());
        fscopy::symlink(Path::new(target), &link, target_is_directory)?;
    }
    directories.sort_by(|first, second| second.0.cmp(&first.0));
    for (relative, mode) in directories {
        let directory = if relative.is_empty() {
            output.to_path_buf()
        } else {
            destination(output, &relative)
        };
        fscopy::set_distribution_file_mode(&directory, false, Some(mode))?;
    }
    Ok(())
}

fn destination(output: &Path, relative_path: &str) -> PathBuf {
    output.join(paths::from_slash(relative_path).as_ref())
}

fn create_directories(directory: &Path) -> Result<()> {
    fs::create_dir_all(directory).with_context(|| directory.display().to_string())
}

#[cfg(test)]
mod tests;
