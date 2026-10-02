// The tests of the component home.

use std::fs;

use testkit::{TempDir, read_text, require_error, write_file};

use crate::home::write_component_home;
use crate::test_support::*;

#[test]
fn the_home_holds_every_entry_without_the_plugin_directory() {
    let directory = TempDir::new();
    let jar = directory.join("remainder/lib/plugin.jar");
    let tool = directory.join("remainder/bin/tool");
    let reused = directory.join("reused/intellij.plugin.core.jar");
    write_file(&jar, "plugin");
    write_file(&tool, "tool");
    write_file(&reused, "core");
    let manifest = with_entries(
        test_manifest("intellij.plugin"),
        vec![
            directory_entry("plugins/plugin", 0o755),
            sourced_entry("plugins/plugin/lib/plugin.jar", &jar),
            file_with_mode("plugins/plugin/bin/tool", &tool, true, None),
            sourced_entry("plugins/plugin/lib/modules/intellij.plugin.core.jar", &reused),
            link_entry("plugins/plugin/lib/current", "modules"),
        ],
    );
    let output = directory.path().join("out/plugin.home");
    write_component_home(&manifest, "plugins/plugin", &output).unwrap();
    assert_eq!(read_text(output.join("lib/plugin.jar")), "plugin");
    assert_eq!(read_text(output.join("lib/modules/intellij.plugin.core.jar")), "core");
    assert_eq!(read_text(output.join("lib/current/intellij.plugin.core.jar")), "core");
    assert_eq!(fs::read_link(output.join("lib/current")).unwrap().to_str(), Some("modules"));
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        assert_eq!(fs::metadata(output.join("bin/tool")).unwrap().permissions().mode() & 0o777, 0o755);
        assert_eq!(
            fs::metadata(output.join("lib/plugin.jar")).unwrap().permissions().mode() & 0o777,
            0o644
        );
    }
}

#[test]
fn an_entry_outside_the_plugin_directory_fails() {
    let directory = TempDir::new();
    let manifest = with_entries(
        test_manifest("intellij.plugin"),
        vec![sourced_entry("lib/util.jar", "out/util.jar")],
    );
    require_error(
        write_component_home(&manifest, "plugins/plugin", &directory.path().join("home")),
        "'lib/util.jar' of the component 'intellij.plugin' is not below plugins/plugin",
    );
}

#[test]
fn a_plugin_directory_that_is_no_plugin_directory_fails() {
    let directory = TempDir::new();
    let manifest = test_manifest("intellij.plugin");
    require_error(
        write_component_home(&manifest, "lib", &directory.path().join("home")),
        "'lib' is not `plugins/<directory>`",
    );
}
