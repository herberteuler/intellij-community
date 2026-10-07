//! The helpers that the suites of `bench` share: the fixture session of `testdata/bench` and its goldens.

use std::path::PathBuf;

/// The `testdata/bench` directory of this crate, under cargo and under Bazel.
pub(crate) fn testdata_dir() -> PathBuf {
    avl_testkit::crate_path!("testdata/bench")
}

/// A copy of the fixture session `testdata/bench/session` in a temporary directory, because `replay` writes into it.
pub(crate) fn fixture_session() -> (tempfile::TempDir, PathBuf) {
    let dir = tempfile::tempdir().expect("a temporary directory");
    let session = dir.path().join("session");
    avl_testkit::traces::copy_tree(&testdata_dir().join("session"), &session);
    (dir, session)
}

/// Holds `text` to the golden file `name` of `testdata/bench`. `UPDATE_EXPECT=1` rewrites it.
pub(crate) fn assert_golden(name: &str, text: &str) {
    let path = testdata_dir().join(name);
    // `expect_file!` reads a relative path from the workspace root.
    let path = std::path::absolute(&path).unwrap_or_else(|error| panic!("{}: {error}", path.display()));
    expect_test::expect_file![path].assert_eq(text);
}
