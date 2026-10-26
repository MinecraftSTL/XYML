// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.downloads;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.addon.RemoteAddon;
import space.minecraftstl.xyml.addon.RemoteAddonRepository;
import space.minecraftstl.xyml.addon.repository.CurseForgeRemoteAddonRepository;

import java.util.List;
import java.util.Objects;

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

    /// Returns every result ordering exposed by the shared catalog control.
    ///
    /// Modrinth maps name and author to its relevance endpoint internally, but keeping those
    /// choices visible preserves the complete catalog contract.
    ///
    /// @return immutable provider-supported result orderings
    public @Unmodifiable List<RemoteAddonRepository.SortType> supportedSortTypes() {
        return List.of(RemoteAddonRepository.SortType.values());
    }

    /// Returns the Core repository dedicated to remote modpack projects.
    ///
    /// @return non-null Core modpack repository
    public RemoteAddonRepository repository() {
        return Objects.requireNonNull(
                source.getRepository(),
                "Missing modpack repository for " + source);
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
