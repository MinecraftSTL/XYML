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
import space.minecraftstl.xyml.addon.RemoteAddon;
import space.minecraftstl.xyml.game.GameInstanceID;

import java.awt.Component;
import java.util.Objects;
import java.util.Optional;

/// Resolves a managed-directory or save-as destination without performing network I/O.
@NotNullByDefault
public interface RemoteAddonInstallTargetResolver {
    /// Returns a stable selected-instance target for the requested category when one is available.
    ///
    /// @param kind acquisition category to resolve
    /// @return selected target, or empty when no valid instance is selected
    Optional<RemoteAddonInstallTarget> resolve(RemoteAddonCatalogKind kind);

    /// Returns a stable target for one explicitly selected installed instance.
    ///
    /// Compatibility implementations must override this overload to support explicit instance targets.
    /// The default is deliberately fail-closed so a supplied instance identifier is never silently
    /// replaced by launcher-global selection. A null identifier denotes the absence of an installed-instance target.
    ///
    /// @param kind acquisition category to resolve
    /// @param targetInstanceId explicitly selected instance, or null when the category has no instance target
    /// @return selected target, or empty when the explicit instance cannot be used
    default Optional<RemoteAddonInstallTarget> resolve(
            RemoteAddonCatalogKind kind,
            @Nullable GameInstanceID targetInstanceId) {
        Objects.requireNonNull(kind, "kind");
        return Optional.empty();
    }

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

    /// Reports whether one explicit installed-instance selection can provide a target.
    ///
    /// Interactive non-instance resolvers must override this overload without opening their chooser.
    ///
    /// @param kind category whose target availability is required
    /// @param targetInstanceId explicitly selected instance, or null for a non-instance target
    /// @return true when an acquisition command may resolve the supplied selection
    default boolean isSelectionAvailable(
            RemoteAddonCatalogKind kind,
            @Nullable GameInstanceID targetInstanceId) {
        return Objects.requireNonNull(
                resolve(Objects.requireNonNull(kind, "kind"), targetInstanceId),
                "resolve returned null")
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

    /// Resolves the exact target for an explicitly selected installed instance and project version.
    ///
    /// Interactive categories may use the project, artifact filename, and owner only after the acquisition
    /// command. Direct-install implementations inherit delegation to [#resolve(RemoteAddonCatalogKind,
    /// GameInstanceID)] and therefore retain the caller's explicit instance identifier.
    ///
    /// @param kind selected catalog category
    /// @param targetInstanceId explicitly selected instance, or null for a non-instance target
    /// @param item selected remote project
    /// @param version exact selected remote version
    /// @param owner component owning any interactive target chooser
    /// @return selected target, or empty when no target is available or the user cancels
    default Optional<RemoteAddonInstallTarget> resolveSelection(
            RemoteAddonCatalogKind kind,
            @Nullable GameInstanceID targetInstanceId,
            RemoteAddonCatalogItem item,
            RemoteAddon.Version version,
            Component owner) {
        Objects.requireNonNull(item, "item");
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(owner, "owner");
        return Objects.requireNonNull(
                resolve(Objects.requireNonNull(kind, "kind"), targetInstanceId),
                "resolve returned null");
    }
}
