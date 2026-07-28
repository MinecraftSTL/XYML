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
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.addon.RemoteAddon;
import space.minecraftstl.xyml.addon.RemoteAddonRepository;
import space.minecraftstl.xyml.addon.repository.CurseForgeRemoteAddonRepository;

import java.util.List;

/// Selectable remote catalog backends that provide modpack-capable Core repositories.
///
/// Selection updates local criteria and may asynchronously refresh category metadata after display;
/// project discovery still requires an explicit Search or completed-page navigation command.
@NotNullByDefault
public enum RemoteModpackCatalogSource {
    /// Modrinth's public modpack project catalog.
    MODRINTH("Modrinth", RemoteAddon.Source.MODRINTH),

    /// CurseForge's modpack project catalog when the launcher has an API key.
    CURSEFORGE("CurseForge", RemoteAddon.Source.CURSEFORGE);

    /// Human-readable source name shown in the selector and result details.
    private final String displayName;

    /// Core source that owns the concrete modpack repository.
    private final RemoteAddon.Source source;

    /// Creates one local source-selection option.
    ///
    /// @param displayName visible source name
    /// @param source Core source providing the modpack repository
    RemoteModpackCatalogSource(String displayName, RemoteAddon.Source source) {
        this.displayName = displayName;
        this.source = source;
    }

    /// Returns whether the source can currently receive requests without missing configuration.
    ///
    /// @return true for Modrinth and for CurseForge when its API key is configured
    public boolean isAvailable() {
        return this != CURSEFORGE || CurseForgeRemoteAddonRepository.isAvailable();
    }

    /// Returns only result orderings that the selected provider maps to distinct server behavior.
    ///
    /// Modrinth maps name and author to relevance, so those duplicate controls are omitted while
    /// CurseForge exposes every Core sort field directly.
    ///
    /// @return immutable provider-supported result orderings
    public @Unmodifiable List<RemoteAddonRepository.SortType> supportedSortTypes() {
        if (this == MODRINTH) {
            return List.of(
                    RemoteAddonRepository.SortType.POPULARITY,
                    RemoteAddonRepository.SortType.DATE_CREATED,
                    RemoteAddonRepository.SortType.LAST_UPDATED,
                    RemoteAddonRepository.SortType.TOTAL_DOWNLOADS);
        }
        return List.of(RemoteAddonRepository.SortType.values());
    }

    /// Returns the Core repository dedicated to remote modpack projects.
    ///
    /// @return non-null Core modpack repository
    public RemoteAddonRepository repository() {
        return source.modpackRepo;
    }

    /// Returns the source name for visual rendering.
    ///
    /// @return non-blank visible source name
    public String displayName() {
        return displayName;
    }

    /// Returns the Core source enum for diagnostics and version objects.
    ///
    /// @return non-null Core source
    public RemoteAddon.Source coreSource() {
        return source;
    }

    /// Renders the source selector with its human-readable source name.
    ///
    /// @return non-blank visible source name
    @Override
    public String toString() {
        return displayName;
    }
}
