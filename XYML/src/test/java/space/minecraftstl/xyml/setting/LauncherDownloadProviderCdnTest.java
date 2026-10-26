// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.setting;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import space.minecraftstl.xyml.download.DownloadCandidate;
import space.minecraftstl.xyml.download.DownloadCandidates;
import space.minecraftstl.xyml.util.RegionUtils;

import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.*;

/// Verifies NeoForge and existing mirror policies without making network requests.
@NotNullByDefault
final class LauncherDownloadProviderCdnTest {
    /// Synthetic BMCLAPI root used only to verify URL rewriting.
    private static final String MIRROR = "https://mirror.example.invalid";
    /// Official release artifact under test.
    private static final String RELEASE = "https://maven.neoforged.net/releases/net/neoforged/neoforge/21.1.1/neoforge-21.1.1-installer.jar";
    /// Corresponding configured mirror artifact.
    private static final String BMCL_RELEASE = MIRROR + "/maven/net/neoforged/neoforge/21.1.1/neoforge-21.1.1-installer.jar";
    /// Corresponding CurseForge CDN artifact.
    private static final String CDN_RELEASE = "https://neoforged.forgecdn.net/releases/net/neoforged/neoforge/21.1.1/neoforge-21.1.1-installer.jar";

    /// Both priority orders keep the CDN last and retain the original candidate object.
    @Test
    void ordersReleaseCandidatesAndPreservesSource() {
        DownloadCandidate source = DownloadCandidate.of(RELEASE);
        @Unmodifiable List<DownloadCandidate> mirrorFirst = Objects.requireNonNull(
                LauncherDownloadProvider.neoForgeCandidates(source, true, MIRROR));
        assertEquals(List.of(BMCL_RELEASE, RELEASE, CDN_RELEASE), urls(mirrorFirst));
        assertSame(source, mirrorFirst.get(1));
        @Unmodifiable List<DownloadCandidate> officialFirst = Objects.requireNonNull(
                LauncherDownloadProvider.neoForgeCandidates(source, false, MIRROR));
        assertEquals(List.of(RELEASE, BMCL_RELEASE, CDN_RELEASE), urls(officialFirst));
        assertSame(source, officialFirst.get(0));
        assertThrows(UnsupportedOperationException.class, () -> mirrorFirst.add(source));
    }

    /// API and non-release paths gain a CDN fallback without inventing a BMCLAPI Maven path.
    @Test
    void handlesMetadataAndNonReleasePaths() {
        for (String path : List.of("api/maven/versions/releases/net/neoforged/neoforge",
                "api/maven/versions/releases/net/neoforged/forge", "snapshots/net/neoforged/example.jar")) {
            String official = "https://maven.neoforged.net/" + path;
            @Unmodifiable List<DownloadCandidate> candidates = Objects.requireNonNull(
                    LauncherDownloadProvider.neoForgeCandidates(DownloadCandidate.of(official), true, MIRROR));
            assertEquals(List.of(official, "https://neoforged.forgecdn.net/" + path), urls(candidates));
        }
    }

    /// Explicit mirror mode applies the same rule to artifact and version-list consumers.
    @Test
    void appliesConfiguredMirrorPolicies() {
        LauncherDownloadProvider provider = new LauncherDownloadProvider(MIRROR);
        provider.setFileSource(DownloadSource.MIRROR);
        provider.setVersionListSource(DownloadSource.MIRROR);
        assertEquals(List.of(BMCL_RELEASE, RELEASE, CDN_RELEASE), urls(provider.getDownloadCandidates(List.of(RELEASE))));
        assertEquals(List.of(BMCL_RELEASE, RELEASE, CDN_RELEASE), urls(provider.getVersionListCandidates(List.of(RELEASE))));
    }

    /// Official and automatic modes retain the current regional decision instead of forcing mirrors globally.
    @Test
    void preservesRegionalPreferences() {
        LauncherDownloadProvider provider = new LauncherDownloadProvider(MIRROR);
        provider.setFileSource(DownloadSource.OFFICIAL);
        assertEquals("CN".equals(RegionUtils.MACHINE_REGION)
                        ? List.of(RELEASE, BMCL_RELEASE, CDN_RELEASE) : List.of(RELEASE),
                urls(provider.getDownloadCandidates(List.of(RELEASE))));
        provider.setFileSource(DownloadSource.DEFAULT);
        assertEquals("CN".equals(RegionUtils.MACHINE_REGION)
                        ? List.of(BMCL_RELEASE, RELEASE, CDN_RELEASE) : List.of(RELEASE),
                urls(provider.getDownloadCandidates(List.of(RELEASE))));
    }

    /// Existing fallback-only and ordinary mirror rules keep their original ordering.
    @Test
    void preservesOtherServicePolicies() {
        LauncherDownloadProvider provider = new LauncherDownloadProvider(MIRROR);
        provider.setFileSource(DownloadSource.MIRROR);
        assertEquals(List.of("https://api.modrinth.com/v2/project/example",
                        "https://mod.mcimirror.top/modrinth/v2/project/example"),
                urls(provider.getDownloadCandidates(List.of("https://api.modrinth.com/v2/project/example"))));
        String official = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";
        assertEquals(List.of(MIRROR + "/mc/game/version_manifest_v2.json", official),
                urls(provider.getDownloadCandidates(List.of(official))));
    }

    /// Unrelated hosts and malformed candidates remain owned by the normal downloader error path.
    @Test
    void ignoresUnrelatedAndMalformedUrls() {
        for (String url : List.of("https://example.invalid/file.jar",
                "https://maven.neoforged.net.evil.invalid/releases/file.jar", "not a URL")) {
            assertNull(LauncherDownloadProvider.neoForgeCandidates(DownloadCandidate.of(url), true, MIRROR));
        }
        LauncherDownloadProvider provider = new LauncherDownloadProvider(MIRROR);
        provider.setFileSource(DownloadSource.MIRROR);
        DownloadCandidates invalid = provider.getDownloadCandidates(List.of("not a URL"));
        assertEquals(1, invalid.getCandidates().size());
        assertNull(invalid.getPrimaryCandidate().url());
    }

    /// Extracts URLs from one immutable provider result.
    private static @Unmodifiable List<String> urls(DownloadCandidates candidates) {
        return urls(candidates.getCandidates());
    }

    /// Extracts source URLs without issuing requests.
    private static @Unmodifiable List<String> urls(List<DownloadCandidate> candidates) {
        return candidates.stream().map(candidate -> Objects.requireNonNull(candidate.url()).href()).toList();
    }
}
