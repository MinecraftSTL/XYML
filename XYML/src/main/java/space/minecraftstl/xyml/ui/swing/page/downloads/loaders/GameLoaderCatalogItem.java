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
import space.minecraftstl.xyml.download.RemoteVersion;

import java.util.Objects;

/// Pairs a selected loader kind with its exact Core remote version instance.
///
/// The concrete [RemoteVersion] is intentionally retained rather than converted into a display-only
/// DTO, so a later installer task can use the original subtype and metadata without re-querying.
///
/// @param kind loader catalog that produced the version
/// @param remoteVersion original Core remote version object
@NotNullByDefault
public record GameLoaderCatalogItem(GameLoaderKind kind, RemoteVersion remoteVersion) {
    /// Validates source provenance while preserving remote-version identity.
    public GameLoaderCatalogItem {
        kind = Objects.requireNonNull(kind, "kind");
        remoteVersion = Objects.requireNonNull(remoteVersion, "remoteVersion");
    }

    /// Returns concise display text without losing the original remote version.
    ///
    /// @return stable loader name and remote self version
    public String displayText() {
        return kind.displayName() + " " + remoteVersion.getSelfVersion();
    }
}
