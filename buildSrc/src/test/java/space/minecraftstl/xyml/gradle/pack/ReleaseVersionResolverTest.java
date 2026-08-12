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
                ReleaseType.STABLE, "1.0.0", null, null, true, "main"));
    }

    /// Generates each testing channel at its required decimal depth.
    @Test
    void derivesChannelVersionsFromBuildNumber() {
        assertEquals("1.0.0.7", ReleaseVersionResolver.resolve(
                ReleaseType.BETA, "1.0.0", null, "7", true, "beta"));
        assertEquals("1.0.0.0.7", ReleaseVersionResolver.resolve(
                ReleaseType.ALPHA, "1.0.0", null, "7", true, "alpha"));
        assertEquals("1.0.0.0.0.7", ReleaseVersionResolver.resolve(
                ReleaseType.DEV, "1.0.0", null, "7", true, "dev"));
    }

    /// Preserves explicit parent counters selected during promotion.
    @Test
    void acceptsExplicitHierarchicalVersions() {
        assertEquals("1.0.1.3", ReleaseVersionResolver.resolve(
                ReleaseType.BETA, "1.0.1", "1.0.1.3", null, true, "beta"));
        assertEquals("1.0.1.3.2", ReleaseVersionResolver.resolve(
                ReleaseType.ALPHA, "1.0.1", "1.0.1.3.2", null, true, "alpha"));
        assertEquals("1.0.1.3.2.9", ReleaseVersionResolver.resolve(
                ReleaseType.DEV, "1.0.1", "1.0.1.3.2.9", null, true, "dev"));
    }

    /// Keeps local zero-placeholder versions unmarked on all non-stable release branches.
    @Test
    void keepsLocalReleaseBranchVersionsUnmarked() {
        assertEquals("1.0.0.0", ReleaseVersionResolver.resolve(
                ReleaseType.BETA, "1.0.0", null, null, false, "beta"));
        assertEquals("1.0.0.0.0", ReleaseVersionResolver.resolve(
                ReleaseType.ALPHA, "1.0.0", null, null, false, "alpha"));
        assertEquals("1.0.0.0.0.0", ReleaseVersionResolver.resolve(
                ReleaseType.DEV, "1.0.0", null, null, false, "dev"));
    }

    /// Appends an empty component to local, CI, and explicit versions built outside release branches.
    @Test
    void marksFeatureBranchVersions() {
        assertEquals("1.0.0.0.0.0.", ReleaseVersionResolver.resolve(
                ReleaseType.DEV, "1.0.0", null, null, false, "feature/settings"));
        assertEquals("1.0.0.0.0.7.", ReleaseVersionResolver.resolve(
                ReleaseType.DEV, "1.0.0", null, "7", true, "feature/settings"));
        assertEquals("1.0.0.0.0.8.", ReleaseVersionResolver.resolve(
                ReleaseType.DEV, "1.0.0", "1.0.0.0.0.8", null, true, null));
        assertThrows(IllegalArgumentException.class, () -> ReleaseVersionResolver.resolve(
                ReleaseType.DEV, "1.0.0", null, null, true, "dev"));
    }

    /// Rejects malformed, cross-baseline, and incorrectly shaped versions.
    @Test
    void rejectsInvalidReleaseVersions() {
        assertThrows(IllegalArgumentException.class, () -> ReleaseVersionResolver.resolve(
                ReleaseType.STABLE, "1.0", null, null, true, "main"));
        assertThrows(IllegalArgumentException.class, () -> ReleaseVersionResolver.resolve(
                ReleaseType.BETA, "1.0.0", "1.0.0", null, true, "beta"));
        assertThrows(IllegalArgumentException.class, () -> ReleaseVersionResolver.resolve(
                ReleaseType.BETA, "1.0.0", "1.0.1.1", null, true, "beta"));
        assertThrows(IllegalArgumentException.class, () -> ReleaseVersionResolver.resolve(
                ReleaseType.ALPHA, "1.0.0", "1.0.0.alpha.1", null, true, "alpha"));
        assertThrows(IllegalArgumentException.class, () -> ReleaseVersionResolver.resolve(
                ReleaseType.DEV, "1.0.0", null, "01", true, "dev"));
        assertThrows(IllegalArgumentException.class, () -> ReleaseType.fromName("STABLE"));
    }
}
