// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.download;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import space.minecraftstl.xyml.download.neoforge.NeoForgeRemoteVersion;

import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;

/// Verifies official-first NeoForge metadata failover without network access.
@NotNullByDefault
final class NeoForgeCdnCandidatesTest {
    /// The modern metadata URL uses the same API path on both hosts.
    @Test
    void modernMetadataHasCdnFallback() {
        assertEquals(List.of(NeoForgeRemoteVersion.META_URL.href(), NeoForgeRemoteVersion.MIRROR_META_URL.href()),
                new DownloadProvider().getNeoForgeVersionListCandidates().getCandidates().stream()
                        .map(candidate -> Objects.requireNonNull(candidate.url()).href()).toList());
        assertEquals("https://neoforged.forgecdn.net/api/maven/versions/releases/net/neoforged/neoforge",
                NeoForgeRemoteVersion.MIRROR_META_URL.href());
    }

    /// Legacy metadata retains its distinct artifact name and the same official-first order.
    @Test
    void legacyMetadataHasCdnFallback() {
        assertEquals(List.of(NeoForgeRemoteVersion.OLD_URL.href(), NeoForgeRemoteVersion.MIRROR_OLD_URL.href()),
                new DownloadProvider().getNeoForgeOldVersionListCandidates().getCandidates().stream()
                        .map(candidate -> Objects.requireNonNull(candidate.url()).href()).toList());
        assertEquals("https://neoforged.forgecdn.net/api/maven/versions/releases/net/neoforged/forge",
                NeoForgeRemoteVersion.MIRROR_OLD_URL.href());
    }
}
