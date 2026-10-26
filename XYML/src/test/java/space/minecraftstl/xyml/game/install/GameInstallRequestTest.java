// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.game.install;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import space.minecraftstl.xyml.download.TestComponentRemoteVersion;
import space.minecraftstl.xyml.download.ComponentRemoteVersion;

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
        ComponentRemoteVersion forge = remoteVersion("forge", "47.2.0");
        ComponentRemoteVersion fabric = remoteVersion("fabric", "47.2.0");
        List<ComponentRemoteVersion> submitted = new ArrayList<>(List.of(forge, fabric));

        GameInstallRequest request = new GameInstallRequest("loader-instance", "1.21.1", submitted);
        submitted.clear();

        @Unmodifiable List<ComponentRemoteVersion> selected = request.selectedRemoteVersions();
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
    private static ComponentRemoteVersion remoteVersion(String libraryId, String selfVersion) {
        return new TestComponentRemoteVersion(libraryId, "1.21.1", selfVersion, Instant.EPOCH, List.of());
    }
}
