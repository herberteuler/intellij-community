use std::ffi::OsString;
use std::path::Path;

use super::*;

fn write_file(path: &Path, content: &str) {
    std::fs::create_dir_all(path.parent().unwrap()).unwrap();
    std::fs::write(path, content).unwrap();
}

/// The macro of `product-info.json` for the IDE home on the host OS.
fn ide_home_macro() -> &'static str {
    match std::env::consts::OS {
        "windows" => "%IDE_HOME%",
        "macos" => "$APP_PACKAGE/Contents",
        _ => "$IDE_HOME",
    }
}

/// Writes the inputs of `jvm-args` below `directory` at the paths of Bazel outputs, and returns the arguments.
fn write_inputs(directory: &Path, flags: &[&str], extra: &[&str]) -> Vec<OsString> {
    let home_macro = ide_home_macro();
    write_file(
        &directory.join("out/idea.properties"),
        "idea.config.path=${user.home}/config\nidea.platform.prefix=fromDistribution\n",
    );
    write_file(
        &directory.join("out/vmoptions"),
        "-Xmx2048m\n-Dsun.io.useCanonCaches=false\n-Djdk.http.auth.tunneling.disabledSchemes=\"\"\n",
    );
    write_file(
        &directory.join("out/product-info.json"),
        &serde_json::json!({"launch": [{
            "additionalJvmArguments": [format!("-Djna.boot.library.path={home_macro}/lib/jna")],
            "customCommands": [{"commands": ["ijLight"], "mainClass": "com.example.LightMain", "additionalJvmArguments": ["-Dlight=1"]}],
        }]})
        .to_string(),
    );
    write_file(&directory.join("out/core-classpath.txt"), "lib/a.jar\n\nlib/b.jar\n");
    write_file(
        &directory.join("out/idea.ide.config"),
        "home.path=idea.metadata\nmain.class.name=com.intellij.idea.Main\n",
    );
    write_file(
        &directory.join("out/flags.txt"),
        &flags.iter().map(|flag| format!("{flag}\n")).collect::<String>(),
    );
    let path = |name: &str| directory.join(name).display().to_string();
    let mut args: Vec<String> = vec![
        format!("--ide-config={}", path("out/idea.ide.config")),
        "--home=/ws/runfiles/ide_home".to_owned(),
        format!("--idea-properties={}", path("out/idea.properties")),
        format!("--vm-options={}", path("out/vmoptions")),
        "--vm-options-destination=bin/idea.vmoptions".to_owned(),
        format!("--product-info={}", path("out/product-info.json")),
        format!("--core-classpath={}", path("out/core-classpath.txt")),
        format!("--flags-file={}", path("out/flags.txt")),
        format!("--output={}", path("out/idea.jvm.args")),
    ];
    args.extend(extra.iter().map(|arg| (*arg).to_owned()));
    args.into_iter().map(OsString::from).collect()
}

fn run(args: &[OsString]) -> (u8, String) {
    let mut errors = Vec::new();
    let code = run_jvm_args(args, &mut errors);
    (code, String::from_utf8(errors).unwrap())
}

#[test]
fn the_argument_file_holds_the_command_line_of_a_launch() {
    let directory = tempfile::tempdir().unwrap();
    let args = write_inputs(
        directory.path(),
        &["-Dawt.toolkit.name=auto", "-Dsun.io.useCanonCaches=true", "-ea"],
        &[],
    );
    assert_eq!(run(&args), (0, String::new()));
    let text = std::fs::read_to_string(directory.path().join("out/idea.jvm.args")).unwrap();
    let lines: Vec<&str> = text.lines().collect();
    assert_eq!(lines[..3], ["-Dawt.toolkit.name=auto", "-Dsun.io.useCanonCaches=true", "-ea"]);
    for expected in [
        "-Didea.home.path=/ws/runfiles/ide_home",
        "-Didea.platform.prefix=fromDistribution",
        // the distribution wins over a flag that the caller does not own
        "-Dsun.io.useCanonCaches=false",
        "\"-Djdk.http.auth.tunneling.disabledSchemes=\\\"\\\"\"",
        "-Djb.vmOptionsFile=/ws/runfiles/ide_home/bin/idea.vmoptions",
        "-Djna.boot.library.path=/ws/runfiles/ide_home/lib/jna",
    ] {
        assert!(lines.contains(&expected), "the argument file misses {expected:?}:\n{text}");
    }
    assert!(
        !text.contains("-Xmx2048m"),
        "the argument file holds a flag that is no property:\n{text}"
    );
    assert!(!text.contains("intellij.platform.runtime.repository.path"), "{text}");
    let classpath = format!("/ws/runfiles/ide_home/lib/a.jar{PATH_LIST_SEPARATOR}/ws/runfiles/ide_home/lib/b.jar");
    assert_eq!(lines[lines.len() - 3..], ["-cp", classpath.as_str(), "com.intellij.idea.Main"]);
}

#[test]
fn the_argument_file_starts_a_custom_command_and_names_the_repository() {
    let directory = tempfile::tempdir().unwrap();
    let args = write_inputs(
        directory.path(),
        &["-Didea.dev.mode.custom.command=true"],
        &["--program-arg=ijLight", "--program-arg=/ws/project", "--runtime-module-repository"],
    );
    assert_eq!(run(&args), (0, String::new()));
    let text = std::fs::read_to_string(directory.path().join("out/idea.jvm.args")).unwrap();
    // The first program argument names the command, and the file still passes it to the main class of the command.
    assert!(text.ends_with("\ncom.example.LightMain\nijLight\n/ws/project\n"), "{text}");
    assert!(text.contains("-Dlight=1\n"), "{text}");
    assert!(
        text.contains("-Dintellij.platform.runtime.repository.path=/ws/runfiles/ide_home/modules/module-descriptors.dat\n"),
        "{text}"
    );
}

#[test]
fn the_program_arguments_follow_the_main_class_in_their_order() {
    let directory = tempfile::tempdir().unwrap();
    let args = write_inputs(
        directory.path(),
        &["-ea"],
        &[
            "--program-arg=serverMode",
            "--program-arg=--project=/ws/Kotlin Koans",
            "--program-arg=@not-a-file",
            "--program-arg=",
            "--program-arg=/$tcp.ij/a\\b",
        ],
    );
    assert_eq!(run(&args), (0, String::new()));
    let text = std::fs::read_to_string(directory.path().join("out/idea.jvm.args")).unwrap();
    let lines: Vec<&str> = text.lines().collect();
    assert_eq!(
        lines[lines.len() - 6..],
        [
            "com.intellij.idea.Main",
            "serverMode",
            "\"--project=/ws/Kotlin Koans\"",
            "@not-a-file",
            "\"\"",
            "\"/$tcp.ij/a\\\\b\"",
        ]
    );
    // Without the custom command property, the first program argument is a plain argument of the IDE.
    assert!(!text.contains("-Dlight=1"), "{text}");
}

#[test]
fn a_program_argument_needs_a_value() {
    let directory = tempfile::tempdir().unwrap();
    let args = write_inputs(directory.path(), &[], &["--program-arg"]);
    let (code, errors) = run(&args);
    assert_eq!(code, 2);
    assert!(errors.contains("--program-arg takes a value"), "{errors}");
}

#[test]
fn a_custom_command_needs_its_name() {
    let directory = tempfile::tempdir().unwrap();
    let args = write_inputs(directory.path(), &["-Didea.dev.mode.custom.command=true"], &[]);
    let (code, errors) = run(&args);
    assert_eq!(code, 1);
    assert!(errors.contains("needs the command as the first program argument"), "{errors}");
}

#[test]
fn jvm_args_refuses_a_relative_home() {
    let directory = tempfile::tempdir().unwrap();
    let mut args = write_inputs(directory.path(), &[], &[]);
    args[1] = OsString::from("--home=ide_home");
    let (code, errors) = run(&args);
    assert_eq!(code, 2);
    assert!(errors.contains("--home must be an absolute path"), "{errors}");
}

#[test]
fn an_argument_is_quoted_only_when_java_needs_it() {
    assert_eq!(quote_argument("-Da=b"), "-Da=b");
    assert_eq!(quote_argument(""), "\"\"");
    assert_eq!(quote_argument("-Da=x y"), "\"-Da=x y\"");
    assert_eq!(quote_argument("-Da=\"\""), "\"-Da=\\\"\\\"\"");
    assert_eq!(quote_argument("-Da=p\\q#r"), "\"-Da=p\\\\q#r\"");
}

#[test]
fn a_windows_path_is_quoted_with_each_backslash_escaped() {
    // Inside double quotes, `java` reads `\\` as one backslash, and `\t` or `\n` as a control character.
    assert_eq!(
        quote_argument(r"-Didea.home.path=C:\Program Files\idea"),
        r#""-Didea.home.path=C:\\Program Files\\idea""#
    );
    assert_eq!(quote_argument(r"-Dx=C:\temp\new\table"), r#""-Dx=C:\\temp\\new\\table""#);
    assert_eq!(quote_argument(r"-Dx=C:\dir\"), r#""-Dx=C:\\dir\\""#);
    assert_eq!(quote_argument(r"-Dx=\\?\C:\dir"), r#""-Dx=\\\\?\\C:\\dir""#);
    assert_eq!(
        quote_argument(r"C:\home dir\lib\a.jar;C:/out/lib/b.jar"),
        r#""C:\\home dir\\lib\\a.jar;C:/out/lib/b.jar""#
    );
    // A backslash alone is a reason to quote. A drive path with forward slashes and no space stays as it is.
    assert_eq!(quote_argument(r"-Dx=C:\dev\idea"), r#""-Dx=C:\\dev\\idea""#);
    assert_eq!(quote_argument("-Dx=C:/dev/idea"), "-Dx=C:/dev/idea");
}
