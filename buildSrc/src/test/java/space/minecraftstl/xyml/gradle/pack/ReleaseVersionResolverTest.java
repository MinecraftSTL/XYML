// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.gradle.pack;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Verifies exact version shape, fallback generation, and stable-prefix ownership for the release model.
@NotNullByDefault
final class ReleaseVersionResolverTest {
    /// Resolves the stable baseline without adding a build component.
    @Test
    void resolvesStableBaseline() {
        assertEquals("1.0.0", ReleaseVersionResolver.resolve(
                ReleaseType.STABLE, "1.0.0", null, null, true));
    }

    /// Generates each testing channel at its required decimal depth.
    @Test
    void derivesChannelVersionsFromBuildNumber() {
        assertEquals("1.0.0.7", ReleaseVersionResolver.resolve(
                ReleaseType.BETA, "1.0.0", null, "7", true));
        assertEquals("1.0.0.0.7", ReleaseVersionResolver.resolve(
                ReleaseType.ALPHA, "1.0.0", null, "7", true));
        assertEquals("1.0.0.0.0.7", ReleaseVersionResolver.resolve(
                ReleaseType.DEV, "1.0.0", null, "7", true));
    }

    /// Preserves explicit parent counters selected during promotion.
    @Test
    void acceptsExplicitHierarchicalVersions() {
        assertEquals("1.0.1.3", ReleaseVersionResolver.resolve(
                ReleaseType.BETA, "1.0.1", "1.0.1.3", null, true));
        assertEquals("1.0.1.3.2", ReleaseVersionResolver.resolve(
                ReleaseType.ALPHA, "1.0.1", "1.0.1.3.2", null, true));
        assertEquals("1.0.1.3.2.9", ReleaseVersionResolver.resolve(
                ReleaseType.DEV, "1.0.1", "1.0.1.3.2.9", null, true));
    }

    /// Keeps local zero-placeholder versions unmarked on all non-stable release branches.
    @Test
    void keepsLocalReleaseBranchVersionsUnmarked() {
        assertEquals("1.0.0.0", ReleaseVersionResolver.resolve(
                ReleaseType.BETA, "1.0.0", null, null, false));
        assertEquals("1.0.0.0.0", ReleaseVersionResolver.resolve(
                ReleaseType.ALPHA, "1.0.0", null, null, false));
        assertEquals("1.0.0.0.0.0", ReleaseVersionResolver.resolve(
                ReleaseType.DEV, "1.0.0", null, null, false));
    }

    /// Accepts only canonical release versions followed by the feature marker.
    @Test
    void validatesMarkedFeatureVersions() {
        ReleaseVersionResolver.validateFeatureVersion("1.0.0.");
        ReleaseVersionResolver.validateFeatureVersion("1.0.0.1.");
        ReleaseVersionResolver.validateFeatureVersion("1.0.0.0.1.");
        ReleaseVersionResolver.validateFeatureVersion("1.0.0.0.0.1.");
        assertThrows(IllegalArgumentException.class, () -> ReleaseVersionResolver.validateFeatureVersion("1.0.0"));
        assertThrows(IllegalArgumentException.class, () -> ReleaseVersionResolver.validateFeatureVersion("1.0."));
        assertThrows(IllegalArgumentException.class,
                () -> ReleaseVersionResolver.validateFeatureVersion("1.0.0.0.0.0.0."));
        assertThrows(IllegalArgumentException.class,
                () -> ReleaseVersionResolver.validateFeatureVersion("1.0.0..0.0.1."));
    }

    /// Requires an explicit version source for official non-stable builds.
    @Test
    void requiresOfficialVersionSource() {
        assertThrows(IllegalArgumentException.class, () -> ReleaseVersionResolver.resolve(
                ReleaseType.DEV, "1.0.0", null, null, true));
    }

    /// Rejects malformed, cross-baseline, and incorrectly shaped versions.
    @Test
    void rejectsInvalidReleaseVersions() {
        assertThrows(IllegalArgumentException.class, () -> ReleaseVersionResolver.resolve(
                ReleaseType.STABLE, "1.0", null, null, true));
        assertThrows(IllegalArgumentException.class, () -> ReleaseVersionResolver.resolve(
                ReleaseType.BETA, "1.0.0", "1.0.0", null, true));
        assertThrows(IllegalArgumentException.class, () -> ReleaseVersionResolver.resolve(
                ReleaseType.BETA, "1.0.0", "1.0.1.1", null, true));
        assertThrows(IllegalArgumentException.class, () -> ReleaseVersionResolver.resolve(
                ReleaseType.ALPHA, "1.0.0", "1.0.0.alpha.1", null, true));
        assertThrows(IllegalArgumentException.class, () -> ReleaseVersionResolver.resolve(
                ReleaseType.DEV, "1.0.0", null, "01", true));
        assertThrows(IllegalArgumentException.class, () -> ReleaseType.fromName("STABLE"));
    }
}
