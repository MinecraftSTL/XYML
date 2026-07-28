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

/// Localizable text used by one native remote acquisition catalog.
///
/// Keeping the state text explicit permits deterministic headless tests without relying on global
/// locale state, while the production factory reuses existing launcher translations.
///
/// @param pageTitle category-specific catalog heading
/// @param sourceLabel provider selector label
/// @param searchLabel project keyword label
/// @param gameVersionLabel optional Minecraft-version filter label
/// @param filterStrings shared category and sort filter text
/// @param versionLabel selected project-version label
/// @param searchAction explicit source search command
/// @param previousPageAction previous provider-page command
/// @param nextPageAction next provider-page command
/// @param installAction direct selected-instance installation command
/// @param initialStatus offline initial state text
/// @param loadingStatus source search state text
/// @param loadingVersionsStatus selected-project version state text
/// @param noVersionsStatus selected-project empty-version text
/// @param noResultsStatus empty search result text
/// @param sourceUnavailableStatus unavailable provider text
/// @param viewportUnavailableStatus absent measured viewport text
/// @param selectInstanceStatus unavailable selected-instance target text
/// @param preparingInstallStatus pre-task construction state text
/// @param installingStatus active download state text
/// @param installSucceededStatus terminal task success text
/// @param installFailedStatus terminal task failure text
/// @param searchFailedStatus source query failure text
/// @param versionLoadFailedStatus selected-project version failure text
@NotNullByDefault
public record RemoteAddonCatalogStrings(
        String pageTitle,
        String sourceLabel,
        String searchLabel,
        String gameVersionLabel,
        RemoteCatalogFilterStrings filterStrings,
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
        String selectInstanceStatus,
        String preparingInstallStatus,
        String installingStatus,
        String installSucceededStatus,
        String installFailedStatus,
        String searchFailedStatus,
        String versionLoadFailedStatus) {
    /// Rejects incomplete text bundles before a Swing panel can expose partial user-visible state.
    public RemoteAddonCatalogStrings {
        Objects.requireNonNull(pageTitle, "pageTitle");
        Objects.requireNonNull(sourceLabel, "sourceLabel");
        Objects.requireNonNull(searchLabel, "searchLabel");
        Objects.requireNonNull(gameVersionLabel, "gameVersionLabel");
        Objects.requireNonNull(filterStrings, "filterStrings");
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
        Objects.requireNonNull(selectInstanceStatus, "selectInstanceStatus");
        Objects.requireNonNull(preparingInstallStatus, "preparingInstallStatus");
        Objects.requireNonNull(installingStatus, "installingStatus");
        Objects.requireNonNull(installSucceededStatus, "installSucceededStatus");
        Objects.requireNonNull(installFailedStatus, "installFailedStatus");
        Objects.requireNonNull(searchFailedStatus, "searchFailedStatus");
        Objects.requireNonNull(versionLoadFailedStatus, "versionLoadFailedStatus");
    }

    /// Creates explicit English fallback text for one acquisition category and focused tests.
    ///
    /// @param kind category represented by the future panel
    /// @return immutable English catalog text
    public static RemoteAddonCatalogStrings english(RemoteAddonCatalogKind kind) {
        RemoteAddonCatalogKind selectedKind = Objects.requireNonNull(kind, "kind");
        String category = switch (selectedKind) {
            case MOD -> "mods";
            case RESOURCE_PACK -> "resource packs";
            case SHADER_PACK -> "shader packs";
            case WORLD -> "worlds";
        };
        boolean world = selectedKind == RemoteAddonCatalogKind.WORLD;
        return new RemoteAddonCatalogStrings(
                "Remote " + category,
                "Source",
                "Search",
                "Minecraft version",
                RemoteCatalogFilterStrings.english(),
                "Version",
                "Search",
                "Previous page",
                "Next page",
                world ? "Save archive as" : "Install to selected instance",
                "Search a source to browse " + category + ".",
                "Searching remote " + category + "...",
                "Loading selected project versions...",
                "This project has no installable versions.",
                "No projects matched this search.",
                "This source is unavailable until it is configured.",
                "Wait until the result list has a measured visible height.",
                world ? "Choose a destination to save the world archive." :
                        "Select an installed game instance before installing.",
                world ? "Preparing world download..." : "Preparing installation...",
                world ? "Downloading the world archive..." : "Installing to the selected instance...",
                world ? "World archive saved." : "Installation completed.",
                world ? "World archive download failed." : "Installation failed.",
                "Unable to search remote projects.",
                "Unable to load versions for this project.");
    }

    /// Resolves production catalog text from existing launcher translations.
    ///
    /// @param kind category represented by the future panel
    /// @return immutable text for the active launcher locale
    public static RemoteAddonCatalogStrings launcherLocalized(RemoteAddonCatalogKind kind) {
        RemoteAddonCatalogKind selectedKind = Objects.requireNonNull(kind, "kind");
        if (selectedKind == RemoteAddonCatalogKind.WORLD) {
            return new RemoteAddonCatalogStrings(
                    i18n("swing.remote_world.title"),
                    i18n("modpack.origin"),
                    i18n("search"),
                    "Minecraft",
                    RemoteCatalogFilterStrings.launcherLocalized(),
                    i18n("version"),
                    i18n("search"),
                    i18n("wizard.prev"),
                    i18n("wizard.next"),
                    i18n("button.save_as"),
                    i18n("swing.remote_world.initial"),
                    i18n("swing.remote_world.loading"),
                    i18n("swing.remote_world.loading_versions"),
                    i18n("swing.remote_world.no_versions"),
                    i18n("swing.remote_world.no_results"),
                    i18n("swing.remote_world.source_unavailable"),
                    i18n("swing.remote_world.viewport_unavailable"),
                    i18n("swing.remote_world.choose_destination"),
                    i18n("swing.remote_world.preparing"),
                    i18n("swing.remote_world.downloading"),
                    i18n("swing.remote_world.saved"),
                    i18n("swing.remote_world.failed"),
                    i18n("swing.remote_world.search_failed"),
                    i18n("swing.remote_world.version_failed"));
        }
        return new RemoteAddonCatalogStrings(
                i18n(selectedKind.titleKey()),
                i18n("modpack.origin"),
                i18n("search"),
                "Minecraft",
                RemoteCatalogFilterStrings.launcherLocalized(),
                i18n("version"),
                i18n("search"),
                i18n("wizard.prev"),
                i18n("wizard.next"),
                i18n("button.install"),
                i18n("download.failed.empty"),
                i18n("message.doing"),
                i18n("message.doing"),
                i18n("download.failed.empty"),
                i18n("download.failed.no_results_found"),
                i18n("download.curseforge.unavailable"),
                i18n("message.doing"),
                i18n("version.switch"),
                i18n("message.doing"),
                i18n("message.doing"),
                i18n("message.success"),
                i18n("message.failed"),
                i18n("download.failed.refresh"),
                i18n("download.failed.refresh"));
    }
}
