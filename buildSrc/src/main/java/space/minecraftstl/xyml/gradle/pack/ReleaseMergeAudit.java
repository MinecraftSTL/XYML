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
import org.jetbrains.annotations.Unmodifiable;

import java.util.ArrayList;
import java.util.List;

/// Verifies the merge topology and stable baseline rules of the XYML release model.
///
/// The checks mirror `config/release/validate-branch-flow.sh`, which stays the authoritative check for pull requests.
/// Every Git read goes through one query function so the rules can be exercised against synthetic repositories
/// without moving any branch.
@NotNullByDefault
final class ReleaseMergeAudit {
    /// Stable version property file read from compared commits.
    private static final String STABLE_VERSION_FILE = "config/project.properties";

    /// Stable version property prefix.
    private static final String STABLE_VERSION_PREFIX = "stableVersion=";

    /// Git query used for every topology read.
    private final GitQuery query;

    /// Reads one Git query result.
    @FunctionalInterface
    interface GitQuery {
        /// Runs Git and returns its combined output.
        ///
        /// @param arguments Git arguments
        /// @return combined output
        String output(String... arguments);
    }

    /// Creates an audit that reads topology through one Git query.
    ///
    /// @param query Git query implementation
    ReleaseMergeAudit(GitQuery query) {
        this.query = query;
    }

    /// Verifies one merge uses the expected two parents in order.
    ///
    /// @param commit merge commit to inspect
    /// @param firstParent expected first parent
    /// @param secondParent expected second parent
    /// @throws IllegalStateException when the commit is not that two-parent merge
    void requireTwoParentMerge(String commit, String firstParent, String secondParent) {
        @Unmodifiable List<String> ids = commitAndParents(commit);
        if (ids.size() != 3) {
            throw new IllegalStateException("Expected a two-parent --no-ff merge at " + commit + " but found "
                    + (ids.size() - 1) + " parents");
        }
        if (!ids.get(1).equals(firstParent) || !ids.get(2).equals(secondParent)) {
            throw new IllegalStateException("Merge " + commit + " must use parents " + firstParent + " and "
                    + secondParent + " but used " + ids.get(1) + " and " + ids.get(2));
        }
    }

    /// Verifies one merge takes the stable baseline from its second parent.
    ///
    /// @param commit merge commit to inspect
    /// @param secondParent expected baseline carrier
    /// @throws IllegalStateException when the merge keeps another baseline
    void requireBaselineFromSecondParent(String commit, String secondParent) {
        String mergeVersion = stableVersion(commit);
        String secondParentVersion = stableVersion(secondParent);
        if (!mergeVersion.equals(secondParentVersion)) {
            throw new IllegalStateException("Merge " + commit + " must take stableVersion from its second parent "
                    + secondParent + " but used " + mergeVersion + " instead of " + secondParentVersion);
        }
    }

    /// Verifies one merge is a valid carrier of the new stable baseline.
    ///
    /// @param channel channel that received the sync merge
    /// @param commit sync merge commit
    /// @param stableVersion expected stable version
    /// @throws IllegalStateException when the carrier chain does not reach a matching main release merge
    void requireBaselineCarrier(String channel, String commit, String stableVersion) {
        String carrierVersion = stableVersion(commit);
        if (!carrierVersion.equals(stableVersion)) {
            throw new IllegalStateException("Stable baseline carrier on " + channel + " must carry " + stableVersion
                    + " but carried " + carrierVersion + ": " + commit);
        }
        String currentChannel = channel;
        String currentCommit = commit;
        while (true) {
            @Unmodifiable List<String> ids = commitAndParents(currentCommit);
            if (ids.size() != 3) {
                throw new IllegalStateException("Stable baseline carrier on " + currentChannel
                        + " must be a two-parent merge: " + currentCommit);
            }
            requireBaselineFromSecondParent(currentCommit, ids.get(2));
            if (ReleaseBranchFlow.MAIN_BRANCH.equals(currentChannel)) {
                String firstParentVersion = stableVersion(ids.get(1));
                if (stableVersion(currentCommit).equals(firstParentVersion)) {
                    throw new IllegalStateException(
                            "Stable release merge on main must change stableVersion: " + currentCommit);
                }
                return;
            }
            currentCommit = ids.get(2);
            currentChannel = ReleaseBranchFlow.moreStableBranch(currentChannel);
        }
    }

    /// Reads the stable version carried by one commit.
    ///
    /// @param commit commit to read
    /// @return stable version stored in the commit
    /// @throws IllegalStateException when the commit has no stable version
    String stableVersion(String commit) {
        for (String line : query.output("show", commit + ":" + STABLE_VERSION_FILE).split("\n", -1)) {
            if (line.startsWith(STABLE_VERSION_PREFIX)) {
                return line.substring(STABLE_VERSION_PREFIX.length()).trim();
            }
        }
        throw new IllegalStateException("Missing " + STABLE_VERSION_PREFIX + " in " + commit);
    }

    /// Reports one commit id followed by its direct parents.
    ///
    /// @param commit commit to inspect
    /// @return immutable ids in Git order, starting with the commit itself
    private @Unmodifiable List<String> commitAndParents(String commit) {
        List<String> ids = new ArrayList<>();
        for (String part : query.output("rev-list", "--parents", "-n", "1", commit).trim().split(" ")) {
            if (!part.isBlank()) {
                ids.add(part);
            }
        }
        return List.copyOf(ids);
    }
}
