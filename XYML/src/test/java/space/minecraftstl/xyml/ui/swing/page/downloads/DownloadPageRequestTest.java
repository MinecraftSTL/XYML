// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.downloads;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import space.minecraftstl.xyml.game.GameInstanceID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Verifies optional Mods queries without changing legacy category and instance requests.
@NotNullByDefault
final class DownloadPageRequestTest {
    /// Preserves category-only and explicit-instance construction without a query.
    @Test
    void preservesLegacyConstructors() {
        GameInstanceID instanceId = new GameInstanceID("instance");
        DownloadPageRequest request = new DownloadPageRequest(DownloadPageTarget.MODS, instanceId);
        assertEquals(instanceId, request.targetInstanceId());
        assertNull(request.searchText());
        for (DownloadPageTarget target : DownloadPageTarget.values()) {
            DownloadPageRequest plain = DownloadPageRequest.of(target);
            assertEquals(target, plain.target());
            assertNull(plain.targetInstanceId());
            assertNull(plain.searchText());
        }
    }

    /// Normalizes non-blank project names while retaining their casing and instance context.
    @Test
    void trimsProjectQueries() {
        GameInstanceID instanceId = new GameInstanceID("instance");
        for (String name : new String[] {"Litematica", "fabric-api", "中文模组"}) {
            DownloadPageRequest request = new DownloadPageRequest(DownloadPageTarget.MODS, instanceId, " " + name + "\t");
            assertEquals(name, request.searchText());
            assertEquals(instanceId, request.targetInstanceId());
        }
    }

    /// Rejects blank queries, non-Mods queries, and missing categories before navigation.
    @Test
    void rejectsInvalidQueries() {
        for (String blank : new String[] {"", " ", "\t\r\n"}) {
            assertThrows(IllegalArgumentException.class,
                    () -> new DownloadPageRequest(DownloadPageTarget.MODS, null, blank));
        }
        for (DownloadPageTarget target : DownloadPageTarget.values()) {
            if (target != DownloadPageTarget.MODS) {
                assertThrows(IllegalArgumentException.class,
                        () -> new DownloadPageRequest(target, null, "Litematica"));
            }
        }
        assertThrows(NullPointerException.class, () -> new DownloadPageRequest(null, null, "Litematica"));
    }
}
