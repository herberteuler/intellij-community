//! A worker's writable storage, the Linux boot's two guest-agent verbs, and the readiness a run needs.

use std::time::Duration;

use avl_base::format::words;
use avl_base::{Backend, Exit, Refusal, Reporter, Scope};
use avl_wire::verb::AgentVerb;
use serde::Deserialize;
use serde_json::value::RawValue;

use super::agent::BazelHost;
use super::sshkeys::PeerChannels;
use super::supervisor::AgentAccount;
use super::{GUEST_COMMAND_TIMEOUT, Guest, guest_join};
use crate::proc::SpawnOptions;

/// The timeout of `provision-guest`. It installs the guest packages with the package manager of the image, which
/// downloads them on a first boot.
const PROVISION_TIMEOUT: Duration = Duration::from_mins(30);

/// The timeout of `validate-guest`. It checks the display, the packages and the accounts, seconds of work.
const VALIDATE_TIMEOUT: Duration = Duration::from_mins(5);

/// The timeout of the recursive `chown` of the data root. The root holds the staged runtimes, so the walk visits
/// every file of them.
const DATA_CHOWN_TIMEOUT: Duration = Duration::from_mins(10);

/// The timeout of the storage script. It grows the APFS container of the data volume with `diskutil`.
const STORAGE_INIT_TIMEOUT: Duration = Duration::from_mins(10);

#[cfg(test)]
mod tests;

/// What the two backend-specific guest-agent verbs of a Linux boot are told, both composed by the caller.
///
/// Values and not the verbs. The verb names are this controller's protocol with its own agent, [`AgentVerb`], and
/// are named at the invocations below; what the caller supplies is the half only the Linux backend knows - the
/// package list, the display, the guest paths - which is why [`super::linux`] builds these two and this module
/// decides when each runs. The Node pair is not here: its values are the controller's own, and it runs later in the boot.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct LinuxProvisioning {
    /// What `provision-guest` is told, positionally: the data directory, the display, the worker user, the share
    /// mount point, and then the package list.
    ///
    /// `None` on a Docker worker, and only there. Its image installs the same package list at build time, and its
    /// entrypoint starts the display and the window manager, so the verb has nothing left to do. `validate-guest`
    /// still runs, because the image is exactly what it exists to doubt.
    pub provision_argv: Option<Vec<String>>,
    /// What `validate-guest` is told. Not optional: a provisioned worker that was never asked to prove itself is
    /// the state two real gaps were found in, months after the boot that introduced them, by reading `idea.log`.
    pub validate_argv: Vec<String>,
}

/// Puts one boot verb's own report where a person can read it, and is the only thing that does.
///
/// The verbs answer a document rather than lines, and their streams do not travel: [`Guest::invoke_agent`] captures
/// both and echoes neither, deliberately - a first boot's `dpkg-query` probe writes one line per package it is about
/// to install, which is the noise the reports exist instead of. So the fields that argue for their own visibility -
/// the display's and the window manager's probe counts, the installed/present split - reach a boot's log through
/// here.
///
/// The `data` half whole, with no struct for it on this side: a copy of the guest's report types here would be the
/// declaration that goes quietly stale, dropping a renamed field out of a note rather than failing to compile.
///
/// A success this cannot read is not an error. The verb exited 0 and that is the contract; this is a progress line.
fn note_verb_report(reporter: &Reporter, worker: &str, verb: AgentVerb, reply: &str) {
    #[derive(Deserialize)]
    struct Reply {
        /// A `data: null` is noted as what it said; only an absent `data` is nothing to note.
        #[serde(default, deserialize_with = "present")]
        data: Option<Box<RawValue>>,
    }
    fn present<'de, D: serde::Deserializer<'de>>(deserializer: D) -> Result<Option<Box<RawValue>>, D::Error> {
        Box::<RawValue>::deserialize(deserializer).map(Some)
    }
    let Some(data) = serde_json::from_str::<Reply>(reply).ok().and_then(|reply| reply.data) else {
        return;
    };
    reporter.note(format!("{verb} reported {}", data.get()), Some(&Scope::worker(worker)));
}

impl Guest<'_> {
    /// Runs one guest-agent verb of a boot and notes what it answered. The one spelling of "run a boot verb", so no
    /// verb can be the one whose report is dropped.
    ///
    /// The account is the caller's, because the boot's verbs do not agree about it: the pair that installs packages
    /// must be root, and the pair that extracts an archive into the worker's own data directory must be the worker.
    pub(crate) async fn invoke_boot_verb(
        &self,
        account: AgentAccount,
        verb: AgentVerb,
        args: &[String],
        timeout: Duration,
    ) -> Result<(), Refusal> {
        let reply = self.invoke_agent(account, verb, args, &SpawnOptions::within(timeout)).await?;
        note_verb_report(self.reporter, self.worker(), verb, &reply);
        Ok(())
    }

    /// Brings a Linux worker to the state a run needs, on every boot.
    ///
    /// Two guest-agent verbs rather than a sequence of execs from here, because `provision-guest` installs packages
    /// and enables services, and a half-applied sequence is much harder to reason about than one verb that is safe
    /// to re-run. It ends with the guest proving itself rather than with the installer exiting 0: everything
    /// `validate-guest` checks was once discovered from `idea.log` days later.
    ///
    /// Every verb goes through [`Guest::invoke_agent`] rather than [`Guest::as_root`], which is why each answers a
    /// named refusal instead of an exit integer: [`Guest::raw`] withholds a guest's output by contract, and the agent's
    /// envelope is carried whole. Both run as root: `provision-guest` refuses any other effective uid.
    ///
    /// It *begins* with [`Guest::install_agent`], because every step after it is a verb of that binary. The agent's
    /// destination lives under the state directory created just before it, so the install cannot come earlier
    /// either. That ordering is also this boot's trust boundary: the worker account writes the binary and root then
    /// executes it, on a guest that is disposable and holds no credentials.
    ///
    /// **The worker's Node is not staged here, and it cannot be**: see [`Guest::ensure_guest_node`].
    pub async fn provision_linux(&self, bazel: &dyn BazelHost, provisioning: &LinuxProvisioning) -> Result<(), Refusal> {
        // Refused before the guest is touched rather than skipped, because a skipped self-check is invisible in
        // exactly the way the self-check exists to end - and not by letting `validate-guest` refuse an empty argv,
        // which would arrive after a first boot spent its minutes installing packages.
        if provisioning.validate_argv.is_empty() {
            return Err(Refusal::new(
                "linux_validation_unset",
                Exit::FAILURE,
                format!("worker {} was given no guest self-check to run after provisioning", self.worker()),
            ));
        }
        let settings = self.settings;
        let state = guest_join(&settings.vm_data, "state");
        self.as_root(&words(["/bin/mkdir", "-p", &state]), &SpawnOptions::within(GUEST_COMMAND_TIMEOUT))
            .await?;
        // Root makes the directory; the worker user writes into it.
        self.as_root(
            &words([settings.guest.chown, "-R", &settings.vm_user, &settings.vm_data]),
            &SpawnOptions::within(DATA_CHOWN_TIMEOUT),
        )
        .await?;
        self.install_agent(bazel).await?;
        if let Some(provision_argv) = &provisioning.provision_argv {
            self.invoke_boot_verb(AgentAccount::Root, AgentVerb::ProvisionGuest, provision_argv, PROVISION_TIMEOUT)
                .await?;
        } else {
            self.reporter.note(
                format!(
                    "the image and its entrypoint own the display; skipping {}",
                    AgentVerb::ProvisionGuest
                ),
                Some(&self.scope()),
            );
        }
        // Only after provisioning returned: an installer that failed leaves nothing to validate, and a validator run
        // against it would report the *display* as the problem when the cause was an `apt-get` that never finished.
        self.invoke_boot_verb(
            AgentAccount::Root,
            AgentVerb::ValidateGuest,
            &provisioning.validate_argv,
            VALIDATE_TIMEOUT,
        )
        .await
    }

    /// Grows a freshly cloned macOS worker's APFS container to its root-disk size and creates the directories it
    /// owns. Runs once per boot, before anything else touches the guest's filesystem; the guest script is idempotent.
    ///
    /// `root_disk_gb` is the worker's *actual* disk and not the configured default for a new one: the script's
    /// safety gate is that `/` is a single internal APFS store of exactly the size it was told.
    pub async fn ensure_worker_storage(&self, root_disk_gb: u32) -> Result<(), Refusal> {
        let settings = self.settings;
        let bytes = (u64::from(root_disk_gb) * 1_000_000_000).to_string();
        self.as_root(
            &words([
                "/usr/local/sbin/air-init-worker-storage",
                &bytes,
                &settings.vm_data,
                &settings.vm_user,
            ]),
            &SpawnOptions::within(STORAGE_INIT_TIMEOUT),
        )
        .await?;
        if !self
            .succeeds(&words(["/bin/test", "-d", &settings.vm_data]), GUEST_COMMAND_TIMEOUT)
            .await
        {
            return Err(Refusal::new(
                "worker_storage_missing",
                Exit::FAILURE,
                format!("worker {} did not initialize {}", self.worker(), settings.vm_data),
            ));
        }
        self.remove_retired_build_directories().await;
        Ok(())
    }

    /// Deletes the guest-build era's directories from a worker, if its image still makes them.
    ///
    /// A worker has no checkout, no Bazel output root and no Bazel disk cache, but the sealed golden still carries
    /// an initializer that makes them. `rmdir`, never `rm -r`: a non-empty directory means something does use it,
    /// which is a discovery to make loudly later rather than a tree to delete now. Failures are ignored: this is
    /// tidying, and a worker that would not give up a directory is still a worker a run can use.
    async fn remove_retired_build_directories(&self) {
        for name in ["checkout", "bazel-output", "bazel-disk-cache"] {
            let path = guest_join(&self.settings.vm_data, name);
            if !self.succeeds(&words(["/bin/test", "-d", &path]), GUEST_COMMAND_TIMEOUT).await {
                continue;
            }
            if self.succeeds(&words(["/bin/rmdir", &path]), GUEST_COMMAND_TIMEOUT).await {
                self.reporter.note(
                    format!("removed the retired {name} directory from {}", self.worker()),
                    Some(&self.scope()),
                );
            }
        }
    }

    /// Brings the guest to the state a run needs: its writable storage, and the parity layout over the two
    /// read-only shares. One path for both backends, because everything below the mount behaves identically once
    /// the shares are there.
    ///
    /// Tart plus macOS adds the SSH identity check: two macOS workers are clones of one sealed image, and `tart exec`
    /// reaches the guest over SSH. A Linux worker has the same cloned keys but nothing here reaches it over SSH, so
    /// regenerating keys there would be ceremony rather than a fix.
    pub async fn ensure_ready(&self, peers: &dyn PeerChannels) -> Result<(), Refusal> {
        let settings = self.settings;
        if settings.backend == Backend::Tart && settings.is_macos_guest() {
            if !self
                .succeeds(&words(["/bin/test", "-d", &settings.vm_data]), GUEST_COMMAND_TIMEOUT)
                .await
            {
                return Err(Refusal::new(
                    "worker_storage_missing",
                    Exit::FAILURE,
                    format!(
                        "worker {} has no {}; its storage initializer did not run at boot",
                        self.worker(),
                        settings.vm_data
                    ),
                ));
            }
            self.ensure_unique_ssh_host_keys(peers).await?;
        }
        self.ensure_parity_ready().await?;
        let roots = [settings.vm_data.as_str(), &settings.vm_runs_root];
        let mut mkdir = words(["/bin/mkdir", "-p"]);
        mkdir.extend(roots.iter().map(|root| (*root).to_owned()));
        self.as_root(&mkdir, &SpawnOptions::within(GUEST_COMMAND_TIMEOUT)).await?;
        let mut chown = words([settings.guest.chown, &settings.vm_user]);
        chown.extend(roots.iter().map(|root| (*root).to_owned()));
        self.as_root(&chown, &SpawnOptions::within(GUEST_COMMAND_TIMEOUT)).await.map(drop)
    }
}
