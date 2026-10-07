//! Helpers the tests of this crate share.

use std::path::Path;

use avl_base::{Backend, Config, Environment, GuestOs, Selection};

use crate::interrupt::Interrupts;
use crate::proc::Runner;

/// A runner over this process's PATH and nothing else, registering with a service no signal reaches.
pub(crate) fn runner() -> Runner {
    Runner::new(
        [("PATH".to_owned(), std::env::var("PATH").unwrap_or_default())],
        Interrupts::detached(),
    )
}

/// Settings for one backend and guest over a fixed home, with `tart` and `prlctl` as the binaries.
pub(crate) fn settings(backend: Backend, guest_os: GuestOs) -> Config {
    let environment = Environment::from_pairs([("HOME", "/Users/air"), ("TART_BIN", "tart"), ("AIR_VM_PARALLELS_BIN", "prlctl")]);
    Config::load(Selection { backend, guest_os }, &environment, Path::new("/repo/scripts"))
        .unwrap_or_else(|refusal| panic!("the environment was refused: {refusal:?}"))
}

/// The backend of a test fixture for a guest OS. A macOS guest runs on Tart only. A Linux guest runs on Tart on a
/// Unix host, and on Docker on a Windows host, which drives only the Docker backend. So a test that needs a Tart
/// pool for a Linux guest is Unix only.
pub(crate) const fn fixture_backend(guest_os: GuestOs) -> Backend {
    match guest_os {
        GuestOs::Linux if cfg!(windows) => Backend::Docker,
        _ => Backend::Tart,
    }
}
