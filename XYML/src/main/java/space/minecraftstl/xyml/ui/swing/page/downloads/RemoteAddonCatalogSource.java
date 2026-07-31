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
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.addon.RemoteAddon;
import space.minecraftstl.xyml.addon.RemoteAddonRepository;
import space.minecraftstl.xyml.addon.repository.CurseForgeRemoteAddonRepository;

import java.util.List;
import java.util.Objects;

/// Selectable Core sources for native remote add-on acquisition categories.
///
/// Selecting a source updates local criteria and may asynchronously refresh its category metadata
/// after display; project discovery still requires an explicit catalog search or page command.
@NotNullByDefault
public enum RemoteAddonCatalogSource {
    /// Modrinth's public catalog, available without launcher-specific credentials.
    MODRINTH("Modrinth", RemoteAddon.Source.MODRINTH),

    /// CurseForge's catalog, available only when the launcher has a configured API key.
    CURSEFORGE("CurseForge", RemoteAddon.Source.CURSEFORGE);

    /// Human-readable provider label rendered in the selector and result rows.
    private final String displayName;

    /// Core source holding category-specific repository instances.
    private final RemoteAddon.Source source;

    /// Creates one local provider option.
    ///
    /// @param displayName visible provider name
    /// @param source Core source supplying typed repositories
    RemoteAddonCatalogSource(String displayName, RemoteAddon.Source source) {
        this.displayName = Objects.requireNonNull(displayName, "displayName");
        this.source = Objects.requireNonNull(source, "source");
    }

    /// Returns whether this provider can receive requests under the current launcher configuration.
    ///
    /// @return true when Core can use this provider without absent credentials
    public boolean isAvailable() {
        return this != CURSEFORGE || CurseForgeRemoteAddonRepository.isAvailable();
    }

    /// Returns whether this provider exposes a repository for the requested category.
    ///
    /// @param kind requested acquisition category
    /// @return true when a matching Core repository exists
    public boolean supports(RemoteAddonCatalogKind kind) {
        Objects.requireNonNull(kind, "kind");
        return source.getRepoForType(kind.repositoryType()) != null;
    }

    /// Returns only result orderings that the selected provider maps to distinct server behavior.
    ///
    /// Modrinth maps name and author to the same relevance index as popularity, so those misleading
    /// duplicates are omitted while CurseForge exposes every Core sort field directly.
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

    /// Resolves the exact Core repository for one supported category.
    ///
    /// @param kind requested acquisition category
    /// @return non-null provider-specific Core repository
    /// @throws IllegalArgumentException when the provider does not support the category
    public RemoteAddonRepository repository(RemoteAddonCatalogKind kind) {
        RemoteAddonCatalogKind requestedKind = Objects.requireNonNull(kind, "kind");
        @Nullable RemoteAddonRepository repository = source.getRepoForType(requestedKind.repositoryType());
        if (repository == null) {
            throw new IllegalArgumentException("Source " + name() + " does not support " + requestedKind.name());
        }
        return repository;
    }

    /// Returns the visible source name.
    ///
    /// @return non-blank source label
    public String displayName() {
        return displayName;
    }

    /// Returns the Core source identifier retained by result and version values.
    ///
    /// @return non-null Core source
    public RemoteAddon.Source coreSource() {
        return source;
    }

    /// Renders the provider selector with its visible source name.
    ///
    /// @return non-blank source label
    @Override
    public String toString() {
        return displayName;
    }
}
