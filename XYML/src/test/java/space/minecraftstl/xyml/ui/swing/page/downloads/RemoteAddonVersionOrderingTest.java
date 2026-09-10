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
import org.junit.jupiter.api.Test;
import space.minecraftstl.xyml.addon.RemoteAddon;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/// Verifies stable game-version grouping, mod-version ordering, and recommendation selection.
@NotNullByDefault
final class RemoteAddonVersionOrderingTest {
    /// Exact game-version matches are shown first, with releases before previews and newer mod versions first.
    @Test
    void ordersByRequestedGameVersionThenModVersion() {
        RemoteAddon.Version oldGame = version("old", "2.0.0", "1.19.4", RemoteAddon.VersionType.Release);
        RemoteAddon.Version preview = version("preview", "9.0.0", "1.20.1", RemoteAddon.VersionType.Beta);
        RemoteAddon.Version release = version("release", "1.10.0", "1.20.1", RemoteAddon.VersionType.Release);

        @Unmodifiable List<RemoteAddon.Version> ordered = RemoteAddonVersionOrdering.order(
                List.of(oldGame, preview, release),
                "1.20.1");

        assertEquals(List.of(release, preview, oldGame), ordered);
        assertSame(release, RemoteAddonVersionOrdering.recommended(ordered, "1.20.1"));
    }

    /// A release remains recommended when no exact game-version filter was entered.
    @Test
    void recommendsNewestReleaseWithoutFilter() {
        RemoteAddon.Version beta = version("beta", "3.0.0", "1.21", RemoteAddon.VersionType.Beta);
        RemoteAddon.Version release = version("release", "1.0.0", "1.20.1", RemoteAddon.VersionType.Release);
        @Unmodifiable List<RemoteAddon.Version> ordered = RemoteAddonVersionOrdering.order(
                List.of(beta, release),
                "");

        assertSame(release, RemoteAddonVersionOrdering.recommended(ordered, ""));
    }

    /// Creates one minimal provider version for ordering assertions.
    private static RemoteAddon.Version version(
            String id,
            String modVersion,
            String gameVersion,
            RemoteAddon.VersionType type) {
        return new RemoteAddon.Version(
                () -> RemoteAddon.Source.MODRINTH,
                id,
                "fixture",
                id,
                modVersion,
                Instant.EPOCH,
                type,
                new RemoteAddon.File(Map.of(), "https://example.invalid/" + id, id + ".jar"),
                List.of(),
                List.of(gameVersion),
                List.of());
    }
}
