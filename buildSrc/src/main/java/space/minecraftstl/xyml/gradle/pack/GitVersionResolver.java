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
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.function.Supplier;

/// Infers XYML release and feature versions from Git branch topology.
///
/// Release channels form hierarchical epochs from Stable through Beta and Alpha to Dev. A promotion or synchronization
/// from a more stable channel snapshots the parent's counters and clears the target channel's counter. The promotion's
/// committer timestamp lets that epoch apply to the next lower-channel commit without a reverse merge; histories
/// without an identifiable boundary retain the legacy merge-base calculation.
/// Feature and detached versions use the complete release version at the newest reachable first-parent commit on a
/// release branch. Sources are preferred in the order Dev, Alpha, Beta, Stable; the selected release version receives
/// a trailing `.` marker. If no release branch is reachable, the fixed fallback is `0.0.0.0.0.0.`.
@NotNullByDefault
public final class GitVersionResolver {
    /// Keeps read-only Git command results for one public resolution call.
    private static final ThreadLocal<ResolutionCache> ACTIVE_CACHE = new ThreadLocal<>();

    /// Git pretty-format record used to read merge parents and timestamps.
    private static final String MERGE_RECORD_FORMAT = "--format=%H%x09%ct%x09%P%x7f";

    /// Release channels ordered from the preferred feature base to the fallback base.
    private static final @Unmodifiable List<ReleaseType> FEATURE_SOURCE_PRIORITY =
            List.of(ReleaseType.DEV, ReleaseType.ALPHA, ReleaseType.BETA, ReleaseType.STABLE);

    /// Release-ref namespaces considered independently for feature bases.
    private static final @Unmodifiable List<String> FEATURE_REF_NAMESPACES =
            List.of("refs/heads/", "refs/remotes/origin/");

    /// Fixed marker version returned when no release branch is reachable.
    private static final String FEATURE_VERSION_FALLBACK = "0.0.0.0.0.0.";

    /// Memoizes Git queries while one version is being resolved.
    ///
    /// The cache is intentionally scoped to a single thread and call. Release refs can move between Gradle
    /// invocations, so retaining results globally would make version inference stale.
    @NotNullByDefault
    private static final class ResolutionCache {
        /// Repository for which the cached commands were executed.
        private final Path repository;

        /// Command result cache; a `null` value represents an allowed optional-command failure.
        private final Map<String, @Nullable String> commandResults = new HashMap<>();

        /// Creates an empty cache for one repository.
        ///
        /// @param repository Git repository root
        private ResolutionCache(Path repository) {
            this.repository = repository;
        }

        /// Executes and memoizes one Git command.
        ///
        /// @param allowFailure whether a non-zero exit may return `null`
        /// @param arguments Git command arguments
        /// @return command output, or `null` for an allowed failure
        private @Nullable String execute(boolean allowFailure, String... arguments) {
            String key = (allowFailure ? "optional\u0000" : "required\u0000") + String.join("\u0000", arguments);
            if (commandResults.containsKey(key)) {
                return commandResults.get(key);
            }
            @Nullable String result = executeGitUncached(repository, allowFailure, arguments);
            commandResults.put(key, result);
            return result;
        }
    }

    /// Counters inherited or advanced at one release-channel commit.
    ///
    /// @param betaCounter Beta counter within the Stable epoch
    /// @param alphaCounter Alpha counter within the Beta epoch
    /// @param devCounter Dev counter within the Alpha epoch
    @NotNullByDefault
    private record ReleaseCounters(int betaCounter, int alphaCounter, int devCounter) {
        /// Returns a copy with one channel counter replaced and all less stable counters cleared.
        ///
        /// @param releaseType channel whose counter is replaced
        /// @param counter non-negative counter value
        /// @return updated hierarchical counters
        private ReleaseCounters withCounter(ReleaseType releaseType, int counter) {
            return switch (releaseType) {
                case STABLE -> new ReleaseCounters(0, 0, 0);
                case BETA -> new ReleaseCounters(counter, 0, 0);
                case ALPHA -> new ReleaseCounters(betaCounter, counter, 0);
                case DEV -> new ReleaseCounters(betaCounter, alphaCounter, counter);
            };
        }
    }

    /// A release merge that establishes an epoch for less stable channels.
    ///
    /// @param commit release merge commit
    /// @param timestamp committer timestamp in seconds since the Unix epoch
    /// @param channel channel whose first-parent history contains the merge
    /// @param secondParent selected source snapshot
    @NotNullByDefault
    private record EpochMerge(
            String commit,
            long timestamp,
            ReleaseType channel,
            String secondParent) {
    }

    /// One release branch's newest reachable base for a feature version.
    ///
    /// @param channel source release channel
    /// @param commit selected release-branch commit
    /// @param timestamp committer timestamp in seconds since the Unix epoch
    /// @param version release version at the selected commit
    /// @param refNamespace local or origin namespace supplying every release ref
    @NotNullByDefault
    private record FeatureBase(
            ReleaseType channel,
            String commit,
            long timestamp,
            String version,
            String refNamespace) {
    }

    /// A first-parent commit and its committer timestamp.
    ///
    /// @param commit first-parent commit
    /// @param timestamp committer timestamp in seconds since the Unix epoch
    @NotNullByDefault
    private record TimedCommit(String commit, long timestamp) {
    }

    /// Parsed merge metadata read from one release-channel history.
    ///
    /// @param commit merge commit
    /// @param timestamp committer timestamp in seconds since the Unix epoch
    /// @param firstParent first parent of the merge
    /// @param secondParent second parent of the merge
    @NotNullByDefault
    private record MergeCommit(
            String commit,
            long timestamp,
            String firstParent,
            String secondParent) {
    }

    /// Prevents construction of this stateless resolver.
    private GitVersionResolver() {
    }

    /// Maps an exact release branch name to its release type.
    ///
    /// @param branchName checked-out or CI-provided branch name, or `null` for a detached checkout
    /// @return the release type, or `null` when the checkout is a feature build
    public static @Nullable ReleaseType releaseTypeForBranch(@Nullable String branchName) {
        if (branchName == null) {
            return null;
        }
        return switch (branchName) {
            case "main" -> ReleaseType.STABLE;
            case "beta" -> ReleaseType.BETA;
            case "alpha" -> ReleaseType.ALPHA;
            case "dev" -> ReleaseType.DEV;
            default -> null;
        };
    }

    /// Resolves the current checkout as a marked non-release feature version.
    ///
    /// Release bases from local and origin namespaces are considered independently. Within each namespace, release
    /// channels are preferred in the order Dev, Alpha, Beta, and Stable; candidates from the same channel are ordered
    /// by topology and committer time.
    ///
    /// @param repository Git repository root
    /// @param stableVersion stable version stored by the current checkout
    /// @return marked feature version, or `0.0.0.0.0.0.` when no release base is reachable
    public static String resolveCurrentFeatureVersion(Path repository, String stableVersion) {
        return withResolutionCache(repository, () -> {
            ReleaseVersionResolver.validateVersion(ReleaseType.STABLE, stableVersion);
            return resolveFeatureVersionUncached(repository, stableVersion, "HEAD");
        });
    }

    /// Resolves a feature or detached commit from one explicit release-ref namespace.
    ///
    /// The feature branch's own commits do not advance the result. The selected release version is the newest
    /// reachable release-branch commit in the requested namespace after applying channel priority.
    ///
    /// @param repository Git repository root
    /// @param stableVersion stable version stored by the current checkout
    /// @param headRef feature commit or ref
    /// @param refNamespace local or origin release-ref namespace
    /// @return marked feature version, or `0.0.0.0.0.0.` when no release base is reachable
    public static String resolveFeatureVersion(
            Path repository,
            String stableVersion,
            String headRef,
            String refNamespace) {
        return withResolutionCache(repository, () -> {
            ReleaseVersionResolver.validateVersion(ReleaseType.STABLE, stableVersion);
            return resolveFeatureVersionUncached(repository, stableVersion, headRef, refNamespace);
        });
    }

    /// Finds the highest-priority release base across local and origin namespaces.
    ///
    /// @param repository Git repository root
    /// @param stableVersion stable version stored by the current checkout
    /// @param headRef feature commit or ref
    /// @return marked feature version or the fixed fallback
    private static String resolveFeatureVersionUncached(Path repository, String stableVersion, String headRef) {
        for (ReleaseType channel : FEATURE_SOURCE_PRIORITY) {
            @Nullable FeatureBase selected = null;
            for (String refNamespace : FEATURE_REF_NAMESPACES) {
                @Nullable FeatureBase candidate = featureBase(
                        repository, stableVersion, headRef, refNamespace, channel);
                if (candidate != null) {
                    selected = newestFeatureBase(repository, selected, candidate);
                }
            }
            if (selected != null) {
                return markedFeatureVersion(selected.version());
            }
        }
        return FEATURE_VERSION_FALLBACK;
    }

    /// Finds the highest-priority release base in one release-ref namespace.
    ///
    /// @param repository Git repository root
    /// @param stableVersion stable version stored by the current checkout
    /// @param headRef feature commit or ref
    /// @param refNamespace local or origin release-ref namespace
    /// @return marked feature version or the fixed fallback
    private static String resolveFeatureVersionUncached(
            Path repository,
            String stableVersion,
            String headRef,
            String refNamespace) {
        for (ReleaseType channel : FEATURE_SOURCE_PRIORITY) {
            @Nullable FeatureBase candidate = featureBase(
                    repository, stableVersion, headRef, refNamespace, channel);
            if (candidate != null) {
                return markedFeatureVersion(candidate.version());
            }
        }
        return FEATURE_VERSION_FALLBACK;
    }

    /// Resolves one release-branch candidate for a feature version.
    ///
    /// @param repository Git repository root
    /// @param stableVersion stable version stored by the current checkout
    /// @param headRef feature commit or ref
    /// @param refNamespace local or origin release-ref namespace
    /// @param channel release channel being tested
    /// @return release base, or `null` when the channel has no valid reachable base
    private static @Nullable FeatureBase featureBase(
            Path repository,
            String stableVersion,
            String headRef,
            String refNamespace,
            ReleaseType channel) {
        @Nullable String releaseRef = optionalRef(repository, refNamespace + releaseBranchName(channel));
        if (releaseRef == null) {
            return null;
        }
        @Nullable String adjacentRef = channel == ReleaseType.STABLE
                ? null
                : optionalRef(repository, refNamespace + releaseBranchName(parentReleaseType(channel)));
        if (channel != ReleaseType.STABLE && adjacentRef == null) {
            return null;
        }
        @Unmodifiable Set<String> parentHistory = adjacentRef == null
                ? Set.of()
                : Set.copyOf(firstParentCommits(repository, adjacentRef));
        @Nullable String candidate = firstParentCommonCommit(repository, releaseRef, headRef, parentHistory);
        if (candidate == null) {
            return null;
        }

        String version;
        try {
            version = resolveReleaseVersion(repository, channel, stableVersion, candidate, adjacentRef);
        } catch (IllegalArgumentException | IllegalStateException expected) {
            return null;
        }
        return new FeatureBase(channel, candidate, commitTimestamp(repository, candidate), version, refNamespace);
    }

    /// Selects the newest candidate for one release channel.
    ///
    /// @param repository Git repository root
    /// @param current previously selected candidate, or `null`
    /// @param candidate new candidate
    /// @return newer candidate, preferring local refs when topology and time tie
    private static FeatureBase newestFeatureBase(
            Path repository,
            @Nullable FeatureBase current,
            FeatureBase candidate) {
        if (current == null) {
            return candidate;
        }
        if (candidate.commit().equals(current.commit())) {
            return isLocalNamespace(candidate.refNamespace()) ? candidate : current;
        }
        if (isAncestor(repository, current.commit(), candidate.commit())) {
            return candidate;
        }
        if (isAncestor(repository, candidate.commit(), current.commit())) {
            return current;
        }
        if (candidate.timestamp() > current.timestamp()) {
            return candidate;
        }
        if (candidate.timestamp() < current.timestamp()) {
            return current;
        }
        return isLocalNamespace(candidate.refNamespace()) ? candidate : current;
    }

    /// Returns whether a namespace contains local branches.
    ///
    /// @param refNamespace local or origin release-ref namespace
    /// @return whether the namespace is `refs/heads/`
    private static boolean isLocalNamespace(String refNamespace) {
        return "refs/heads/".equals(refNamespace);
    }

    /// Applies and validates the trailing feature marker.
    ///
    /// @param releaseVersion selected release version
    /// @return marked feature version
    private static String markedFeatureVersion(String releaseVersion) {
        String version = releaseVersion + ".";
        ReleaseVersionResolver.validateFeatureVersion(version);
        return version;
    }

    /// Resolves the checked-out release branch from its first-parent distance to the adjacent stable branch.
    ///
    /// @param repository Git repository root
    /// @param releaseType release channel represented by the current branch
    /// @param stableVersion stable version stored by the current checkout
    /// @return inferred release version for `HEAD`
    public static String resolveCurrentReleaseVersion(
            Path repository,
            ReleaseType releaseType,
            String stableVersion) {
        @Nullable String adjacentBranch = switch (releaseType) {
            case STABLE -> null;
            case BETA -> "main";
            case ALPHA -> "beta";
            case DEV -> "alpha";
        };
        String refNamespace = releaseType == ReleaseType.STABLE
                ? "refs/heads/"
                : preferredReleaseRefNamespace(repository, releaseType);
        @Nullable String adjacentRef = adjacentBranch == null
                ? null
                : releaseBranchRef(repository, parentReleaseType(releaseType), refNamespace);
        return resolveReleaseVersion(repository, releaseType, stableVersion, "HEAD", adjacentRef);
    }

    /// Resolves a release-channel version for an arbitrary target commit.
    ///
    /// A promotion or Stable-baseline synchronization merge starts a target-channel epoch. It inherits the complete
    /// parent version and clears the target and all less stable counters. Stable-baseline synchronization is recognized
    /// by a changed Stable-version property and an uninterrupted adjacent-channel chain back to the Stable merge.
    /// Older histories without an identifiable boundary retain the original merge-base calculation. A promotion is
    /// applied to lower channels by committer chronology, so the promoted channel is not merged back into them.
    ///
    /// @param repository Git repository root
    /// @param releaseType release channel represented by the target commit
    /// @param stableVersion stable version prefix
    /// @param targetRef target commit or ref
    /// @param adjacentStableRef adjacent, more stable branch ref; `null` is allowed only for Stable
    /// @return inferred release version
    public static String resolveReleaseVersion(
            Path repository,
            ReleaseType releaseType,
            String stableVersion,
            String targetRef,
            @Nullable String adjacentStableRef) {
        ReleaseVersionResolver.validateVersion(ReleaseType.STABLE, stableVersion);
        if (releaseType == ReleaseType.STABLE) {
            return stableVersion;
        }
        if (adjacentStableRef == null) {
            throw new IllegalArgumentException(releaseType.getName() + " requires an adjacent stable branch ref");
        }

        ReleaseCounters counters = withResolutionCache(
                repository,
                () -> resolveReleaseCounters(repository, releaseType, targetRef, adjacentStableRef));
        String result = switch (releaseType) {
            case STABLE -> stableVersion;
            case BETA -> stableVersion + '.' + counters.betaCounter();
            case ALPHA -> stableVersion + '.' + counters.betaCounter() + '.' + counters.alphaCounter();
            case DEV -> stableVersion + '.' + counters.betaCounter() + '.' + counters.alphaCounter() + '.'
                    + counters.devCounter();
        };
        ReleaseVersionResolver.validateVersion(releaseType, result);
        return result;
    }

    /// Reads the stable version property from one Git commit without checking it out.
    ///
    /// @param repository Git repository root
    /// @param commit commit containing `config/project.properties`
    /// @return validated three-component stable version
    public static String readStableVersion(Path repository, String commit) {
        String content = git(repository, "show", commit + ":config/project.properties");
        Properties properties = new Properties();
        try {
            properties.load(new StringReader(content));
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot parse stable version at " + commit, exception);
        }
        @Nullable String stableVersion = properties.getProperty("stableVersion");
        if (stableVersion == null || stableVersion.isBlank()) {
            throw new IllegalStateException("Missing stableVersion at " + commit);
        }
        ReleaseVersionResolver.validateVersion(ReleaseType.STABLE, stableVersion);
        return stableVersion;
    }

    /// Resolves a ref to an immutable commit SHA.
    ///
    /// @param repository Git repository root
    /// @param ref Git ref or revision
    /// @return complete commit SHA
    public static String resolveCommit(Path repository, String ref) {
        return git(repository, "rev-parse", "--verify", ref + "^{commit}");
    }

    /// Selects a consistent namespace for a checked-out release branch and its adjacent parent.
    ///
    /// The current branch and its adjacent channel must both exist in the selected namespace.
    ///
    /// @param repository Git repository root
    /// @param releaseType current release channel
    /// @return local or origin namespace containing the required pair
    private static String preferredReleaseRefNamespace(Path repository, ReleaseType releaseType) {
        ReleaseType parentType = parentReleaseType(releaseType);
        if (hasReleaseRefPair(repository, "refs/heads/", releaseType, parentType)) {
            return "refs/heads/";
        }
        if (hasReleaseRefPair(repository, "refs/remotes/origin/", releaseType, parentType)) {
            return "refs/remotes/origin/";
        }
        throw new IllegalStateException(
                "Cannot find current and adjacent release refs for " + releaseType.getName());
    }

    /// Checks whether one namespace contains two release branches.
    ///
    /// @param repository Git repository root
    /// @param refNamespace local or origin release-ref namespace
    /// @param firstType first release channel
    /// @param secondType second release channel
    /// @return whether both branch refs exist
    private static boolean hasReleaseRefPair(
            Path repository,
            String refNamespace,
            ReleaseType firstType,
            ReleaseType secondType) {
        return optionalRef(repository, refNamespace + releaseBranchName(firstType)) != null
                && optionalRef(repository, refNamespace + releaseBranchName(secondType)) != null;
    }

    /// Checks whether an exact Git ref resolves to a commit.
    ///
    /// @param repository Git repository root
    /// @param ref full ref name
    /// @return the supplied ref, or `null` when it does not exist
    private static @Nullable String optionalRef(Path repository, String ref) {
        return tryGit(repository, "rev-parse", "--verify", "--quiet", ref + "^{commit}") == null ? null : ref;
    }

    /// Finds the best common ancestor of two refs.
    ///
    /// @param repository Git repository root
    /// @param left first ref
    /// @param right second ref
    /// @return merge-base commit SHA
    private static String mergeBase(Path repository, String left, String right) {
        return git(repository, "merge-base", left, right);
    }

    /// Finds the newest commit on a release branch's first-parent chain that is reachable from another ref.
    ///
    /// Unlike a regular merge base, this cannot move onto feature-only commits after the feature has been merged back
    /// with `--no-ff`. It also lets a feature inherit a newer release base after explicitly merging that branch.
    ///
    /// @param repository Git repository root
    /// @param releaseRef release branch whose first-parent history defines valid bases
    /// @param descendantRef feature or detached ref
    /// @param excludedCommits release commits that belong to a more stable branch
    /// @return newest eligible shared release first-parent commit, or `null` when none exists
    private static @Nullable String firstParentCommonCommit(
            Path repository,
            String releaseRef,
            String descendantRef,
            Set<String> excludedCommits) {
        @Unmodifiable Set<String> descendantHistory = Set.copyOf(
                git(repository, "rev-list", descendantRef).lines().toList());
        for (String releaseCommit : firstParentCommits(repository, releaseRef)) {
            if (!excludedCommits.contains(releaseCommit) && descendantHistory.contains(releaseCommit)) {
                return releaseCommit;
            }
        }
        return null;
    }

    /// Counts target commits along the first-parent chain after an ancestor.
    ///
    /// @param repository Git repository root
    /// @param ancestor inclusive base commit
    /// @param target target commit or ref
    /// @return non-negative first-parent distance
    private static int firstParentDistance(Path repository, String ancestor, String target) {
        String value = git(repository, "rev-list", "--first-parent", "--count", ancestor + ".." + target);
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            throw new IllegalStateException("Git returned an invalid first-parent distance: " + value, exception);
        }
    }

    /// Resolves all hierarchical counters at a release-channel commit.
    ///
    /// A reachable synchronization or chronologically applicable promotion starts a new epoch from its second parent.
    /// If the target predates the first boundary, the source merge's first parent freezes the old parent snapshot so
    /// later promotions cannot renumber that history. Without an identifiable boundary, the legacy calculation is
    /// retained for compatibility.
    ///
    /// @param repository Git repository root
    /// @param releaseType target release channel
    /// @param targetRef target commit or ref
    /// @param adjacentStableRef adjacent, more stable branch ref
    /// @return counters at the target commit
    private static ReleaseCounters resolveReleaseCounters(
            Path repository,
            ReleaseType releaseType,
            String targetRef,
            String adjacentStableRef) {
        String refNamespace = releaseRefNamespace(adjacentStableRef);
        ReleaseType parentType = parentReleaseType(releaseType);
        String targetBranch = releaseBranchName(releaseType);
        String targetCommit = resolveCommit(repository, targetRef);
        @Nullable String targetBranchRef = optionalRef(repository, refNamespace + targetBranch);
        @Nullable EpochMerge nextSync = targetBranchRef == null
                ? null
                : findNextSyncMerge(
                        repository,
                        targetRef,
                        targetBranchRef,
                        adjacentStableRef,
                        releaseType);
        @Unmodifiable List<EpochMerge> previousSyncMerges = findSyncMerges(
                repository,
                targetRef,
                adjacentStableRef,
                releaseType);
        @Nullable EpochMerge previousSync = previousSyncMerges.isEmpty()
                ? null
                : previousSyncMerges.get(0);
        Set<String> structuralSynchronizations = new HashSet<>();
        for (EpochMerge syncMerge : previousSyncMerges) {
            structuralSynchronizations.add(syncMerge.commit());
        }
        @Nullable EpochMerge latestPromotion = findLatestUpstreamEpochMerge(
                repository,
                releaseType,
                targetCommit,
                adjacentStableRef);
        if (previousSync != null
                && (latestPromotion == null
                || isAncestor(repository, latestPromotion.commit(), previousSync.secondParent()))) {
            String parentSnapshot = previousSync.secondParent();
            ReleaseCounters parentCounters = countersAtChannelSnapshot(
                    repository,
                    parentType,
                    parentSnapshot,
                    refNamespace);
            int candidateCount = firstParentDistance(repository, previousSync.commit(), targetRef);
            return parentCounters.withCounter(releaseType, candidateCount);
        }
        if (latestPromotion != null) {
            ReleaseCounters parentCounters = countersAtEpochMerge(
                    repository,
                    releaseType,
                    latestPromotion,
                    refNamespace);
            @Nullable Integer commitsAfterPromotion = countFirstParentCommitsAfter(
                    repository,
                    targetRef,
                    latestPromotion.timestamp(),
                    structuralSynchronizations);
            if (commitsAfterPromotion != null) {
                int firstCandidateOffset = releaseType == ReleaseType.DEV ? 1 : 0;
                int counter = Math.max(0, commitsAfterPromotion - firstCandidateOffset);
                return parentCounters.withCounter(releaseType, counter);
            }
        }
        String parentSnapshot = adjacentStableRef;
        @Nullable EpochMerge nextPromotion = findNextUpstreamEpochMerge(
                repository,
                releaseType,
                targetCommit,
                adjacentStableRef);
        long nextSyncTimestamp = Long.MAX_VALUE;
        boolean nextSyncPrecedesPromotion = nextSync != null
                && nextPromotion != null
                && nextSync.timestamp() == nextPromotion.timestamp()
                && isAncestor(repository, nextSync.commit(), nextPromotion.commit());
        if (nextSync != null
                && (nextPromotion == null
                || nextSync.timestamp() < nextPromotion.timestamp()
                || nextSyncPrecedesPromotion)) {
            @Nullable String oldParentSnapshot = optionalFirstParent(
                    repository,
                    nextSync.secondParent());
            if (oldParentSnapshot != null) {
                parentSnapshot = oldParentSnapshot;
            }
            nextSyncTimestamp = nextSync.timestamp();
        }
        boolean nextPromotionPrecedesSync = nextPromotion != null
                && nextSync != null
                && nextPromotion.timestamp() == nextSync.timestamp()
                && isAncestor(repository, nextPromotion.commit(), nextSync.commit());
        if (nextPromotion != null
                && (nextPromotion.timestamp() < nextSyncTimestamp
                || nextPromotionPrecedesSync)) {
            @Nullable String oldParentSnapshot = nextPromotion.channel() == parentType
                    ? optionalFirstParent(repository, nextPromotion.commit())
                    : nextPromotion.secondParent();
            if (oldParentSnapshot != null) {
                parentSnapshot = oldParentSnapshot;
            }
        }
        return resolveLegacyCounters(repository, releaseType, targetRef, parentSnapshot, refNamespace);
    }

    /// Finds the newest upstream release merge that chronologically precedes a target commit.
    ///
    /// Git cannot encode the order of commits made independently on two branches. The release merge committer time
    /// is therefore used as the explicit cross-branch ordering signal; equal timestamps are left unresolved and use
    /// the legacy calculation instead of silently assigning a potentially wrong epoch.
    ///
    /// @param repository Git repository root
    /// @param targetType channel being resolved
    /// @param targetRef target commit or ref
    /// @param adjacentStableRef adjacent stable branch ref
    /// @return latest applicable upstream merge, or `null`
    private static @Nullable EpochMerge findLatestUpstreamEpochMerge(
            Path repository,
            ReleaseType targetType,
            String targetRef,
            String adjacentStableRef) {
        long targetTimestamp = commitTimestamp(repository, targetRef);
        @Unmodifiable List<EpochMerge> merges = findUpstreamEpochMerges(
                repository,
                parentReleaseType(targetType),
                adjacentStableRef,
                releaseRefNamespace(adjacentStableRef));
        long selectedTimestamp = Long.MIN_VALUE;
        @Nullable EpochMerge selected = null;
        for (EpochMerge merge : merges) {
            if (!hasSameStableVersion(repository, merge.commit(), targetRef)) {
                continue;
            }
            boolean precedesTarget = merge.timestamp() < targetTimestamp
                    || merge.timestamp() == targetTimestamp
                    && isAncestor(repository, merge.commit(), targetRef);
            if (precedesTarget) {
                if (merge.timestamp() == selectedTimestamp && selected != null) {
                    if (isAncestor(repository, selected.commit(), merge.commit())) {
                        selected = merge;
                    } else if (!isAncestor(repository, merge.commit(), selected.commit())) {
                        return null;
                    }
                    continue;
                }
                if (merge.timestamp() > selectedTimestamp) {
                    selectedTimestamp = merge.timestamp();
                    selected = merge;
                }
            }
        }
        return selected;
    }

    /// Finds the oldest upstream release merge that chronologically follows a target commit.
    ///
    /// @param repository Git repository root
    /// @param targetType channel being resolved
    /// @param targetRef target commit or ref
    /// @param adjacentStableRef adjacent stable branch ref
    /// @return next applicable upstream merge, or `null`
    private static @Nullable EpochMerge findNextUpstreamEpochMerge(
            Path repository,
            ReleaseType targetType,
            String targetRef,
            String adjacentStableRef) {
        long targetTimestamp = commitTimestamp(repository, targetRef);
        @Unmodifiable List<EpochMerge> merges = findUpstreamEpochMerges(
                repository,
                parentReleaseType(targetType),
                adjacentStableRef,
                releaseRefNamespace(adjacentStableRef));
        @Nullable EpochMerge candidate = null;
        for (EpochMerge merge : merges) {
            if (!hasSameStableVersion(repository, merge.commit(), targetRef)) {
                continue;
            }
            boolean followsTarget = merge.timestamp() > targetTimestamp
                    || merge.timestamp() == targetTimestamp
                    && isAncestor(repository, targetRef, merge.commit());
            if (followsTarget) {
                if (candidate != null && merge.timestamp() == candidate.timestamp()) {
                    if (isAncestor(repository, merge.commit(), candidate.commit())) {
                        candidate = merge;
                    } else if (!isAncestor(repository, candidate.commit(), merge.commit())) {
                        return null;
                    }
                    continue;
                }
                if (candidate == null || merge.timestamp() < candidate.timestamp()) {
                    candidate = merge;
                }
            }
        }
        return candidate;
    }

    /// Collects release merges from the adjacent channel and every more stable channel above it.
    ///
    /// A promotion on Beta changes the prefix inherited by Alpha and Dev even when the lower channel has not yet
    /// received a separate synchronization merge. Keeping the complete chain here makes that propagation explicit.
    ///
    /// @param repository Git repository root
    /// @param parentType first channel more stable than the target
    /// @param adjacentStableRef target's adjacent stable ref
    /// @param refNamespace local or origin release-ref namespace
    /// @return newest-first upstream epoch merges
    private static @Unmodifiable List<EpochMerge> findUpstreamEpochMerges(
            Path repository,
            ReleaseType parentType,
            String adjacentStableRef,
            String refNamespace) {
        List<EpochMerge> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        ReleaseType channel = parentType;
        String channelRef = adjacentStableRef;
        while (channel != ReleaseType.STABLE) {
            for (EpochMerge merge : findChannelEpochMerges(repository, channel, channelRef, refNamespace)) {
                if (seen.add(merge.commit())) {
                    result.add(merge);
                }
            }
            channel = parentReleaseType(channel);
            @Nullable String nextChannelRef = optionalRef(
                    repository,
                    refNamespace + releaseBranchName(channel));
            if (nextChannelRef == null) {
                break;
            }
            channelRef = nextChannelRef;
        }
        result.sort(Comparator.comparingLong(EpochMerge::timestamp).reversed());
        return List.copyOf(result);
    }

    /// Finds epoch-producing merges in one release channel's first-parent history.
    ///
    /// @param repository Git repository root
    /// @param channel channel whose history is scanned
    /// @param channelRef channel commit or branch ref
    /// @param refNamespace local or origin release-ref namespace
    /// @return newest-first epoch merges
    private static @Unmodifiable List<EpochMerge> findChannelEpochMerges(
            Path repository,
            ReleaseType channel,
            String channelRef,
            String refNamespace) {
        @Unmodifiable Set<String> childHistory = firstParentHistoryOrEmpty(
                repository,
                childReleaseType(channel),
                refNamespace);
        @Unmodifiable Set<String> parentHistory = firstParentHistoryOrEmpty(
                repository,
                parentReleaseType(channel),
                refNamespace);
        List<EpochMerge> result = new ArrayList<>();
        for (MergeCommit mergeCommit : mergeCommitDetails(repository, channelRef)) {
            boolean directPromotion = !stableVersionChanged(repository, mergeCommit)
                    && childHistory.contains(mergeCommit.secondParent())
                    && !parentHistory.contains(mergeCommit.secondParent());
            if (directPromotion) {
                result.add(new EpochMerge(
                        mergeCommit.commit(),
                        mergeCommit.timestamp(),
                        channel,
                        mergeCommit.secondParent()));
            }
        }
        return List.copyOf(result);
    }

    /// Returns whether a Stable merge establishes a new stable-version epoch.
    ///
    /// A main-branch merge can be an ordinary integration merge, so its two-parent shape alone is not a release
    /// boundary. Stable promotions and hotfixes are required to advance `stableVersion`, which distinguishes their
    /// intended epoch from unrelated merges in legacy main history.
    ///
    /// @param repository Git repository root
    /// @param mergeCommit parsed Stable merge
    /// @return whether the merge changes `config/project.properties` `stableVersion`
    private static boolean stableVersionChanged(Path repository, MergeCommit mergeCommit) {
        @Nullable String previousVersion = optionalStableVersion(repository, mergeCommit.firstParent());
        @Nullable String currentVersion = optionalStableVersion(repository, mergeCommit.commit());
        return previousVersion != null && currentVersion != null && !previousVersion.equals(currentVersion);
    }

    /// Reads a stable-version property without making an old history fail when the file is absent.
    ///
    /// @param repository Git repository root
    /// @param commit commit to inspect
    /// @return the raw non-blank stable version, or `null` when it cannot be read
    private static @Nullable String optionalStableVersion(Path repository, String commit) {
        @Nullable String content = tryGit(repository, "show", commit + ":config/project.properties");
        if (content == null) {
            return null;
        }
        Properties properties = new Properties();
        try {
            properties.load(new StringReader(content));
        } catch (IOException exception) {
            return null;
        }
        @Nullable String stableVersion = properties.getProperty("stableVersion");
        return stableVersion == null || stableVersion.isBlank() ? null : stableVersion;
    }

    /// Resolves the complete inherited prefix represented by an upstream epoch merge.
    ///
    /// @param repository Git repository root
    /// @param targetType less stable channel being resolved
    /// @param merge upstream epoch merge
    /// @param refNamespace local or origin release-ref namespace
    /// @return counters inherited before the target channel counter is applied
    private static ReleaseCounters countersAtEpochMerge(
            Path repository,
            ReleaseType targetType,
            EpochMerge merge,
            String refNamespace) {
        @Nullable String parentRef = optionalRef(
                repository,
                refNamespace + releaseBranchName(parentReleaseType(merge.channel())));
        ReleaseCounters sourceCounters = parentRef == null
                ? new ReleaseCounters(0, 0, 0)
                : resolveReleaseCounters(repository, merge.channel(), merge.commit(), parentRef);
        return sourceCounters.withCounter(targetType, 0);
    }

    /// Resolves all counters represented by one channel snapshot.
    ///
    /// @param repository Git repository root
    /// @param channel channel containing the snapshot
    /// @param snapshot channel commit
    /// @param refNamespace local or origin release-ref namespace
    /// @return counters at the channel snapshot
    private static ReleaseCounters countersAtChannelSnapshot(
            Path repository,
            ReleaseType channel,
            String snapshot,
            String refNamespace) {
        @Nullable String parentRef = channel == ReleaseType.STABLE
                ? null
                : optionalRef(
                        repository,
                        refNamespace + releaseBranchName(parentReleaseType(channel)));
        return channel == ReleaseType.STABLE || parentRef == null
                ? new ReleaseCounters(0, 0, 0)
                : resolveReleaseCounters(repository, channel, snapshot, parentRef);
    }

    /// Compares the stable-version property at two commits.
    ///
    /// @param repository Git repository root
    /// @param left first commit
    /// @param right second commit
    /// @return whether both commits contain the same non-null stable version
    private static boolean hasSameStableVersion(Path repository, String left, String right) {
        @Nullable String leftVersion = optionalStableVersion(repository, left);
        @Nullable String rightVersion = optionalStableVersion(repository, right);
        return leftVersion != null && leftVersion.equals(rightVersion);
    }

    /// Counts timestamp-ordered target commits after an epoch while excluding structural synchronizations.
    ///
    /// @param repository Git repository root
    /// @param targetRef target commit or ref
    /// @param epochTimestamp boundary committer timestamp
    /// @param excludedCommits structural commits that do not consume a candidate number
    /// @return number of non-excluded commits, or `null` when timestamps are not strictly ordered
    private static @Nullable Integer countFirstParentCommitsAfter(
            Path repository,
            String targetRef,
            long epochTimestamp,
            Set<String> excludedCommits) {
        int count = 0;
        long newerTimestamp = Long.MAX_VALUE;
        for (TimedCommit commit : firstParentCommitDetails(repository, targetRef)) {
            if (commit.timestamp() > newerTimestamp) {
                return null;
            }
            if (commit.timestamp() < epochTimestamp) {
                return count;
            }
            if (commit.timestamp() == epochTimestamp) {
                return null;
            }
            if (!excludedCommits.contains(commit.commit())) {
                count++;
            }
            newerTimestamp = commit.timestamp();
        }
        return count;
    }

    /// Returns whether one commit is an ancestor of another.
    ///
    /// @param repository Git repository root
    /// @param ancestor candidate ancestor
    /// @param descendant candidate descendant
    /// @return whether the candidate ancestor is reachable from the descendant
    private static boolean isAncestor(Path repository, String ancestor, String descendant) {
        @Nullable String commonAncestor = tryGit(repository, "merge-base", ancestor, descendant);
        return ancestor.equals(commonAncestor);
    }

    /// Reads one commit's committer timestamp.
    ///
    /// @param repository Git repository root
    /// @param commit commit or ref
    /// @return Unix timestamp in seconds
    private static long commitTimestamp(Path repository, String commit) {
        String value = git(repository, "show", "-s", "--format=%ct", commit);
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            throw new IllegalStateException("Git returned an invalid commit timestamp: " + value, exception);
        }
    }

    /// Reads first-parent commits and committer timestamps in one Git invocation.
    ///
    /// @param repository Git repository root
    /// @param ref commit or branch ref
    /// @return newest-first timed commits
    private static @Unmodifiable List<TimedCommit> firstParentCommitDetails(Path repository, String ref) {
        String output = git(repository, "log", "--first-parent", "--format=%H%x09%ct", ref);
        if (output.isBlank()) {
            return List.of();
        }
        List<TimedCommit> result = new ArrayList<>();
        for (String line : output.lines().toList()) {
            String[] fields = line.split("\\t", -1);
            if (fields.length != 2) {
                continue;
            }
            try {
                result.add(new TimedCommit(fields[0], Long.parseLong(fields[1])));
            } catch (NumberFormatException exception) {
                throw new IllegalStateException("Git returned an invalid first-parent timestamp: " + line, exception);
            }
        }
        return List.copyOf(result);
    }

    /// Reads merge commits, parents, and timestamps from one first-parent history.
    ///
    /// @param repository Git repository root
    /// @param ref channel commit or branch ref
    /// @return newest-first parsed merge commits
    private static @Unmodifiable List<MergeCommit> mergeCommitDetails(Path repository, String ref) {
        String output = git(repository, "log", "--first-parent", "--merges", MERGE_RECORD_FORMAT, ref);
        if (output.isBlank()) {
            return List.of();
        }
        List<MergeCommit> result = new ArrayList<>();
        for (String line : output.lines().toList()) {
            @Nullable MergeCommit mergeCommit = parseMergeCommitLine(line);
            if (mergeCommit != null) {
                result.add(mergeCommit);
            }
        }
        return List.copyOf(result);
    }

    /// Parses one sentinel-terminated Git merge record.
    ///
    /// @param line formatted Git output
    /// @return parsed merge metadata, or `null` for malformed/non-merge output
    private static @Nullable MergeCommit parseMergeCommitLine(String line) {
        if (!line.endsWith("\u007f")) {
            return null;
        }
        String[] fields = line.substring(0, line.length() - 1).split("\\t", -1);
        if (fields.length != 3) {
            return null;
        }
        String[] parents = fields[2].split("\\s+");
        if (parents.length != 2) {
            return null;
        }
        try {
            return new MergeCommit(fields[0], Long.parseLong(fields[1]), parents[0], parents[1]);
        } catch (NumberFormatException exception) {
            throw new IllegalStateException("Git returned an invalid merge timestamp: " + fields[1], exception);
        }
    }

    /// Returns the immediately less stable channel.
    ///
    /// @param channel channel whose child is requested
    /// @return child channel
    /// @throws IllegalArgumentException when Stable has no child in this resolver
    private static ReleaseType childReleaseType(ReleaseType channel) {
        return switch (channel) {
            case STABLE -> ReleaseType.BETA;
            case BETA -> ReleaseType.ALPHA;
            case ALPHA -> ReleaseType.DEV;
            case DEV -> throw new IllegalArgumentException("Dev has no less stable release channel");
        };
    }

    /// Reproduces the legacy merge-base calculation exactly.
    ///
    /// @param repository Git repository root
    /// @param releaseType target release channel
    /// @param targetRef target commit or ref
    /// @param adjacentStableRef adjacent branch snapshot used by the old calculation
    /// @param refNamespace local or origin release-ref namespace selected by the caller
    /// @return legacy counters at the target commit
    private static ReleaseCounters resolveLegacyCounters(
            Path repository,
            ReleaseType releaseType,
            String targetRef,
            String adjacentStableRef,
            String refNamespace) {
        String base = mergeBase(repository, adjacentStableRef, targetRef);
        int distance = firstParentDistance(repository, base, targetRef);
        return switch (releaseType) {
            case STABLE -> new ReleaseCounters(0, 0, 0);
            case BETA -> new ReleaseCounters(distance, 0, 0);
            case ALPHA -> new ReleaseCounters(0, distance, 0);
            case DEV -> {
                @Nullable String betaRef = optionalRef(
                        repository,
                        refNamespace + releaseBranchName(ReleaseType.BETA));
                ReleaseCounters alphaCounters = betaRef == null
                        ? new ReleaseCounters(0, 0, 0)
                        : resolveReleaseCounters(
                                repository,
                                ReleaseType.ALPHA,
                                adjacentStableRef,
                                betaRef);
                yield new ReleaseCounters(
                        alphaCounters.betaCounter(),
                        alphaCounters.alphaCounter(),
                        distance);
            }
        };
    }

    /// Finds the first valid Stable-baseline synchronization after a target on the current target branch.
    ///
    /// @param repository Git repository root
    /// @param targetRef target commit or ref
    /// @param targetBranchRef current target-channel ref
    /// @param sourceRef current source-channel ref
    /// @param targetType target release channel
    /// @return next synchronization merge, or `null` when the target is not before one
    private static @Nullable EpochMerge findNextSyncMerge(
            Path repository,
            String targetRef,
            String targetBranchRef,
            String sourceRef,
            ReleaseType targetType) {
        @Unmodifiable List<String> targetHistory = firstParentCommits(repository, targetBranchRef);
        int targetIndex = targetHistory.indexOf(resolveCommit(repository, targetRef));
        if (targetIndex < 0) {
            return null;
        }
        @Unmodifiable List<EpochMerge> syncMerges = findSyncMerges(
                repository,
                targetBranchRef,
                sourceRef,
                targetType);
        for (int index = syncMerges.size() - 1; index >= 0; index--) {
            EpochMerge syncMerge = syncMerges.get(index);
            int syncIndex = targetHistory.indexOf(syncMerge.commit());
            if (syncIndex >= 0 && syncIndex < targetIndex) {
                return syncMerge;
            }
        }
        return null;
    }

    /// Reads Stable-baseline synchronizations in one target-channel first-parent history.
    ///
    /// A synchronization is a boundary only when it belongs to an uninterrupted adjacent-channel chain rooted in a
    /// Stable version change on Main. Ordinary reverse merges keep their normal first-parent position and do not reset
    /// counters.
    ///
    /// @param repository Git repository root
    /// @param targetRef target commit or branch whose first-parent merges are inspected
    /// @param sourceRef immediate parent-channel ref
    /// @param targetType target release channel
    /// @return newest-first Stable-baseline synchronization boundaries
    private static @Unmodifiable List<EpochMerge> findSyncMerges(
            Path repository,
            String targetRef,
            String sourceRef,
            ReleaseType targetType) {
        String refNamespace = releaseRefNamespace(sourceRef);
        List<EpochMerge> result = new ArrayList<>();
        for (MergeCommit mergeCommit : mergeCommitDetails(repository, targetRef)) {
            if (carriesStableBaseline(repository, mergeCommit, targetType, refNamespace)) {
                result.add(new EpochMerge(
                        mergeCommit.commit(),
                        mergeCommit.timestamp(),
                        targetType,
                        mergeCommit.secondParent()));
            }
        }
        return List.copyOf(result);
    }

    /// Verifies one uninterrupted Stable-baseline synchronization chain.
    ///
    /// Every merge result must use the second parent's Stable version. That second parent must itself be the
    /// synchronization merge on the adjacent channel, ending at a Main promotion or hotfix merge that changes the
    /// Stable version. This rejects ordinary reverse merges and synchronization delayed by unrelated commits while
    /// allowing a Beta candidate to retain its version when the new Stable merge returns to Beta.
    ///
    /// @param repository Git repository root
    /// @param candidate first candidate synchronization merge
    /// @param channel channel containing the candidate
    /// @param refNamespace local or origin release-ref namespace
    /// @return whether the candidate carries a complete Stable-baseline chain
    private static boolean carriesStableBaseline(
            Path repository,
            MergeCommit candidate,
            ReleaseType channel,
            String refNamespace) {
        MergeCommit currentMerge = candidate;
        ReleaseType currentChannel = channel;
        while (true) {
            if (currentChannel == ReleaseType.STABLE) {
                return hasSameStableVersion(repository, currentMerge.commit(), currentMerge.secondParent())
                        && stableVersionChanged(repository, currentMerge);
            }

            ReleaseType sourceChannel = parentReleaseType(currentChannel);
            @Unmodifiable Set<String> sourceHistory = firstParentHistoryOrEmpty(
                    repository,
                    sourceChannel,
                    refNamespace);
            if (!sourceHistory.contains(currentMerge.secondParent())) {
                return false;
            }
            if (!hasSameStableVersion(repository, currentMerge.commit(), currentMerge.secondParent())) {
                return false;
            }
            @Nullable MergeCommit sourceMerge = mergeCommitAt(repository, currentMerge.secondParent());
            if (sourceMerge == null) {
                return false;
            }
            currentMerge = sourceMerge;
            currentChannel = sourceChannel;
        }
    }

    /// Reads one exact commit as a two-parent merge.
    ///
    /// @param repository Git repository root
    /// @param commit commit to inspect
    /// @return parsed merge metadata, or `null` when the commit is not a two-parent merge
    private static @Nullable MergeCommit mergeCommitAt(Path repository, String commit) {
        String output = git(repository, "show", "-s", MERGE_RECORD_FORMAT, commit);
        return parseMergeCommitLine(output);
    }

    /// Reads a commit's complete first-parent chain from newest to oldest.
    ///
    /// @param repository Git repository root
    /// @param ref commit or branch to traverse
    /// @return immutable first-parent commit list
    private static @Unmodifiable List<String> firstParentCommits(Path repository, String ref) {
        String output = git(repository, "rev-list", "--first-parent", ref);
        return output.isBlank() ? List.of() : List.copyOf(output.lines().toList());
    }

    /// Reads a release branch's first-parent history when that ref is available.
    ///
    /// Missing adjacent refs are valid for callers that provide a deliberately small explicit checkout; an empty
    /// history simply disables topology-based boundary recognition for that side.
    ///
    /// @param repository Git repository root
    /// @param releaseType release channel
    /// @param refNamespace local or origin release-ref namespace
    /// @return immutable first-parent history, or an empty set when the ref is unavailable
    private static @Unmodifiable Set<String> firstParentHistoryOrEmpty(
            Path repository,
            ReleaseType releaseType,
            String refNamespace) {
        @Nullable String ref = optionalRef(repository, refNamespace + releaseBranchName(releaseType));
        return ref == null
                ? Set.of()
                : Set.copyOf(firstParentCommits(repository, ref));
    }

    /// Maps a release type to its permanent release branch.
    ///
    /// @param releaseType release channel
    /// @return exact branch name
    private static String releaseBranchName(ReleaseType releaseType) {
        return releaseType == ReleaseType.STABLE ? "main" : releaseType.getName();
    }

    /// Returns the adjacent, more stable release type.
    ///
    /// @param releaseType non-Stable release channel
    /// @return adjacent parent channel
    /// @throws IllegalArgumentException when Stable has no parent channel
    private static ReleaseType parentReleaseType(ReleaseType releaseType) {
        return switch (releaseType) {
            case STABLE -> throw new IllegalArgumentException("Stable has no adjacent parent release channel");
            case BETA -> ReleaseType.STABLE;
            case ALPHA -> ReleaseType.BETA;
            case DEV -> ReleaseType.ALPHA;
        };
    }

    /// Determines the release-ref namespace used by an explicit adjacent branch ref.
    ///
    /// @param adjacentStableRef explicit adjacent branch ref
    /// @return `refs/remotes/origin/` for official remote refs, otherwise `refs/heads/`
    private static String releaseRefNamespace(String adjacentStableRef) {
        return adjacentStableRef.startsWith("refs/remotes/origin/")
                ? "refs/remotes/origin/"
                : "refs/heads/";
    }

    /// Resolves one release branch in the namespace selected by the public caller.
    ///
    /// @param repository Git repository root
    /// @param releaseType release channel
    /// @param refNamespace local or origin release-ref namespace
    /// @return existing namespaced branch ref
    private static String releaseBranchRef(
            Path repository,
            ReleaseType releaseType,
            String refNamespace) {
        String ref = refNamespace + releaseBranchName(releaseType);
        if (optionalRef(repository, ref) == null) {
            throw new IllegalStateException("Cannot find Git ref " + ref);
        }
        return ref;
    }

    /// Returns the first parent of a commit when one exists.
    ///
    /// @param repository Git repository root
    /// @param commit candidate commit
    /// @return first parent, or `null` for a root commit
    private static @Nullable String optionalFirstParent(Path repository, String commit) {
        String parentsOutput = git(repository, "show", "-s", "--format=%P", commit);
        String[] parents = parentsOutput.isEmpty() ? new String[0] : parentsOutput.split("\\s+");
        return parents.length >= 1 ? parents[0] : null;
    }

    /// Executes Git and returns `null` instead of failing for a missing optional ref.
    ///
    /// @param repository Git repository root
    /// @param arguments Git arguments
    /// @return trimmed output, or `null` when Git exits unsuccessfully
    private static @Nullable String tryGit(Path repository, String... arguments) {
        return executeGit(repository, true, arguments);
    }

    /// Executes a required Git command.
    ///
    /// @param repository Git repository root
    /// @param arguments Git arguments
    /// @return trimmed standard output
    private static String git(Path repository, String... arguments) {
        @Nullable String output = executeGit(repository, false, arguments);
        if (output == null) {
            throw new IllegalStateException("Required Git command unexpectedly returned no output");
        }
        return output;
    }

    /// Starts Git and captures its combined standard and error output.
    ///
    /// @param repository Git repository root
    /// @param allowFailure whether a non-zero exit code returns `null`
    /// @param arguments Git arguments
    /// @return trimmed output, or `null` for an allowed failure
    private static @Nullable String executeGit(Path repository, boolean allowFailure, String... arguments) {
        @Nullable ResolutionCache cache = ACTIVE_CACHE.get();
        if (cache != null && cache.repository.equals(repository)) {
            return cache.execute(allowFailure, arguments);
        }
        return executeGitUncached(repository, allowFailure, arguments);
    }

    /// Executes one Git command without consulting the per-resolution cache.
    ///
    /// @param repository Git repository root
    /// @param allowFailure whether a non-zero exit code returns `null`
    /// @param arguments Git command arguments
    /// @return trimmed output, or `null` for an allowed failure
    private static @Nullable String executeGitUncached(
            Path repository,
            boolean allowFailure,
            String... arguments) {
        List<String> command = new ArrayList<>(arguments.length + 1);
        command.add("git");
        command.addAll(List.of(arguments));
        ProcessBuilder processBuilder = new ProcessBuilder(command)
                .directory(repository.toFile())
                .redirectErrorStream(true);
        try {
            Process process = processBuilder.start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                if (allowFailure) {
                    return null;
                }
                throw new IllegalStateException(
                        "Git command failed (" + exitCode + "): " + String.join(" ", command) + "\n" + output);
            }
            return output;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while running Git", exception);
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot run Git in " + repository, exception);
        }
    }

    /// Runs one public resolver operation with a fresh, call-scoped Git query cache.
    ///
    /// @param repository Git repository root
    /// @param operation resolver operation
    /// @param <T> operation result type
    /// @return operation result
    private static <T> T withResolutionCache(Path repository, Supplier<T> operation) {
        @Nullable ResolutionCache previous = ACTIVE_CACHE.get();
        ACTIVE_CACHE.set(new ResolutionCache(repository));
        try {
            return operation.get();
        } finally {
            if (previous == null) {
                ACTIVE_CACHE.remove();
            } else {
                ACTIVE_CACHE.set(previous);
            }
        }
    }
}
