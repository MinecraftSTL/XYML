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
package space.minecraftstl.xyml.ui.swing.page.instances.management.maintenance;

import org.jetbrains.annotations.NotNullByDefault;

import java.nio.file.Path;
import java.util.Objects;

import static space.minecraftstl.xyml.util.i18n.I18n.i18n;

/// Immutable visible text for instance launch, repair, and cleanup controls.
///
/// @param title page title
/// @param launchSection launch-related command group title
/// @param repairSection dependency repair command group title
/// @param cleanupSection destructive cleanup command group title
/// @param testLaunchAction test launch command
/// @param exportScriptAction standalone script command
/// @param updateModpackAction local modpack update command
/// @param updateModpackUrlAction remote modpack update command
/// @param redownloadAssetsAction forced asset repair command
/// @param removeAssetsAction shared asset removal command
/// @param removeLibrariesAction shared library removal command
/// @param cleanGeneratedFilesAction generated diagnostic cleanup command
/// @param loadingStatus initial snapshot-loading status
/// @param readyStatus idle status
/// @param workingStatus active operation status
/// @param successStatus generic successful operation status
/// @param cancelledStatus cancelled operation status
/// @param failedStatus generic failed operation status
/// @param updateUnavailableStatus non-modpack update explanation
/// @param sharedDataWarning warning used before deleting shared repository data
/// @param permanentRemovalWarning irreversible deletion warning
/// @param testLaunchStartedStatus test launch preparation status
/// @param scriptSuccessPattern successful script path pattern
@NotNullByDefault
public record InstanceMaintenanceStrings(
        String title,
        String launchSection,
        String repairSection,
        String cleanupSection,
        String testLaunchAction,
        String exportScriptAction,
        String updateModpackAction,
        String updateModpackUrlAction,
        String redownloadAssetsAction,
        String removeAssetsAction,
        String removeLibrariesAction,
        String cleanGeneratedFilesAction,
        String loadingStatus,
        String readyStatus,
        String workingStatus,
        String successStatus,
        String cancelledStatus,
        String failedStatus,
        String updateUnavailableStatus,
        String sharedDataWarning,
        String permanentRemovalWarning,
        String testLaunchStartedStatus,
        String scriptSuccessPattern) {
    /// Validates every visible string before a native component or dialog can consume it.
    public InstanceMaintenanceStrings {
        requireNonBlank(title, "title");
        requireNonBlank(launchSection, "launchSection");
        requireNonBlank(repairSection, "repairSection");
        requireNonBlank(cleanupSection, "cleanupSection");
        requireNonBlank(testLaunchAction, "testLaunchAction");
        requireNonBlank(exportScriptAction, "exportScriptAction");
        requireNonBlank(updateModpackAction, "updateModpackAction");
        requireNonBlank(updateModpackUrlAction, "updateModpackUrlAction");
        requireNonBlank(redownloadAssetsAction, "redownloadAssetsAction");
        requireNonBlank(removeAssetsAction, "removeAssetsAction");
        requireNonBlank(removeLibrariesAction, "removeLibrariesAction");
        requireNonBlank(cleanGeneratedFilesAction, "cleanGeneratedFilesAction");
        requireNonBlank(loadingStatus, "loadingStatus");
        requireNonBlank(readyStatus, "readyStatus");
        requireNonBlank(workingStatus, "workingStatus");
        requireNonBlank(successStatus, "successStatus");
        requireNonBlank(cancelledStatus, "cancelledStatus");
        requireNonBlank(failedStatus, "failedStatus");
        requireNonBlank(updateUnavailableStatus, "updateUnavailableStatus");
        requireNonBlank(sharedDataWarning, "sharedDataWarning");
        requireNonBlank(permanentRemovalWarning, "permanentRemovalWarning");
        requireNonBlank(testLaunchStartedStatus, "testLaunchStartedStatus");
        requireNonBlank(scriptSuccessPattern, "scriptSuccessPattern");
    }

    /// Returns production text using existing launcher localization entries.
    ///
    /// @return current-locale maintenance strings
    public static InstanceMaintenanceStrings localized() {
        return new InstanceMaintenanceStrings(
                i18n("settings.game.management"),
                i18n("instance.launch"),
                i18n("instance.manage.redownload_assets_index"),
                i18n("instance.manage.clean"),
                i18n("instance.launch.test"),
                i18n("instance.launch_script"),
                i18n("instance.update"),
                i18n("modpack.choose.remote"),
                i18n("instance.manage.redownload_assets_index"),
                i18n("instance.manage.remove_assets"),
                i18n("instance.manage.remove_libraries"),
                i18n("instance.manage.clean"),
                i18n("message.doing"),
                i18n("message.success"),
                i18n("message.doing"),
                i18n("message.success"),
                i18n("message.cancelled"),
                i18n("message.failed"),
                i18n("modpack.unsupported"),
                i18n("settings.launcher.common_path.tooltip"),
                i18n("button.remove.confirm"),
                i18n("message.doing"),
                i18n("instance.launch_script.success", "%s"));
    }

    /// Returns deterministic English text for component tests.
    ///
    /// @return stable English strings
    public static InstanceMaintenanceStrings english() {
        return new InstanceMaintenanceStrings(
                "Maintenance",
                "Launch diagnostics",
                "Repair",
                "Cleanup",
                "Test launch",
                "Export launch script",
                "Update modpack",
                "Update modpack from URL",
                "Update game assets",
                "Delete all assets",
                "Delete all libraries",
                "Delete logs and crash reports",
                "Loading maintenance state...",
                "Ready",
                "Working...",
                "Completed",
                "Cancelled",
                "Operation failed",
                "Only installed modpacks can be updated from an archive.",
                "This directory is shared by every instance in the selected game directory.",
                "The files will be permanently removed and downloaded again when required.",
                "Preparing test launch...",
                "Exported launch script as %s.");
    }

    /// Formats one successful script destination.
    ///
    /// @param scriptFile exact generated local script path
    /// @return localized successful completion text
    public String scriptSuccess(Path scriptFile) {
        return String.format(scriptSuccessPattern, Objects.requireNonNull(scriptFile, "scriptFile"));
    }

    /// Rejects missing visible strings.
    ///
    /// @param value candidate visible string
    /// @param name diagnostic field name
    private static void requireNonBlank(String value, String name) {
        String candidate = Objects.requireNonNull(value, name);
        if (candidate.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
