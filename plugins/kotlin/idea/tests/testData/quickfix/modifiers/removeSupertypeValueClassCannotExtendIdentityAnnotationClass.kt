// "Remove supertype" "true"
// WITH_STDLIB
// COMPILER_ARGUMENTS: -XXLanguage:+FullValueClasses
// K2_ERROR: EXTENDING_AN_ANNOTATION_CLASS_ERROR
// K2_ERROR: VALUE_CLASS_CANNOT_EXTEND_IDENTITY_CLASSES
// K2_ERROR: WRONG_MODIFIER_TARGET
// K2_AFTER_ERROR: WRONG_MODIFIER_TARGET
open annotation class IdentityClass

value class ExtendsIdentity(val value: Int) : <caret>IdentityClass()

// FUS_QUICKFIX_NAME: org.jetbrains.kotlin.idea.quickfix.RemoveSupertypeFix
