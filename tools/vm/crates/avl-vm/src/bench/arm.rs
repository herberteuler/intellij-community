//! The arms of a session: the start-up variants that a session compares.

use serde::{Deserialize, Serialize};

use super::record;

/// One start-up variant. On the command line, an arm is its [`Arm::label`].
#[derive(Clone, Copy, Debug, PartialEq, Eq, PartialOrd, Ord, Hash, Serialize, Deserialize, clap::ValueEnum)]
#[serde(rename_all = "camelCase")]
pub(crate) enum Arm {
    /// The modal `WelcomeFrame`, forced with `idea.force.disable.non.modal.welcome.screen=true`.
    Modal,
    /// The non-modal welcome screen, the default of the product.
    NonModal,
    /// The non-modal welcome screen, then a project that the running IDE opens.
    OpenProject,
    /// A project that the command line of the IDE names, with no welcome screen.
    Project,
}

/// What a run of an arm must show to pass the gate.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum Gate {
    /// The FUS event `welcome.screen.became.visible` with this `is_modal`. A non-modal screen also needs the welcome
    /// project in `idea.log`.
    Welcome { modal: bool },
    /// The `editor highlighting completed` event in the trace or in the report.
    Highlighted,
}

impl Arm {
    /// The key in `summary.json` and in the `--json` envelope.
    pub(crate) const fn key(self) -> &'static str {
        match self {
            Self::Modal => "modal",
            Self::NonModal => "nonModal",
            Self::OpenProject => "openProject",
            Self::Project => "project",
        }
    }

    /// The prefix of the run directories and the name in the digest.
    pub(crate) const fn label(self) -> &'static str {
        match self {
            Self::Modal => "modal",
            Self::NonModal => "non-modal",
            Self::OpenProject => "open-project",
            Self::Project => "project",
        }
    }

    /// The metric that tells the time of the arm: the run line reports it, and it picks the median run of the arm.
    pub(crate) const fn main_metric(self) -> &'static str {
        match self {
            Self::Modal | Self::NonModal => record::WELCOME_BECAME_VISIBLE,
            Self::OpenProject => record::OPEN_HIGHLIGHTED,
            Self::Project => record::EDITOR_HIGHLIGHTED,
        }
    }

    /// The gate of a run of the arm. The launch waits for it, and the record checks it.
    pub(crate) const fn gate(self) -> Gate {
        match self {
            Self::Modal => Gate::Welcome { modal: true },
            Self::NonModal | Self::OpenProject => Gate::Welcome { modal: false },
            Self::Project => Gate::Highlighted,
        }
    }

    /// Every arm, in the order of the digest.
    pub(crate) const ALL: [Self; 4] = [Self::Modal, Self::NonModal, Self::OpenProject, Self::Project];

    /// The arm of a run directory label.
    pub(crate) fn from_label(label: &str) -> Option<Self> {
        Self::ALL.into_iter().find(|arm| arm.label() == label)
    }
}

#[cfg(test)]
mod tests;
