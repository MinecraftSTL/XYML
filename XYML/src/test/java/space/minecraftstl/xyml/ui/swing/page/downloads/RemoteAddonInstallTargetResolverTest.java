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
import space.minecraftstl.xyml.game.GameInstanceID;

import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;

/// Verifies that legacy target resolvers cannot silently replace an explicit instance selection.
@NotNullByDefault
final class RemoteAddonInstallTargetResolverTest {
    /// Rejects explicit targets when an implementation exposes only launcher-global resolution.
    @org.junit.jupiter.api.Test
    void failsClosedForLegacyResolver() {
        RemoteAddonInstallTargetResolver legacyResolver = kind -> Optional.of(new RemoteAddonInstallTarget(
                kind,
                new GameInstanceID("global"),
                Path.of("target")));

        assertFalse(legacyResolver.isSelectionAvailable(
                RemoteAddonCatalogKind.MOD,
                new GameInstanceID("explicit")));
        assertFalse(legacyResolver.resolve(
                RemoteAddonCatalogKind.MOD,
                new GameInstanceID("explicit")).isPresent());
    }
}
