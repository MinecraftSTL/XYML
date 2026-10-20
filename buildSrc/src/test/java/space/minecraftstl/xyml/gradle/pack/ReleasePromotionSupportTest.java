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

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies the pure helpers used by the `stl` release promotion tasks.
@NotNullByDefault
final class ReleasePromotionSupportTest {
    /// Increments the requested component and clears every lower component.
    @Test
    void incrementsStableVersions() {
        assertEquals("1.0.6", StableVersionIncrement.targetVersion("1.0.5", StableVersionIncrement.Kind.PATCH));
        assertEquals("1.1.0", StableVersionIncrement.targetVersion("1.0.5", StableVersionIncrement.Kind.MINOR));
        assertEquals("2.0.0", StableVersionIncrement.targetVersion("1.0.5", StableVersionIncrement.Kind.MAJOR));
        assertEquals("1.0.0", StableVersionIncrement.targetVersion("0.5.5", StableVersionIncrement.Kind.MAJOR));
        assertEquals("0.9.10", StableVersionIncrement.targetVersion("0.9.9", StableVersionIncrement.Kind.PATCH));
    }

    /// Rejects malformed increments and stable versions.
    @Test
    void rejectsInvalidIncrements() {
        assertThrows(IllegalArgumentException.class, () -> StableVersionIncrement.parseKind("hotfix"));
        assertThrows(IllegalArgumentException.class, () -> StableVersionIncrement.parseKind(""));
        assertThrows(IllegalArgumentException.class,
                () -> StableVersionIncrement.targetVersion("1.0", StableVersionIncrement.Kind.PATCH));
        assertThrows(IllegalArgumentException.class,
                () -> StableVersionIncrement.targetVersion("1.0.x", StableVersionIncrement.Kind.PATCH));
        assertThrows(IllegalArgumentException.class,
                () -> StableVersionIncrement.targetVersion("1.0.99999999999", StableVersionIncrement.Kind.PATCH));
    }

    /// Classifies the adjacent release flows.
    @Test
    void classifiesAdjacentFlows() {
        assertEquals(ReleaseBranchFlow.Kind.PROMOTION, ReleaseBranchFlow.kindOf("dev", "alpha"));
        assertEquals(ReleaseBranchFlow.Kind.PROMOTION, ReleaseBranchFlow.kindOf("alpha", "beta"));
        assertEquals(ReleaseBranchFlow.Kind.PROMOTION, ReleaseBranchFlow.kindOf("beta", "main"));
        assertEquals(ReleaseBranchFlow.Kind.SYNC, ReleaseBranchFlow.kindOf("main", "beta"));
        assertEquals(ReleaseBranchFlow.Kind.SYNC, ReleaseBranchFlow.kindOf("beta", "alpha"));
        assertEquals(ReleaseBranchFlow.Kind.SYNC, ReleaseBranchFlow.kindOf("alpha", "dev"));
        assertThrows(IllegalArgumentException.class, () -> ReleaseBranchFlow.kindOf("dev", "beta"));
        assertThrows(IllegalArgumentException.class, () -> ReleaseBranchFlow.kindOf("main", "dev"));
        assertThrows(IllegalArgumentException.class, () -> ReleaseBranchFlow.kindOf("dev", "main"));
    }

    /// Requires a changed baseline only when a promotion publishes a new stable version.
    @Test
    void requiresChangedBaselineOnlyForStablePromotions() {
        assertTrue(ReleaseBranchFlow.promotionChangesStableBaseline(ReleaseBranchFlow.Kind.PROMOTION, "main"));
        assertFalse(ReleaseBranchFlow.promotionChangesStableBaseline(ReleaseBranchFlow.Kind.PROMOTION, "beta"));
        assertFalse(ReleaseBranchFlow.promotionChangesStableBaseline(ReleaseBranchFlow.Kind.SYNC, "main"));
    }

    /// Walks the stable baseline chain toward main.
    @Test
    void walksTowardMain() {
        assertEquals("main", ReleaseBranchFlow.moreStableBranch("beta"));
        assertEquals("beta", ReleaseBranchFlow.moreStableBranch("alpha"));
        assertEquals("alpha", ReleaseBranchFlow.moreStableBranch("dev"));
        assertThrows(IllegalArgumentException.class, () -> ReleaseBranchFlow.moreStableBranch("main"));
    }

    /// Parses porcelain worktree output into branch checkouts.
    @Test
    void parsesWorktreeCheckouts() {
        String porcelain = String.join("\n",
                "worktree E:/repo",
                "HEAD 1111111111111111111111111111111111111111",
                "branch refs/heads/dev",
                "",
                "worktree E:/scratch",
                "HEAD 2222222222222222222222222222222222222222",
                "detached",
                "",
                "worktree E:/beta",
                "HEAD 3333333333333333333333333333333333333333",
                "branch refs/heads/beta",
                "");
        List<WorktreeCheckouts.Entry> entries = WorktreeCheckouts.parse(porcelain);
        assertEquals(3, entries.size());
        assertEquals("dev", entries.get(0).branch());
        assertNull(entries.get(1).branch());
        assertEquals(Path.of("E:/beta"), entries.get(2).path());
        assertEquals(List.of(Path.of("E:/beta")), WorktreeCheckouts.pathsCheckingOut(entries, "beta"));
        assertTrue(WorktreeCheckouts.pathsCheckingOut(entries, "alpha").isEmpty());
    }

    /// Builds the promotion and stable baseline sync messages.
    @Test
    void buildsMergeMessages() {
        assertEquals("merge: promote dev 1.0.5.0.0.7 to alpha 1.0.5.0.1",
                ReleasePromotionTask.promotionMessage("dev", "1.0.5.0.0.7", "alpha", "1.0.5.0.1"));
        assertEquals("merge: promote beta 1.0.6.3 to main 1.0.6",
                ReleasePromotionTask.promotionMessage("beta", "1.0.6.3", "main", "1.0.6"));
        assertEquals("merge: sync stable 1.0.6 to dev", ReleasePromotionTask.syncMessage("1.0.6", "dev"));
    }

    /// Moves the target branch alone, or the whole chain for stable promotions.
    @Test
    void selectsMovedBranches() {
        assertEquals(List.of("alpha"), ReleasePromotionTask.movedBranches("dev", "alpha", false));
        assertEquals(List.of("beta"), ReleasePromotionTask.movedBranches("alpha", "beta", false));
        assertEquals(List.of("beta", "main", "alpha", "dev"),
                ReleasePromotionTask.movedBranches("beta", "main", true));
    }

    /// Resolves short branch names from local refs.
    @Test
    void resolvesBranchNames() {
        assertEquals("main", ReleasePromotionTask.branchName("refs/heads/main"));
        assertEquals("dev", ReleasePromotionTask.branchName("refs/heads/dev"));
    }

    /// Counts and replaces the stable version line byte for byte.
    @Test
    void rewritesStableVersionBytes() {
        byte[] content = "stableVersion=1.0.5\n".getBytes(StandardCharsets.UTF_8);
        byte[] current = "stableVersion=1.0.5".getBytes(StandardCharsets.UTF_8);
        byte[] target = "stableVersion=1.0.6".getBytes(StandardCharsets.UTF_8);
        assertEquals(1, ReleasePromotionTask.countOccurrences(content, current));
        assertEquals("stableVersion=1.0.6\n", new String(
                ReleasePromotionTask.replaceOccurrence(content, current, target), StandardCharsets.UTF_8));
        assertEquals(0, ReleasePromotionTask.countOccurrences(content,
                "stableVersion=9.9.9".getBytes(StandardCharsets.UTF_8)));
    }

    /// Forces nested Gradle gates to use a single-use daemon.
    @Test
    void addsNoDaemonToNestedGradleArguments() {
        List<String> gate = List.of("checkstyle", "checkTranslations");
        assertEquals(List.of("checkstyle", "checkTranslations", "--no-daemon"),
                ReleasePromotionTask.withNoDaemon(gate));
        List<String> probe = List.of(":XYML:validateReleaseMetadata", "--no-daemon", "--stacktrace");
        assertEquals(probe, ReleasePromotionTask.withNoDaemon(probe));
    }

    /// Keeps the nested Wrapper command identical on Windows and Unix.
    @Test
    void buildsCrossPlatformWrapperCommands() {
        Path checkout = Path.of("checkout");
        List<String> arguments = List.of(":XYML:validateReleaseMetadata", "--no-daemon");
        List<String> windows = GitBranchGradleTask.nestedGradleCommand(checkout, true, arguments);
        List<String> unix = GitBranchGradleTask.nestedGradleCommand(checkout, false, arguments);
        assertEquals("cmd.exe", windows.get(0));
        assertEquals("/d", windows.get(1));
        assertEquals("/c", windows.get(2));
        assertEquals(checkout.resolve("gradlew.bat").toString(), windows.get(3));
        assertEquals("-Djava.net.useSystemProxies=true", windows.get(4));
        assertEquals(arguments, windows.subList(5, windows.size()));
        assertEquals(checkout.resolve("gradlew").toString(), unix.get(0));
        assertEquals(arguments, unix.subList(1, unix.size()));
    }
}
