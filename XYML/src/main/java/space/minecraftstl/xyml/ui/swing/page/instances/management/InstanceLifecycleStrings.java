/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2026 huangyuhui <huanghongxun2008@126.com> and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package space.minecraftstl.xyml.ui.swing.page.instances.management;

import org.jetbrains.annotations.NotNullByDefault;

import java.util.Objects;

import static space.minecraftstl.xyml.util.i18n.I18n.i18n;

/// Immutable visible text used by the instance lifecycle page and its native dialogs.
///
/// Production construction uses established launcher localization keys. `english` is intentionally
/// deterministic for focused Swing tests and does not depend on the process locale.
///
/// @param title page title
/// @param instanceNameLabel current instance label
/// @param renameAction rename command label
/// @param duplicateAction duplicate command label
/// @param deleteAction delete command label
/// @param renamePrompt native rename input prompt
/// @param duplicatePrompt native duplicate input prompt
/// @param duplicateSavesLabel duplicate-worlds checkbox label
/// @param duplicateConfirmation visible duplicate consequence text
/// @param deleteConfirmation destructive deletion confirmation text pattern
/// @param renameFailure visible rename failure detail
/// @param duplicateFailure visible duplicate failure detail
/// @param deleteFailure visible delete failure detail
/// @param workingStatus in-progress status
/// @param successStatus completed status
@NotNullByDefault
public record InstanceLifecycleStrings(
        String title,
        String instanceNameLabel,
        String renameAction,
        String duplicateAction,
        String deleteAction,
        String renamePrompt,
        String duplicatePrompt,
        String duplicateSavesLabel,
        String duplicateConfirmation,
        String deleteConfirmation,
        String renameFailure,
        String duplicateFailure,
        String deleteFailure,
        String workingStatus,
        String successStatus) {
    /// Validates that all visible strings are non-null and non-blank.
    ///
    /// @param title page title
    /// @param instanceNameLabel current instance label
    /// @param renameAction rename command label
    /// @param duplicateAction duplicate command label
    /// @param deleteAction delete command label
    /// @param renamePrompt native rename input prompt
    /// @param duplicatePrompt native duplicate input prompt
    /// @param duplicateSavesLabel duplicate-worlds checkbox label
    /// @param duplicateConfirmation visible duplicate consequence text
    /// @param deleteConfirmation destructive deletion confirmation text pattern
    /// @param renameFailure visible rename failure detail
    /// @param duplicateFailure visible duplicate failure detail
    /// @param deleteFailure visible delete failure detail
    /// @param workingStatus in-progress status
    /// @param successStatus completed status
    public InstanceLifecycleStrings {
        requireNonBlank(title, "title");
        requireNonBlank(instanceNameLabel, "instanceNameLabel");
        requireNonBlank(renameAction, "renameAction");
        requireNonBlank(duplicateAction, "duplicateAction");
        requireNonBlank(deleteAction, "deleteAction");
        requireNonBlank(renamePrompt, "renamePrompt");
        requireNonBlank(duplicatePrompt, "duplicatePrompt");
        requireNonBlank(duplicateSavesLabel, "duplicateSavesLabel");
        requireNonBlank(duplicateConfirmation, "duplicateConfirmation");
        requireNonBlank(deleteConfirmation, "deleteConfirmation");
        requireNonBlank(renameFailure, "renameFailure");
        requireNonBlank(duplicateFailure, "duplicateFailure");
        requireNonBlank(deleteFailure, "deleteFailure");
        requireNonBlank(workingStatus, "workingStatus");
        requireNonBlank(successStatus, "successStatus");
    }

    /// Returns production localized text sourced from the former management workflow keys.
    ///
    /// @return current-locale lifecycle strings
    public static InstanceLifecycleStrings localized() {
        return new InstanceLifecycleStrings(
                i18n("instance.manage.manage"),
                i18n("instance.name"),
                i18n("instance.manage.rename"),
                i18n("instance.manage.duplicate"),
                i18n("instance.manage.remove"),
                i18n("instance.manage.rename.message"),
                i18n("instance.manage.duplicate.prompt"),
                i18n("instance.manage.duplicate.duplicate_save"),
                i18n("instance.manage.duplicate.confirm"),
                i18n("instance.manage.remove.confirm.independent"),
                i18n("instance.manage.rename.fail"),
                i18n("instance.manage.duplicate.confirm"),
                i18n("instance.manage.remove.failed"),
                i18n("message.doing"),
                i18n("message.success"));
    }

    /// Returns stable English text for deterministic component tests.
    ///
    /// @return English lifecycle strings
    public static InstanceLifecycleStrings english() {
        return new InstanceLifecycleStrings(
                "Instance management",
                "Instance name",
                "Rename instance",
                "Duplicate instance",
                "Delete instance",
                "Enter a new instance name",
                "Enter a new instance name",
                "Duplicate worlds",
                "The duplicate receives an isolated working directory and settings.",
                "Deleting this instance can also delete its worlds and other isolated data. Delete \"%s\"?",
                "The instance could not be renamed. Its name may be invalid or files may be in use.",
                "The instance could not be duplicated. Its name may already exist or files may be in use.",
                "The instance could not be deleted. Some files might be in use.",
                "Working...",
                "Completed");
    }

    /// Rejects missing visible text early so a native dialog never receives an ambiguous label.
    ///
    /// @param value candidate text
    /// @param name parameter name
    private static void requireNonBlank(String value, String name) {
        String candidate = Objects.requireNonNull(value, name);
        if (candidate.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
