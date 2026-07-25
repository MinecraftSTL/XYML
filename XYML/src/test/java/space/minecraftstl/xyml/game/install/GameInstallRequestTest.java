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
package space.minecraftstl.xyml.game.install;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import space.minecraftstl.xyml.download.RemoteVersion;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests immutable ordered loader selections on [GameInstallRequest].
@NotNullByDefault
public final class GameInstallRequestTest {
    /// Request construction snapshots ordered installers without collapsing equal version text.
    @Test
    public void selectedRemoteVersionsAreImmutableAndKeepEqualVersionLoaders() {
        RemoteVersion forge = remoteVersion("forge", "47.2.0");
        RemoteVersion fabric = remoteVersion("fabric", "47.2.0");
        List<RemoteVersion> submitted = new ArrayList<>(List.of(forge, fabric));

        GameInstallRequest request = new GameInstallRequest("loader-instance", "1.21.1", submitted);
        submitted.clear();

        @Unmodifiable List<RemoteVersion> selected = request.selectedRemoteVersions();
        assertEquals(2, selected.size());
        assertSame(forge, selected.get(0));
        assertSame(fabric, selected.get(1));
        assertThrows(UnsupportedOperationException.class, () -> selected.add(forge));
    }

    /// The original two-argument constructor still creates a vanilla-only request.
    @Test
    public void twoArgumentConstructorCreatesVanillaOnlyRequest() {
        GameInstallRequest request = new GameInstallRequest("vanilla-instance", "1.21.1");

        assertTrue(request.selectedRemoteVersions().isEmpty());
    }

    /// Creates minimal remote installer metadata for request tests.
    ///
    /// @param libraryId selected installer identifier
    /// @param selfVersion selected installer version text
    /// @return remote installer metadata
    private static RemoteVersion remoteVersion(String libraryId, String selfVersion) {
        return new RemoteVersion(libraryId, "1.21.1", selfVersion, Instant.EPOCH, List.of());
    }
}
