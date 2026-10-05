// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.

//! `dev-launch-args` writes the `java` argument file of a dev launch at build time.
//!
//! The rule `intellij_dev_java_launcher` (`community/build/intellij_dev.bzl`) runs the command `jvm-args` in an action.
//! The row then starts `java @<row>.jvm.args`, and no process runs before the JVM. See [`jvm_args`] for the options and
//! the file. Every other command line fails with the exit code 2.
//!
//! ```text
//! dev-launch-args jvm-args --ide-config=<file> --home=<directory> --idea-properties=<file> --vm-options=<file> \
//!   --vm-options-destination=<path> --product-info=<file> --core-classpath=<file> --flags-file=<file> \
//!   [--program-arg=<argument>...] [--runtime-module-repository] --output=<file>
//! ```

mod jvm_args;
mod properties;

use std::ffi::OsString;
use std::io::Write;
use std::process::ExitCode;

fn main() -> ExitCode {
    let args: Vec<OsString> = std::env::args_os().skip(1).collect();
    ExitCode::from(run(&args, &mut std::io::stderr()))
}

/// Runs the tool and returns the exit code of [`jvm_args::run_jvm_args`], or 2 when the command is not `jvm-args`.
fn run(args: &[OsString], errors: &mut dyn Write) -> u8 {
    match args.split_first() {
        Some((command, options)) if command == "jvm-args" => jvm_args::run_jvm_args(options, errors),
        _ => {
            cli::report(errors, &anyhow::anyhow!("the first argument must be the command `jvm-args`"));
            2
        }
    }
}

#[cfg(test)]
mod tests;
