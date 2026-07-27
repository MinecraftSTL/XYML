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
import space.minecraftstl.xyml.game.XYMLGameRepository;
import space.minecraftstl.xyml.setting.GameDirectoryManager;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/// Resolves direct-install targets from the launcher-wide currently selected repository and instance.
///
/// The resolver only reads existing launcher state and computes paths. It never creates directories,
/// starts a repository refresh, or contacts a remote source while the catalog is merely displayed.
@NotNullByDefault
public final class LegacyRemoteAddonInstallTargetResolver implements RemoteAddonInstallTargetResolver {
    /// Resolves a selected direct-install target, returning empty for unavailable or stale launcher state.
    ///
    /// @param kind requested direct-install category
    /// @return selected immutable target, or empty without a usable selected instance
    @Override
    public Optional<RemoteAddonInstallTarget> resolve(RemoteAddonCatalogKind kind) {
        RemoteAddonCatalogKind requestedKind = Objects.requireNonNull(kind, "kind");
        try {
            XYMLGameRepository repository = GameDirectoryManager.getSelectedRepository();
            @Nullable String instanceId = repository.getSelectedInstance();
            if (instanceId == null || instanceId.isBlank() || !repository.hasVersion(instanceId)) {
                return Optional.empty();
            }
            if (requestedKind == RemoteAddonCatalogKind.WORLD) {
                return Optional.empty();
            }
            Path directory = switch (requestedKind) {
                case MOD -> repository.getModsDirectory(instanceId);
                case RESOURCE_PACK -> repository.getResourcePackDirectory(instanceId);
                case SHADER_PACK -> repository.getRunDirectory(instanceId).resolve("shaderpacks");
                case WORLD -> throw new IllegalStateException("World targets require an explicit save-as resolver");
            };
            return Optional.of(new RemoteAddonInstallTarget(
                    requestedKind,
                    instanceId,
                    directory));
        } catch (RuntimeException ignored) {
            return Optional.empty();
        }
    }
}
