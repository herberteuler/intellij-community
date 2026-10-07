use pretty_assertions::assert_eq;

use super::{Arm, Gate};
use crate::bench::record::{EDITOR_HIGHLIGHTED, OPEN_HIGHLIGHTED, WELCOME_BECAME_VISIBLE};

#[test]
fn the_main_metric_of_a_welcome_arm_is_the_welcome_event() {
    assert_eq!(Arm::Modal.main_metric(), WELCOME_BECAME_VISIBLE);
    assert_eq!(Arm::NonModal.main_metric(), WELCOME_BECAME_VISIBLE);
    assert_eq!(Arm::OpenProject.main_metric(), OPEN_HIGHLIGHTED);
    assert_eq!(Arm::Project.main_metric(), EDITOR_HIGHLIGHTED);
}

#[test]
fn only_the_project_arm_skips_the_welcome_screen() {
    assert_eq!(Arm::Modal.gate(), Gate::Welcome { modal: true });
    assert_eq!(Arm::NonModal.gate(), Gate::Welcome { modal: false });
    assert_eq!(Arm::OpenProject.gate(), Gate::Welcome { modal: false });
    assert_eq!(Arm::Project.gate(), Gate::Highlighted);
}

#[test]
fn a_label_names_one_arm() {
    for arm in Arm::ALL {
        assert_eq!(Arm::from_label(arm.label()), Some(arm));
    }
    assert_eq!(Arm::from_label("projects"), None);
}
