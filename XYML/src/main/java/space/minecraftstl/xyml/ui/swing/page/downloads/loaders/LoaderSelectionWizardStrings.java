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
package space.minecraftstl.xyml.ui.swing.page.downloads.loaders;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

import static space.minecraftstl.xyml.util.i18n.I18n.i18n;

/// Holds visible text for the embeddable loader-selection wizard.
///
/// Keeping this text independent from the panel makes headless interaction tests deterministic and
/// lets the production factory reuse the historical installer translations without introducing new
/// resource keys for the Swing migration.
///
/// @param pageTitle wizard heading
/// @param gameVersionLabel selected Minecraft-version label
/// @param loaderKindsLabel compatible loader-kind heading
/// @param selectedKindLabel selected loader-catalog label
/// @param loadVersionsAction explicit remote version-list refresh action
/// @param loadingVersionsAction disabled action text while the explicit refresh is active
/// @param versionListLabel remote version-list label
/// @param addAction selected-version add action
/// @param removeAction selected-loader removal action
/// @param selectedLoadersLabel current installation-order selection label
/// @param awaitingGameVersionStatus status before a base Minecraft version is selected
/// @param awaitingLoaderStatus status after a base Minecraft version is selected
/// @param loadingVersionsStatus status while one selected loader list refreshes
/// @param noVersionsStatus status after an explicit list refresh has no versions
/// @param selectVersionStatus status when a user must select a loaded version row
/// @param alreadySelectedStatus status for a duplicated selected loader kind
/// @param conflictStatus status for a matrix-incompatible loader selection
/// @param parentRequiredStatus status for API selections whose parent loader is absent
/// @param selectionAddedStatus status after a version enters the installation selection
/// @param selectionRemovedStatus status after a selected loader is removed
/// @param dependentSelectionStatus status when a parent loader still has a selected API child
/// @param loadFailedStatus status after an explicit remote refresh fails
/// @param emptySelectionSummary summary when the installation selection is empty
/// @param loaderNames localized names for every supported loader kind
@NotNullByDefault
public record LoaderSelectionWizardStrings(
        String pageTitle,
        String gameVersionLabel,
        String loaderKindsLabel,
        String selectedKindLabel,
        String loadVersionsAction,
        String loadingVersionsAction,
        String versionListLabel,
        String addAction,
        String removeAction,
        String selectedLoadersLabel,
        String awaitingGameVersionStatus,
        String awaitingLoaderStatus,
        String loadingVersionsStatus,
        String noVersionsStatus,
        String selectVersionStatus,
        String alreadySelectedStatus,
        String conflictStatus,
        String parentRequiredStatus,
        String selectionAddedStatus,
        String selectionRemovedStatus,
        String dependentSelectionStatus,
        String loadFailedStatus,
        String emptySelectionSummary,
        @Unmodifiable Map<GameLoaderKind, String> loaderNames) {
    /// Validates text completeness and snapshots the loader-name mapping.
    public LoaderSelectionWizardStrings {
        pageTitle = Objects.requireNonNull(pageTitle, "pageTitle");
        gameVersionLabel = Objects.requireNonNull(gameVersionLabel, "gameVersionLabel");
        loaderKindsLabel = Objects.requireNonNull(loaderKindsLabel, "loaderKindsLabel");
        selectedKindLabel = Objects.requireNonNull(selectedKindLabel, "selectedKindLabel");
        loadVersionsAction = Objects.requireNonNull(loadVersionsAction, "loadVersionsAction");
        loadingVersionsAction = Objects.requireNonNull(loadingVersionsAction, "loadingVersionsAction");
        versionListLabel = Objects.requireNonNull(versionListLabel, "versionListLabel");
        addAction = Objects.requireNonNull(addAction, "addAction");
        removeAction = Objects.requireNonNull(removeAction, "removeAction");
        selectedLoadersLabel = Objects.requireNonNull(selectedLoadersLabel, "selectedLoadersLabel");
        awaitingGameVersionStatus = Objects.requireNonNull(awaitingGameVersionStatus, "awaitingGameVersionStatus");
        awaitingLoaderStatus = Objects.requireNonNull(awaitingLoaderStatus, "awaitingLoaderStatus");
        loadingVersionsStatus = Objects.requireNonNull(loadingVersionsStatus, "loadingVersionsStatus");
        noVersionsStatus = Objects.requireNonNull(noVersionsStatus, "noVersionsStatus");
        selectVersionStatus = Objects.requireNonNull(selectVersionStatus, "selectVersionStatus");
        alreadySelectedStatus = Objects.requireNonNull(alreadySelectedStatus, "alreadySelectedStatus");
        conflictStatus = Objects.requireNonNull(conflictStatus, "conflictStatus");
        parentRequiredStatus = Objects.requireNonNull(parentRequiredStatus, "parentRequiredStatus");
        selectionAddedStatus = Objects.requireNonNull(selectionAddedStatus, "selectionAddedStatus");
        selectionRemovedStatus = Objects.requireNonNull(selectionRemovedStatus, "selectionRemovedStatus");
        dependentSelectionStatus = Objects.requireNonNull(dependentSelectionStatus, "dependentSelectionStatus");
        loadFailedStatus = Objects.requireNonNull(loadFailedStatus, "loadFailedStatus");
        emptySelectionSummary = Objects.requireNonNull(emptySelectionSummary, "emptySelectionSummary");
        EnumMap<GameLoaderKind, String> names = new EnumMap<>(GameLoaderKind.class);
        names.putAll(Objects.requireNonNull(loaderNames, "loaderNames"));
        for (GameLoaderKind kind : GameLoaderKind.values()) {
            if (!names.containsKey(kind)) {
                throw new IllegalArgumentException("loaderNames is missing " + kind);
            }
            names.put(kind, Objects.requireNonNull(names.get(kind), "loaderNames contains null"));
        }
        loaderNames = Map.copyOf(names);
    }

    /// Returns deterministic English text for focused Swing tests and independent embedding.
    ///
    /// @return complete English text bundle
    public static LoaderSelectionWizardStrings english() {
        return new LoaderSelectionWizardStrings(
                "Loader selection",
                "Minecraft version",
                "Compatible loaders",
                "Selected catalog",
                "Load versions",
                "Loading versions...",
                "Available versions",
                "Add selected loader",
                "Remove selected loader",
                "Selected loaders",
                "Select a Minecraft version to choose loaders.",
                "Choose a compatible loader catalog.",
                "Loading the selected loader versions...",
                "This loader has no available versions.",
                "Select a loaded loader version first.",
                "A version of this loader is already selected.",
                "This loader conflicts with the current selection.",
                "Select the required parent loader first.",
                "Loader added to the installation selection.",
                "Loader removed from the installation selection.",
                "Remove the dependent API selection first.",
                "Unable to load loader versions.",
                "No loaders selected.",
                englishLoaderNames());
    }

    /// Creates a production text bundle from existing historical installer keys.
    ///
    /// @return localized text for the active launcher locale
    public static LoaderSelectionWizardStrings launcherLocalized() {
        return new LoaderSelectionWizardStrings(
                i18n("install.installer.install_online"),
                i18n("install.installer.game"),
                i18n("settings.tabs.installers"),
                i18n("install.installer.choose", ""),
                i18n("install.installer.install_online"),
                i18n("message.doing"),
                i18n("install.installer.version", ""),
                i18n("button.install"),
                i18n("button.delete"),
                i18n("settings.tabs.installers"),
                i18n("install.select"),
                i18n("install.installer.choose", ""),
                i18n("message.doing"),
                i18n("download.failed.empty"),
                i18n("install.installer.choose", ""),
                i18n("install.installer.not_installed"),
                i18n("install.installer.incompatible", ""),
                i18n("install.installer.depend", ""),
                i18n("message.success"),
                i18n("button.delete"),
                i18n("install.installer.depend", ""),
                i18n("download.failed.refresh"),
                i18n("install.installer.not_installed"),
                launcherLoaderNames());
    }

    /// Returns the localized display name of one loader kind.
    ///
    /// @param kind loader kind to label
    /// @return non-blank localized loader name
    public String loaderName(GameLoaderKind kind) {
        return Objects.requireNonNull(loaderNames.get(Objects.requireNonNull(kind, "kind")),
                "loaderNames is missing kind");
    }

    /// Creates deterministic English fallback names for every kind.
    ///
    /// @return immutable complete name mapping
    private static @Unmodifiable Map<GameLoaderKind, String> englishLoaderNames() {
        EnumMap<GameLoaderKind, String> names = new EnumMap<>(GameLoaderKind.class);
        for (GameLoaderKind kind : GameLoaderKind.values()) {
            names.put(kind, kind.displayName());
        }
        return Map.copyOf(names);
    }

    /// Creates production names from the historical installer localization keys.
    ///
    /// @return immutable complete localized name mapping
    private static @Unmodifiable Map<GameLoaderKind, String> launcherLoaderNames() {
        return Map.ofEntries(
                Map.entry(GameLoaderKind.FORGE, i18n("install.installer.forge")),
                Map.entry(GameLoaderKind.NEOFORGE, i18n("install.installer.neoforge")),
                Map.entry(GameLoaderKind.LITELOADER, i18n("install.installer.liteloader")),
                Map.entry(GameLoaderKind.OPTIFINE, i18n("install.installer.optifine")),
                Map.entry(GameLoaderKind.FABRIC, i18n("install.installer.fabric")),
                Map.entry(GameLoaderKind.FABRIC_API, i18n("install.installer.fabric-api")),
                Map.entry(GameLoaderKind.QUILT, i18n("install.installer.quilt")),
                Map.entry(GameLoaderKind.QUILT_API, i18n("install.installer.quilt-api")),
                Map.entry(GameLoaderKind.LEGACY_FABRIC, i18n("install.installer.legacyfabric")),
                Map.entry(GameLoaderKind.LEGACY_FABRIC_API, i18n("install.installer.legacyfabric-api")),
                Map.entry(GameLoaderKind.CLEANROOM, i18n("install.installer.cleanroom")));
    }
}
