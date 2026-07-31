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
import space.minecraftstl.xyml.addon.RemoteAddon;

import java.awt.Component;
import java.util.Optional;
import java.util.Objects;

/// Resolves a managed-directory or save-as destination without performing network I/O.
@NotNullByDefault
public interface RemoteAddonInstallTargetResolver {
    /// Returns a stable selected-instance target for the requested category when one is available.
    ///
    /// @param kind acquisition category to resolve
    /// @return selected target, or empty when no valid instance is selected
    Optional<RemoteAddonInstallTarget> resolve(RemoteAddonCatalogKind kind);

    /// Reports whether the requested category can offer a target when the user starts acquisition.
    ///
    /// Implementations with an interactive chooser must override this method so routine control-state
    /// updates do not open a dialog. Directory-based implementations may retain the default snapshot.
    ///
    /// @param kind category whose target availability is required
    /// @return true when an acquisition command may ask this resolver for a target
    default boolean isSelectionAvailable(RemoteAddonCatalogKind kind) {
        return Objects.requireNonNull(resolve(Objects.requireNonNull(kind, "kind")), "resolve returned null")
                .isPresent();
    }

    /// Resolves the exact target after a project version has been selected by the user.
    ///
    /// Directory-based implementations retain the original category-only behavior. Interactive
    /// implementations can use the project, artifact filename, and owning component to show a
    /// save-as chooser only in direct response to the acquisition command.
    ///
    /// @param kind selected catalog category
    /// @param item selected remote project
    /// @param version exact selected remote version
    /// @param owner component owning any interactive target chooser
    /// @return selected target, or empty when no target is available or the user cancels
    default Optional<RemoteAddonInstallTarget> resolveSelection(
            RemoteAddonCatalogKind kind,
            RemoteAddonCatalogItem item,
            RemoteAddon.Version version,
            Component owner) {
        Objects.requireNonNull(item, "item");
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(owner, "owner");
        return Objects.requireNonNull(resolve(Objects.requireNonNull(kind, "kind")), "resolve returned null");
    }
}
