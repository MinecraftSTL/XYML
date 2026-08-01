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
package space.minecraftstl.xyml.ui.swing.page.downloads;

import org.jetbrains.annotations.NotNullByDefault;

import java.util.Objects;

import static space.minecraftstl.xyml.util.i18n.I18n.i18n;

/// Localizable text shown by the standalone remote-modpack catalog.
///
/// The record keeps all user-visible catalog lifecycle text injectable so the panel can be tested
/// without relying on global locale initialization and later integrated with the launcher's locale source.
///
/// @param pageTitle page heading
/// @param sourceLabel provider selector label
/// @param searchLabel project filter label
/// @param gameVersionLabel optional game-version filter label
/// @param filterStrings shared category and sort filter text
/// @param instanceNameLabel destination identifier label
/// @param versionLabel selected project version label
/// @param searchAction explicit source-query command
/// @param previousPageAction previous server-page command
/// @param nextPageAction next server-page command
/// @param installAction selected-version installation command
/// @param initialStatus initial no-network status
/// @param loadingStatus source-query status
/// @param loadingVersionsStatus selected-project version status
/// @param noVersionsStatus selected-project no-version status
/// @param noResultsStatus empty-page status
/// @param sourceUnavailableStatus unavailable source status
/// @param viewportUnavailableStatus status when no measured result viewport exists yet
/// @param invalidInstanceNameStatus invalid destination status
/// @param preparingInstallStatus installation setup status
/// @param installingStatus active installation status
/// @param installSucceededStatus terminal installation success status
/// @param installFailedStatus terminal installation failure status
/// @param searchFailedStatus source-query failure status
/// @param versionLoadFailedStatus version-request failure status
@NotNullByDefault
public record RemoteModpackCatalogStrings(
        String pageTitle,
        String sourceLabel,
        String searchLabel,
        String gameVersionLabel,
        RemoteCatalogFilterStrings filterStrings,
        String instanceNameLabel,
        String versionLabel,
        String searchAction,
        String previousPageAction,
        String nextPageAction,
        String installAction,
        String initialStatus,
        String loadingStatus,
        String loadingVersionsStatus,
        String noVersionsStatus,
        String noResultsStatus,
        String sourceUnavailableStatus,
        String viewportUnavailableStatus,
        String invalidInstanceNameStatus,
        String preparingInstallStatus,
        String installingStatus,
        String installSucceededStatus,
        String installFailedStatus,
        String searchFailedStatus,
        String versionLoadFailedStatus) {
    /// Validates that every catalog surface has explicit text.
    public RemoteModpackCatalogStrings {
        Objects.requireNonNull(pageTitle, "pageTitle");
        Objects.requireNonNull(sourceLabel, "sourceLabel");
        Objects.requireNonNull(searchLabel, "searchLabel");
        Objects.requireNonNull(gameVersionLabel, "gameVersionLabel");
        Objects.requireNonNull(filterStrings, "filterStrings");
        Objects.requireNonNull(instanceNameLabel, "instanceNameLabel");
        Objects.requireNonNull(versionLabel, "versionLabel");
        Objects.requireNonNull(searchAction, "searchAction");
        Objects.requireNonNull(previousPageAction, "previousPageAction");
        Objects.requireNonNull(nextPageAction, "nextPageAction");
        Objects.requireNonNull(installAction, "installAction");
        Objects.requireNonNull(initialStatus, "initialStatus");
        Objects.requireNonNull(loadingStatus, "loadingStatus");
        Objects.requireNonNull(loadingVersionsStatus, "loadingVersionsStatus");
        Objects.requireNonNull(noVersionsStatus, "noVersionsStatus");
        Objects.requireNonNull(noResultsStatus, "noResultsStatus");
        Objects.requireNonNull(sourceUnavailableStatus, "sourceUnavailableStatus");
        Objects.requireNonNull(viewportUnavailableStatus, "viewportUnavailableStatus");
        Objects.requireNonNull(invalidInstanceNameStatus, "invalidInstanceNameStatus");
        Objects.requireNonNull(preparingInstallStatus, "preparingInstallStatus");
        Objects.requireNonNull(installingStatus, "installingStatus");
        Objects.requireNonNull(installSucceededStatus, "installSucceededStatus");
        Objects.requireNonNull(installFailedStatus, "installFailedStatus");
        Objects.requireNonNull(searchFailedStatus, "searchFailedStatus");
        Objects.requireNonNull(versionLoadFailedStatus, "versionLoadFailedStatus");
    }

    /// Returns explicit English fallback text for standalone use and focused tests.
    ///
    /// @return immutable English remote-catalog text
    public static RemoteModpackCatalogStrings english() {
        return new RemoteModpackCatalogStrings(
                "Remote modpacks",
                "Source",
                "Search",
                "Minecraft version",
                RemoteCatalogFilterStrings.english(),
                "Instance name",
                "Version",
                "Search",
                "Previous page",
                "Next page",
                "Install",
                "Search a source to browse modpacks.",
                "Searching remote modpacks...",
                "Loading selected modpack versions...",
                "This modpack has no installable versions.",
                "No modpacks matched this search.",
                "This source is unavailable until it is configured.",
                "Wait until the result list has a measured visible height.",
                "Enter a valid instance name.",
                "Preparing modpack installation...",
                "Installing modpack...",
                "Modpack installation completed.",
                "Modpack installation failed.",
                "Unable to search remote modpacks.",
                "Unable to load versions for this modpack.");
    }

    /// Returns catalog text resolved from the launcher's active locale.
    ///
    /// Existing launcher translations cover the shared commands and lifecycle messages. The two
    /// source-specific labels deliberately reuse established modpack terminology so this new page
    /// follows the rest of the launcher without adding a parallel translation namespace.
    ///
    /// @return immutable text for the active launcher locale
    public static RemoteModpackCatalogStrings launcherLocalized() {
        return new RemoteModpackCatalogStrings(
                i18n("modpack.download"),
                i18n("modpack.origin"),
                i18n("search"),
                "Minecraft",
                RemoteCatalogFilterStrings.launcherLocalized(),
                i18n("instance.name"),
                i18n("archive.version"),
                i18n("search"),
                i18n("wizard.prev"),
                i18n("wizard.next"),
                i18n("button.install"),
                i18n("modpack.choose.repository"),
                i18n("message.doing"),
                i18n("message.doing"),
                i18n("download.failed.empty"),
                i18n("search.no_results_found"),
                i18n("download.curseforge.unavailable"),
                i18n("message.doing"),
                i18n("modpack.enter_name"),
                i18n("modpack.installing"),
                i18n("modpack.installing"),
                i18n("message.success"),
                i18n("message.failed"),
                i18n("download.failed.refresh"),
                i18n("download.failed.refresh"));
    }
}
