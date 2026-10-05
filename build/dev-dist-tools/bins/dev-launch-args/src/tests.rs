use std::ffi::OsString;

use super::*;

#[test]
fn the_tool_takes_only_the_command_jvm_args() {
    for args in [vec![], vec!["prepare"], vec!["--ide-config=x"]] {
        let mut errors = Vec::new();
        let args: Vec<OsString> = args.iter().map(OsString::from).collect();
        let code = run(&args, &mut errors);
        let errors = String::from_utf8(errors).unwrap();
        assert_eq!(code, 2, "{args:?}");
        assert!(errors.contains("must be the command `jvm-args`"), "{errors}");
    }
}
