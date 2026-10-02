//! The JVM arguments of a distribution, and the command `jvm-args`, which writes them as a `java` argument file.
//!
//! A launch prepares the arguments from the home that it starts. The rule `intellij_dev_java_launcher` runs `jvm-args`
//! at build time instead. Then `java` starts with `@<file>`, and no process runs before the JVM. The arguments are
//! the same in both forms: the caller flags, three fixed properties, the distribution properties, the class path and
//! the main class.

use std::ffi::OsString;
use std::io::Write;
use std::path::Path;

use anyhow::{Context as _, bail};
use component::paths::from_slash;
use indexmap::IndexMap;

use crate::properties::{
    ProductInfo, RUNTIME_MODULE_REPOSITORY_PROPERTY, custom_command, properties_of_files, put_system_property, read_lines,
};
use crate::{PATH_LIST_SEPARATOR, is_caller_owned_property, path_string, read_ide_config};

/// What a distribution adds to the command line of `java`.
pub(crate) struct Distribution {
    /// The home that the IDE starts from, an absolute path.
    pub(crate) home: String,
    pub(crate) info: ProductInfo,
    /// The properties of [`crate::properties::distribution_properties`].
    pub(crate) properties: IndexMap<String, String>,
    pub(crate) main_class: String,
    /// The core class path, every path absolute.
    pub(crate) class_path: Vec<String>,
    /// The runtime module repository file of the home, when the home has one.
    pub(crate) runtime_module_repository: Option<String>,
}

/// The arguments of `java` from the first caller flag to the main class. `command` is the first program argument, which
/// names the custom command when a caller flag asks for one.
///
/// A caller-owned property of `command_line` keeps its value against the distribution, as `PreBuiltDevMain` does. Every
/// other property of the distribution comes after the caller flags, so it wins.
pub(crate) fn java_arguments(command_line: Vec<String>, distribution: Distribution, command: Option<&str>) -> anyhow::Result<Vec<String>> {
    let Distribution {
        home,
        info,
        mut properties,
        mut main_class,
        class_path,
        runtime_module_repository,
    } = distribution;
    let mut caller_properties = IndexMap::new();
    for flag in &command_line {
        put_system_property(&mut caller_properties, flag);
    }
    if caller_properties
        .get("idea.dev.mode.custom.command")
        .is_some_and(|value| value.eq_ignore_ascii_case("true"))
    {
        let Some(command) = command else {
            bail!("-Didea.dev.mode.custom.command=true needs the command as the first program argument");
        };
        let (command_main_class, command_properties) = custom_command(&home, &info, command)?;
        main_class = command_main_class;
        properties.extend(command_properties);
    }
    if let Some(file) = runtime_module_repository {
        add_runtime_module_repository(&mut properties, &file, &caller_properties);
    }

    let mut arguments = command_line;
    // `PreBuiltDevMain` sets these three before the properties of the distribution, which can override them.
    arguments.extend([
        "-Didea.vendor.name=JetBrains".to_owned(),
        "-Didea.use.dev.build.server=true".to_owned(),
        format!("-Didea.home.path={home}"),
    ]);
    for (key, value) in &properties {
        if caller_properties.contains_key(key) && is_caller_owned_property(key) {
            continue;
        }
        arguments.push(format!("-D{key}={value}"));
    }
    arguments.extend(["-cp".to_owned(), class_path.join(PATH_LIST_SEPARATOR), main_class]);
    Ok(arguments)
}

/// Adds the runtime module repository `file` of the home to `properties`, as
/// `PreBuiltDevMain.addRuntimeModuleRepository` does.
///
/// The distribution states the property through `product-info.json` when its launch model asks. So the launcher adds
/// `file` only when neither `properties` nor `caller_properties` state the property.
pub(crate) fn add_runtime_module_repository(
    properties: &mut IndexMap<String, String>,
    file: &str,
    caller_properties: &IndexMap<String, String>,
) {
    if !properties.contains_key(RUNTIME_MODULE_REPOSITORY_PROPERTY) && !caller_properties.contains_key(RUNTIME_MODULE_REPOSITORY_PROPERTY) {
        properties.insert(RUNTIME_MODULE_REPOSITORY_PROPERTY.to_owned(), file.to_owned());
    }
}

/// The class path of `core-classpath.txt` at `file`. A relative line is relative to `home`.
pub(crate) fn read_class_path(file: &Path, home: &str) -> anyhow::Result<Vec<String>> {
    read_lines(file)?
        .iter()
        .map(|line| line.trim())
        .filter(|line| !line.is_empty())
        .map(|line| {
            if Path::new(line).is_absolute() {
                Ok(line.to_owned())
            } else {
                path_string(Path::new(home).join(from_slash(line).as_ref()))
            }
        })
        .collect()
}

/// The options of `jvm-args`. Each path names a file of the distribution where the action reads it. `home` and
/// `vm_options_destination` state where the IDE finds the distribution when it starts.
#[derive(Debug)]
struct JvmArgsOptions {
    ide_config: String,
    home: String,
    idea_properties: String,
    vm_options: String,
    vm_options_destination: String,
    product_info: String,
    core_classpath: String,
    flags_file: String,
    command: Option<String>,
    runtime_module_repository: bool,
    output: String,
}

/// `jvm-args`: writes the argument file of a distribution. It returns the exit code: 2 for an option error, 1 for any
/// other error.
pub(crate) fn run_jvm_args(args: &[OsString], errors: &mut dyn Write) -> u8 {
    let options = match parse_jvm_args(args) {
        Ok(options) => options,
        Err(error) => {
            cli::report(errors, &error);
            return 2;
        }
    };
    match write_jvm_args(&options) {
        Ok(()) => 0,
        Err(error) => {
            cli::report(errors, &error);
            1
        }
    }
}

fn parse_jvm_args(args: &[OsString]) -> anyhow::Result<JvmArgsOptions> {
    let mut options = cli::parse(args.iter().cloned())?;
    let result = JvmArgsOptions {
        ide_config: options.require("--ide-config")?,
        home: options.require("--home")?,
        idea_properties: options.require("--idea-properties")?,
        vm_options: options.require("--vm-options")?,
        vm_options_destination: options.require("--vm-options-destination")?,
        product_info: options.require("--product-info")?,
        core_classpath: options.require("--core-classpath")?,
        flags_file: options.require("--flags-file")?,
        command: options.take("--command")?,
        runtime_module_repository: options.flag("--runtime-module-repository")?,
        output: options.require("--output")?,
    };
    options.finish()?;
    if !Path::new(&result.home).is_absolute() {
        bail!("--home must be an absolute path: {}", result.home);
    }
    Ok(result)
}

fn write_jvm_args(options: &JvmArgsOptions) -> anyhow::Result<()> {
    let home = &options.home;
    let (_, main_class) = read_ide_config(&options.ide_config)?;
    let info = ProductInfo::read_file(Path::new(&options.product_info))?;
    let vm_options_path = path_string(Path::new(home).join(from_slash(&options.vm_options_destination).as_ref()))?;
    let properties = properties_of_files(
        Path::new(&options.idea_properties),
        Path::new(&options.vm_options),
        &vm_options_path,
        home,
        &info,
    )?;
    let runtime_module_repository = if options.runtime_module_repository {
        Some(path_string(Path::new(home).join("modules").join("module-descriptors.dat"))?)
    } else {
        None
    };
    let distribution = Distribution {
        home: home.clone(),
        info,
        properties,
        main_class,
        class_path: read_class_path(Path::new(&options.core_classpath), home)?,
        runtime_module_repository,
    };
    let command_line = read_lines(Path::new(&options.flags_file))?;
    let arguments = java_arguments(command_line, distribution, options.command.as_deref())?;
    let mut text = String::new();
    for argument in &arguments {
        text.push_str(&quote_argument(argument));
        text.push('\n');
    }
    std::fs::write(&options.output, text).with_context(|| format!("write {}", options.output))
}

/// One argument of a `java` argument file. An argument with a space, a quote, a number sign or a backslash is in double
/// quotes, with each backslash and each double quote escaped. `java` reads every other argument as it is.
pub(crate) fn quote_argument(argument: &str) -> String {
    if !argument.is_empty() && !argument.contains(|character: char| character.is_whitespace() || "\"'#\\".contains(character)) {
        return argument.to_owned();
    }
    let mut quoted = String::with_capacity(argument.len() + 2);
    quoted.push('"');
    for character in argument.chars() {
        if character == '"' || character == '\\' {
            quoted.push('\\');
        }
        quoted.push(character);
    }
    quoted.push('"');
    quoted
}

#[cfg(test)]
mod tests;
