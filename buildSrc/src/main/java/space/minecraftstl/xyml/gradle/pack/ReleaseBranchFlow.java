// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.gradle.pack;

import org.jetbrains.annotations.NotNullByDefault;

/// Classifies the adjacent release flows allowed by the XYML release model.
///
/// This mirrors the branch decisions of `config/release/validate-branch-flow.sh`, which stays the authoritative check
/// for pull requests; keep both implementations in sync when the branch model changes.
@NotNullByDefault
final class ReleaseBranchFlow {
    /// Stable branch name.
    static final String MAIN_BRANCH = "main";

    /// Blocking kind of one adjacent flow.
    enum Kind {
        /// Moves channel content toward a more stable channel.
        PROMOTION,

        /// Carries a changed stable baseline toward a less stable channel.
        SYNC
    }

    /// Prevents instantiation of this stateless helper.
    private ReleaseBranchFlow() {
    }

    /// Classifies one adjacent branch flow.
    ///
    /// @param sourceBranch branch that provides the merged commits
    /// @param targetBranch branch that receives the merge
    /// @return promotion or synchronization kind
    /// @throws IllegalArgumentException when the pair is not an adjacent release flow
    static Kind kindOf(String sourceBranch, String targetBranch) {
        return switch (sourceBranch + ":" + targetBranch) {
            case "dev:alpha", "alpha:beta", "beta:main" -> Kind.PROMOTION;
            case "main:beta", "beta:alpha", "alpha:dev" -> Kind.SYNC;
            default -> throw new IllegalArgumentException(
                    "Unsupported adjacent release flow: " + sourceBranch + " -> " + targetBranch);
        };
    }

    /// Reports whether a promotion publishes a new stable version.
    ///
    /// @param kind flow kind
    /// @param targetBranch target branch name
    /// @return `true` when the promotion must carry a changed stable baseline
    static boolean promotionChangesStableBaseline(Kind kind, String targetBranch) {
        return kind == Kind.PROMOTION && MAIN_BRANCH.equals(targetBranch);
    }

    /// Returns the adjacent, more stable branch for one channel.
    ///
    /// @param branch release branch name
    /// @return adjacent more stable branch name
    /// @throws IllegalArgumentException when the branch is `main`
    static String moreStableBranch(String branch) {
        return switch (branch) {
            case "beta" -> MAIN_BRANCH;
            case "alpha" -> "beta";
            case "dev" -> "alpha";
            default -> throw new IllegalArgumentException("No more stable neighbour for branch: " + branch);
        };
    }
}
