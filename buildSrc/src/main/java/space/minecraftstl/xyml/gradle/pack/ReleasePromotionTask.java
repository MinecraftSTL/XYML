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

import org.gradle.api.DefaultTask;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.TaskAction;
import org.gradle.process.ExecOperations;
import org.gradle.process.ExecSpec;
import org.gradle.work.DisableCachingByDefault;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import javax.inject.Inject;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/// Promotes one local release branch into its adjacent channel with a two-parent `--no-ff` merge.
///
/// The task never contacts a remote. It reads local branch refs, prepares every candidate commit in an isolated
/// temporary worktree, verifies the merge order, the stable baseline rules and the configured release gates, and only
/// then moves the local refs in one atomic transaction. Promoting `beta` to `main` additionally prepares the new stable
/// version on `beta` and carries that baseline through `main -> beta -> alpha -> dev`.
///
/// The release gates run on the staged merge before the merge commit exists, so a failing gate aborts the merge and no
/// merge commit is created. The policy checks mirror `config/release/validate-branch-flow.sh`, which stays the
/// authoritative check for pull requests; keep both implementations in sync when the branch model changes.
@NotNullByDefault
@DisableCachingByDefault(because = "The task moves local release branch refs and always evaluates their current tips")
public abstract class ReleasePromotionTask extends DefaultTask {
    /// Stable version property file inside the repository.
    private static final String STABLE_VERSION_FILE = "config/project.properties";

    /// Stable version property key.
    private static final String STABLE_VERSION_KEY = "stableVersion";

    /// Prefix of the release metadata line printed by the nested `validateReleaseMetadata` task.
    static final String RELEASE_VERSION_PREFIX = "XYML release version: ";

    /// Pattern that extracts the nested release version.
    private static final Pattern RELEASE_VERSION_PATTERN = Pattern.compile(RELEASE_VERSION_PREFIX + "([0-9.]+)");

    /// Maximum number of paths restored in one checkout refresh command.
    static final int REFRESH_CHUNK_SIZE = 100;

    /// Accepted `xyml.release.verify` modes.
    private static final String VERIFY_STANDARD = "standard";
    private static final String VERIFY_FULL = "full";

    /// Alpha branch name used by the stable baseline chain.
    private static final String ALPHA_BRANCH = "alpha";

    /// Dev branch name used by the stable baseline chain.
    private static final String DEV_BRANCH = "dev";

    /// Option that makes a nested Gradle build stop its single-use daemon before the Wrapper exits.
    private static final String NO_DAEMON_OPTION = "--no-daemon";

    /// Environment variables that would override an inferred release version.
    private static final List<String> OVERRIDING_ENVIRONMENT = List.of(
            "BUILD_NUMBER",
            "GITHUB_HEAD_REF",
            "GITHUB_REF_NAME",
            "CHANGE_BRANCH",
            "RELEASE_VERSION",
            "STABLE_VERSION");

    /// Process execution service used for Git and nested Gradle commands.
    private final ExecOperations execOperations;

    /// Branch that provides the merged commits.
    @Input
    public abstract Property<String> getSourceBranch();

    /// Branch that receives the promotion merge.
    @Input
    public abstract Property<String> getTargetBranch();

    /// Requested stable version increment for promotions into `main`.
    @Input
    @Optional
    public abstract Property<String> getStableIncrement();

    /// Verification mode: `standard` or `full`.
    @Input
    public abstract Property<String> getVerifyMode();

    /// Whether the release gates skip the root `test` task.
    @Input
    public abstract Property<Boolean> getSkipTests();

    /// Whether the task only reports the planned promotion.
    @Input
    public abstract Property<Boolean> getDryRun();

    /// Root directory of the controlling Git repository.
    @Internal
    public abstract DirectoryProperty getRepositoryDirectory();

    /// Creates a task that always evaluates the current local branch tips.
    ///
    /// @param execOperations process execution service used by this task
    @Inject
    public ReleasePromotionTask(ExecOperations execOperations) {
        this.execOperations = execOperations;
        getOutputs().upToDateWhen(ignored -> false);
    }

    /// Plans or performs one local channel promotion.
    ///
    /// @throws IOException when a Git or nested Gradle command cannot be executed
    @TaskAction
    public void run() throws IOException {
        Path repository = getRepositoryDirectory().get().getAsFile().toPath().toAbsolutePath().normalize();
        String sourceBranch = getSourceBranch().get();
        String targetBranch = getTargetBranch().get();
        ReleaseBranchFlow.Kind kind = ReleaseBranchFlow.kindOf(sourceBranch, targetBranch);
        if (kind != ReleaseBranchFlow.Kind.PROMOTION) {
            throw new IllegalArgumentException("Only promotions are exposed as tasks, but " + sourceBranch + " -> "
                    + targetBranch + " is a stable baseline sync that releasePromoteMain performs");
        }
        String verifyMode = verifyMode();
        boolean dryRun = getDryRun().get();

        String sourceTip = GitVersionResolver.resolveCommit(
                repository, GitBranchGradleTask.localBranchRef(sourceBranch));
        String targetTip = GitVersionResolver.resolveCommit(
                repository, GitBranchGradleTask.localBranchRef(targetBranch));
        String sourceStableVersion = GitVersionResolver.readStableVersion(repository, sourceTip);
        String targetStableVersion = GitVersionResolver.readStableVersion(repository, targetTip);

        @Nullable String stableTargetVersion = null;
        if (ReleaseBranchFlow.promotionChangesStableBaseline(kind, targetBranch)) {
            stableTargetVersion = StableVersionIncrement.targetVersion(
                    targetStableVersion, StableVersionIncrement.parseKind(requiredStableIncrement()));
        } else if (!sourceStableVersion.equals(targetStableVersion)) {
            throw new IllegalStateException("Promotion " + sourceBranch + " -> " + targetBranch
                    + " must not change the Stable baseline: " + sourceBranch + "=" + sourceStableVersion + ", "
                    + targetBranch + "=" + targetStableVersion + "; synchronize the stable baseline first");
        }

        @Unmodifiable List<String> movedBranches = movedBranches(sourceBranch, targetBranch, stableTargetVersion != null);
        getLogger().lifecycle("XYML promotion: {} {} -> {}", kind, sourceBranch, targetBranch);
        getLogger().lifecycle("XYML {} tip: {} (stableVersion {})", sourceBranch, sourceTip, sourceStableVersion);
        getLogger().lifecycle("XYML {} tip: {} (stableVersion {})", targetBranch, targetTip, targetStableVersion);
        if (stableTargetVersion != null) {
            getLogger().lifecycle("XYML target stable version: {}", stableTargetVersion);
        }
        checkCheckouts(repository, movedBranches, !dryRun);

        if (dryRun) {
            getLogger().lifecycle("XYML dry run: no commit and no ref will be changed");
        }

        Path temporaryRoot = Files.createTempDirectory("xyml-promote-" + targetBranch + "-");
        Path checkout = temporaryRoot.resolve("checkout");
        try {
            git(repository, false, "worktree", "add", "--detach", checkout.toString(), sourceTip);
            if (dryRun) {
                reportPlan(checkout, sourceBranch, targetBranch, targetTip, stableTargetVersion);
                return;
            }
            promote(repository, checkout, sourceBranch, targetBranch, sourceTip, targetTip, stableTargetVersion,
                    verifyMode);
        } finally {
            git(repository, true, "worktree", "remove", "--force", checkout.toString());
            git(repository, true, "worktree", "prune");
            deleteTree(temporaryRoot);
        }
    }

    /// Builds the promotion chain, runs the release gates, and moves the local refs.
    ///
    /// @param repository Git repository root
    /// @param checkout temporary detached worktree
    /// @param sourceBranch branch that provides the merged commits
    /// @param targetBranch branch that receives the promotion merge
    /// @param sourceTip current source branch tip
    /// @param targetTip current target branch tip
    /// @param stableTargetVersion requested stable version, or `null` for a content promotion
    /// @param verifyMode verification mode
    private void promote(
            Path repository,
            Path checkout,
            String sourceBranch,
            String targetBranch,
            String sourceTip,
            String targetTip,
            @Nullable String stableTargetVersion,
            String verifyMode) throws IOException {
        Map<String, String> expectedTips = new LinkedHashMap<>();
        Map<String, String> newTips = new LinkedHashMap<>();
        Map<String, String> versions = new LinkedHashMap<>();
        expectedTips.put(GitBranchGradleTask.localBranchRef(targetBranch), targetTip);

        String sourceCandidate = sourceTip;
        if (stableTargetVersion != null) {
            expectedTips.put(GitBranchGradleTask.localBranchRef(sourceBranch), sourceTip);
            sourceCandidate = commitStableVersion(checkout, stableTargetVersion);
            getLogger().lifecycle("XYML {} stable version preparation: {}", sourceBranch, sourceCandidate);
        }

        String sourceVersion = probeReleaseVersion(checkout, sourceBranch);
        versions.put(sourceBranch, sourceVersion);
        getLogger().lifecycle("XYML {} version: {}", sourceBranch, sourceVersion);

        git(checkout, false, "checkout", "--detach", targetTip);
        String mergeMessage = promotionMessage(sourceBranch, sourceVersion, targetBranch,
                stableTargetVersion == null ? sourceVersion : stableTargetVersion);
        stageMerge(checkout, sourceCandidate, mergeMessage);
        getLogger().lifecycle("XYML staged {} -> {} merge; running release gates", sourceBranch, targetBranch);
        runGates(checkout, targetBranch, verifyMode);
        String promotionMerge = commitMerge(checkout, mergeMessage);
        requireTwoParentMerge(repository, promotionMerge, targetTip, sourceCandidate);
        requireBaselineFromSecondParent(repository, promotionMerge, sourceCandidate);
        getLogger().lifecycle("XYML {} promotion merge: {}", targetBranch, promotionMerge);

        String targetVersion = probeReleaseVersion(checkout, targetBranch);
        String finalMessage = promotionMessage(sourceBranch, sourceVersion, targetBranch, targetVersion);
        if (!finalMessage.equals(mergeMessage)) {
            amendCommitMessage(checkout, finalMessage);
            promotionMerge = GitVersionResolver.resolveCommit(checkout, "HEAD");
            getLogger().lifecycle("XYML corrected promotion merge message: {}", promotionMerge);
        }
        versions.put(targetBranch, targetVersion);
        if (stableTargetVersion != null && !stableTargetVersion.equals(targetVersion)) {
            throw new IllegalStateException("Resolved stable version " + targetVersion
                    + " does not match the requested stable version " + stableTargetVersion);
        }
        getLogger().lifecycle("XYML {} version: {}", targetBranch, targetVersion);

        if (stableTargetVersion == null) {
            newTips.put(GitBranchGradleTask.localBranchRef(targetBranch), promotionMerge);
        } else {
            String alphaTip = GitVersionResolver.resolveCommit(
                    repository, GitBranchGradleTask.localBranchRef(ALPHA_BRANCH));
            String devTip = GitVersionResolver.resolveCommit(
                    repository, GitBranchGradleTask.localBranchRef(DEV_BRANCH));
            expectedTips.put(GitBranchGradleTask.localBranchRef(ALPHA_BRANCH), alphaTip);
            expectedTips.put(GitBranchGradleTask.localBranchRef(DEV_BRANCH), devTip);

            String betaSync = mergeCommit(checkout, sourceCandidate, promotionMerge,
                    syncMessage(stableTargetVersion, sourceBranch));
            verifyBaselineCarrier(repository, sourceBranch, betaSync, stableTargetVersion);
            versions.put(sourceBranch, probeReleaseVersion(checkout, sourceBranch));

            String alphaSync = mergeCommit(checkout, alphaTip, betaSync,
                    syncMessage(stableTargetVersion, ALPHA_BRANCH));
            verifyBaselineCarrier(repository, ALPHA_BRANCH, alphaSync, stableTargetVersion);
            versions.put(ALPHA_BRANCH, probeReleaseVersion(checkout, ALPHA_BRANCH));

            String devSync = mergeCommit(checkout, devTip, alphaSync,
                    syncMessage(stableTargetVersion, DEV_BRANCH));
            verifyBaselineCarrier(repository, DEV_BRANCH, devSync, stableTargetVersion);
            versions.put(DEV_BRANCH, probeReleaseVersion(checkout, DEV_BRANCH));

            newTips.put(GitBranchGradleTask.localBranchRef(sourceBranch), betaSync);
            newTips.put(GitBranchGradleTask.localBranchRef(targetBranch), promotionMerge);
            newTips.put(GitBranchGradleTask.localBranchRef(ALPHA_BRANCH), alphaSync);
            newTips.put(GitBranchGradleTask.localBranchRef(DEV_BRANCH), devSync);
        }

        checkCheckouts(repository, movedBranches(sourceBranch, targetBranch, stableTargetVersion != null), true);
        updateRefs(repository, expectedTips, newTips, "release: promote " + sourceBranch + " to " + targetBranch);
        refreshCheckouts(repository, expectedTips, newTips);
        auditTips(repository, newTips);
        reportPromotion(versions, expectedTips, newTips);
    }

    /// Writes the requested stable version into the tracked property file and commits it.
    ///
    /// @param checkout temporary detached worktree positioned on the source tip
    /// @param stableVersion requested stable version
    /// @return preparation commit id
    String commitStableVersion(Path checkout, String stableVersion) throws IOException {
        Path properties = checkout.resolve(STABLE_VERSION_FILE);
        String currentVersion = GitVersionResolver.readStableVersion(checkout, "HEAD");
        byte[] current = (STABLE_VERSION_KEY + "=" + currentVersion).getBytes(StandardCharsets.UTF_8);
        byte[] target = (STABLE_VERSION_KEY + "=" + stableVersion).getBytes(StandardCharsets.UTF_8);
        byte[] content = Files.readAllBytes(properties);
        int occurrences = countOccurrences(content, current);
        if (occurrences != 1) {
            throw new IllegalStateException(STABLE_VERSION_FILE + " must contain exactly one "
                    + STABLE_VERSION_KEY + "=" + currentVersion + " entry, found " + occurrences);
        }
        Files.write(properties, replaceOccurrence(content, current, target));
        git(checkout, false, "add", "--", STABLE_VERSION_FILE);
        git(checkout, false, "commit", "-F",
                writeMessage(checkout, "build: prepare stable version " + stableVersion));
        @Unmodifiable List<String> changed = changedPaths(checkout, "HEAD^", "HEAD");
        if (!changed.equals(List.of(STABLE_VERSION_FILE))) {
            throw new IllegalStateException("Stable version preparation must only change " + STABLE_VERSION_FILE
                    + " but changed " + changed);
        }
        requireCleanWorktree(checkout);
        return GitVersionResolver.resolveCommit(checkout, "HEAD");
    }

    /// Stages a two-parent merge in the current worktree without creating the merge commit.
    ///
    /// @param checkout temporary detached worktree positioned on the first parent
    /// @param sourceCommit commit merged into the worktree head
    /// @param message prepared merge message
    private void stageMerge(Path checkout, String sourceCommit, String message) {
        String messageFile = writeMessage(checkout, message);
        try {
            git(checkout, false, "merge", "--no-ff", "--no-commit", sourceCommit, "-F", messageFile);
        } catch (RuntimeException exception) {
            git(checkout, true, "merge", "--abort");
            throw exception;
        }
    }

    /// Commits the staged merge.
    ///
    /// @param checkout temporary detached worktree with a staged merge
    /// @param message merge message
    /// @return merge commit id
    String commitMerge(Path checkout, String message) {
        git(checkout, false, "commit", "-F", writeMessage(checkout, message));
        requireCleanWorktree(checkout);
        return GitVersionResolver.resolveCommit(checkout, "HEAD");
    }

    /// Creates a committed two-parent merge without running release gates.
    ///
    /// @param checkout temporary detached worktree
    /// @param firstParent commit that becomes the first parent
    /// @param secondParent commit that becomes the second parent
    /// @param message merge message
    /// @return merge commit id
    private String mergeCommit(
            Path checkout,
            String firstParent,
            String secondParent,
            String message) {
        git(checkout, false, "checkout", "--detach", firstParent);
        stageMerge(checkout, secondParent, message);
        return commitMerge(checkout, message);
    }

    /// Rewrites the current commit message without changing its parents or content.
    ///
    /// @param checkout temporary detached worktree
    /// @param message replacement message
    private void amendCommitMessage(Path checkout, String message) {
        git(checkout, false, "commit", "--amend", "-F", writeMessage(checkout, message));
    }

    /// Runs the configured release gates on the current worktree state.
    ///
    /// @param checkout temporary detached worktree
    /// @param targetBranch release branch represented by the staged merge
    /// @param verifyMode verification mode
    private void runGates(Path checkout, String targetBranch, String verifyMode) {
        Map<String, Object> environment = nestedEnvironment(targetBranch);
        runNestedGradle(checkout, environment, List.of("checkstyle", "checkTranslations"));
        if (!getSkipTests().get()) {
            runNestedGradle(checkout, environment, List.of("test"));
        }
        if (VERIFY_FULL.equals(verifyMode)) {
            runNestedGradle(checkout, environment, List.of("clean", "build"));
        }
    }

    /// Reads the release version inferred by the current worktree commit.
    ///
    /// @param checkout temporary detached worktree
    /// @param branch release branch represented by the current commit
    /// @return inferred release version
    private String probeReleaseVersion(Path checkout, String branch) {
        String output = captureNestedGradle(checkout, nestedEnvironment(branch),
                List.of(":XYML:validateReleaseMetadata", "--stacktrace"));
        Matcher matcher = RELEASE_VERSION_PATTERN.matcher(output);
        if (!matcher.find()) {
            throw new IllegalStateException("Nested build for " + branch + " did not report "
                    + RELEASE_VERSION_PREFIX + " but printed: " + output);
        }
        return matcher.group(1);
    }

    /// Verifies one generated merge uses the expected two parents in order.
    ///
    /// @param repository Git repository root
    /// @param commit merge commit to inspect
    /// @param firstParent expected first parent
    /// @param secondParent expected second parent
    private void requireTwoParentMerge(Path repository, String commit, String firstParent, String secondParent) {
        audit(repository).requireTwoParentMerge(commit, firstParent, secondParent);
    }

    /// Verifies one merge takes the stable baseline from its second parent.
    ///
    /// @param repository Git repository root
    /// @param commit merge commit to inspect
    /// @param secondParent expected baseline carrier
    private void requireBaselineFromSecondParent(Path repository, String commit, String secondParent) {
        audit(repository).requireBaselineFromSecondParent(commit, secondParent);
    }

    /// Verifies one sync merge is a valid carrier of the new stable baseline.
    ///
    /// @param repository Git repository root
    /// @param channel channel that received the sync merge
    /// @param commit sync merge commit
    /// @param stableVersion expected stable version
    private void verifyBaselineCarrier(Path repository, String channel, String commit, String stableVersion) {
        audit(repository).requireBaselineCarrier(channel, commit, stableVersion);
    }

    /// Creates a topology audit for one repository.
    ///
    /// @param repository Git repository root
    /// @return audit backed by this task's Git query
    private ReleaseMergeAudit audit(Path repository) {
        return new ReleaseMergeAudit(arguments -> gitOutput(repository, arguments));
    }

    /// Moves the prepared refs in one atomic transaction.
    ///
    /// @param repository Git repository root
    /// @param expectedTips expected current ref values
    /// @param newTips prepared ref values
    /// @param reason reflog reason
    private void updateRefs(
            Path repository,
            @Unmodifiable Map<String, String> expectedTips,
            @Unmodifiable Map<String, String> newTips,
            String reason) {
        StringBuilder input = new StringBuilder();
        for (Map.Entry<String, String> entry : newTips.entrySet()) {
            @Nullable String previous = expectedTips.get(entry.getKey());
            if (previous == null) {
                throw new IllegalStateException("Missing expected tip for " + entry.getKey());
            }
            input.append("update ").append(entry.getKey()).append(' ').append(entry.getValue()).append(' ')
                    .append(previous).append('\n');
        }
        List<String> command = List.of("git", "update-ref", "-m", reason, "--stdin");
        captureProcess(repository, command, null, input.toString(), false);
    }

    /// Refreshes every worktree that checks out one of the moved branches.
    ///
    /// @param repository Git repository root
    /// @param expectedTips previous ref values
    /// @param newTips prepared ref values
    private void refreshCheckouts(
            Path repository,
            @Unmodifiable Map<String, String> expectedTips,
            @Unmodifiable Map<String, String> newTips) {
        @Unmodifiable List<WorktreeCheckouts.Entry> entries = parseWorktrees(repository);
        for (Map.Entry<String, String> entry : newTips.entrySet()) {
            String branch = branchName(entry.getKey());
            for (Path path : WorktreeCheckouts.pathsCheckingOut(entries, branch)) {
                requireCleanWorktree(path);
                @Unmodifiable List<String> changed = changedPaths(repository, expectedTips.get(entry.getKey()),
                        entry.getValue());
                refreshCheckout(path, changed);
                getLogger().lifecycle("XYML refreshed {} tracked paths in {}", changed.size(), path);
            }
        }
    }

    /// Restores the changed tracked paths of one clean checkout.
    ///
    /// @param path checked-out worktree path
    /// @param changed changed repository-relative paths
    private void refreshCheckout(Path path, @Unmodifiable List<String> changed) {
        for (int start = 0; start < changed.size(); start += REFRESH_CHUNK_SIZE) {
            int end = Math.min(start + REFRESH_CHUNK_SIZE, changed.size());
            List<String> command = new ArrayList<>(
                    List.of("git", "restore", "--source=HEAD", "--staged", "--worktree", "--"));
            command.addAll(changed.subList(start, end));
            runProcess(path, command, null, false);
        }
    }

    /// Verifies the moved refs point at the prepared commits.
    ///
    /// @param repository Git repository root
    /// @param newTips prepared ref values
    private void auditTips(Path repository, @Unmodifiable Map<String, String> newTips) {
        Map<String, String> audited = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : newTips.entrySet()) {
            String branch = branchName(entry.getKey());
            String tip = GitVersionResolver.resolveCommit(repository, entry.getKey());
            if (!tip.equals(entry.getValue())) {
                throw new IllegalStateException("Expected " + entry.getKey() + " at " + entry.getValue()
                        + " but found " + tip);
            }
            String stableVersion = GitVersionResolver.readStableVersion(repository, tip);
            audited.put(branch, tip + " (stableVersion " + stableVersion + ")");
        }
        getLogger().lifecycle("XYML audited refs: {}", audited);
    }

    /// Reports the promotion result and the commands that were deliberately not executed.
    ///
    /// @param versions channel versions measured on the prepared commits
    /// @param expectedTips previous ref values
    /// @param newTips prepared ref values
    private void reportPromotion(
            @Unmodifiable Map<String, String> versions,
            @Unmodifiable Map<String, String> expectedTips,
            @Unmodifiable Map<String, String> newTips) {
        getLogger().lifecycle("XYML promotion complete; no remote ref was changed");
        for (Map.Entry<String, String> entry : newTips.entrySet()) {
            String branch = branchName(entry.getKey());
            getLogger().lifecycle("XYML {}: {} -> {} (version {})", branch, expectedTips.get(entry.getKey()),
                    entry.getValue(), versions.get(branch));
        }
        for (Map.Entry<String, String> entry : newTips.entrySet()) {
            String branch = branchName(entry.getKey());
            getLogger().lifecycle("XYML push when approved: git push origin {}:refs/heads/{}", branch, branch);
        }
        for (Map.Entry<String, String> entry : expectedTips.entrySet()) {
            String branch = branchName(entry.getKey());
            getLogger().lifecycle("XYML rollback {}: git update-ref {} {} {}", branch, entry.getKey(),
                    entry.getValue(), newTips.get(entry.getKey()));
        }
    }

    /// Reports the planned promotion without changing any ref.
    ///
    /// @param checkout temporary detached worktree positioned on the source tip
    /// @param sourceBranch branch that provides the merged commits
    /// @param targetBranch branch that receives the promotion merge
    /// @param targetTip current target branch tip
    /// @param stableTargetVersion requested stable version, or `null` for a content promotion
    private void reportPlan(
            Path checkout,
            String sourceBranch,
            String targetBranch,
            String targetTip,
            @Nullable String stableTargetVersion) {
        String sourceVersion = probeReleaseVersion(checkout, sourceBranch);
        getLogger().lifecycle("XYML {} current version: {}", sourceBranch, sourceVersion);
        git(checkout, false, "checkout", "--detach", targetTip);
        getLogger().lifecycle("XYML {} current version: {}", targetBranch,
                probeReleaseVersion(checkout, targetBranch));
        if (stableTargetVersion == null) {
            getLogger().lifecycle("XYML planned merge message: merge: promote {} {} to {} <measured>",
                    sourceBranch, sourceVersion, targetBranch);
            return;
        }
        getLogger().lifecycle("XYML planned preparation commit: build: prepare stable version {}",
                stableTargetVersion);
        getLogger().lifecycle("XYML planned merge message: {}",
                promotionMessage(sourceBranch, sourceVersion, targetBranch, stableTargetVersion));
        getLogger().lifecycle("XYML planned {} version: {}", targetBranch, stableTargetVersion);
        getLogger().lifecycle("XYML planned sync chain: main -> beta -> alpha -> dev");
    }

    /// Builds one promotion merge message.
    ///
    /// @param sourceBranch branch that provides the merged commits
    /// @param sourceVersion version of the merged source candidate
    /// @param targetBranch branch that receives the promotion merge
    /// @param targetVersion version of the prepared target merge
    /// @return merge message
    static String promotionMessage(String sourceBranch, String sourceVersion, String targetBranch,
            String targetVersion) {
        return "merge: promote " + sourceBranch + " " + sourceVersion + " to " + targetBranch + " " + targetVersion;
    }

    /// Builds one stable baseline sync message.
    ///
    /// @param stableVersion stable version carried downward
    /// @param branch channel that receives the sync merge
    /// @return merge message
    static String syncMessage(String stableVersion, String branch) {
        return "merge: sync stable " + stableVersion + " to " + branch;
    }

    /// Returns the branches that one promotion moves.
    ///
    /// @param sourceBranch branch that provides the merged commits
    /// @param targetBranch branch that receives the promotion merge
    /// @param promotesStable whether the promotion publishes a new stable version
    /// @return immutable branch names
    static @Unmodifiable List<String> movedBranches(String sourceBranch, String targetBranch,
            boolean promotesStable) {
        if (!promotesStable) {
            return List.of(targetBranch);
        }
        return List.of(sourceBranch, targetBranch, ALPHA_BRANCH, DEV_BRANCH);
    }

    /// Resolves the requested stable increment.
    ///
    /// @return configured increment keyword
    private String requiredStableIncrement() {
        @Nullable String increment = getStableIncrement().getOrNull();
        if (increment == null || increment.isBlank()) {
            throw new IllegalStateException(
                    "Stable promotions require -Pxyml.release.stableIncrement=major, minor, or patch");
        }
        return increment;
    }

    /// Validates the verification mode.
    ///
    /// @return configured verification mode
    private String verifyMode() {
        String mode = getVerifyMode().get();
        if (!VERIFY_STANDARD.equals(mode) && !VERIFY_FULL.equals(mode)) {
            throw new IllegalArgumentException("Unsupported verification mode: " + mode + " (expected "
                    + VERIFY_STANDARD + " or " + VERIFY_FULL + ")");
        }
        return mode;
    }

    /// Verifies that every worktree checking out a moved branch can be refreshed.
    ///
    /// @param repository Git repository root
    /// @param branches branches that the promotion moves
    /// @param failWhenDirty whether uncommitted changes abort the promotion
    private void checkCheckouts(Path repository, @Unmodifiable List<String> branches, boolean failWhenDirty) {
        @Unmodifiable List<WorktreeCheckouts.Entry> entries = parseWorktrees(repository);
        for (String branch : branches) {
            for (Path path : WorktreeCheckouts.pathsCheckingOut(entries, branch)) {
                String status = gitOutput(path, "status", "--porcelain").trim();
                if (status.isEmpty()) {
                    getLogger().lifecycle("XYML {} is checked out at {} and will be refreshed", branch, path);
                } else if (failWhenDirty) {
                    throw new IllegalStateException("Worktree " + path + " checks out " + branch
                            + " and has uncommitted changes; commit or stash them first:\n" + status);
                } else {
                    getLogger().warn("XYML worktree {} checks out {} and has uncommitted changes", path, branch);
                }
            }
        }
    }

    /// Fails when one worktree has uncommitted changes.
    ///
    /// @param path worktree path
    private void requireCleanWorktree(Path path) {
        String status = gitOutput(path, "status", "--porcelain").trim();
        if (!status.isEmpty()) {
            throw new IllegalStateException("Worktree " + path + " must be clean but reported:\n" + status);
        }
    }

    /// Parses the repository worktree list.
    ///
    /// @param repository Git repository root
    /// @return immutable worktree entries
    private @Unmodifiable List<WorktreeCheckouts.Entry> parseWorktrees(Path repository) {
        return WorktreeCheckouts.parse(gitOutput(repository, "worktree", "list", "--porcelain"));
    }

    /// Lists the tracked paths that differ between two commits.
    ///
    /// @param repository Git repository root
    /// @param from base commit
    /// @param to target commit
    /// @return immutable repository-relative paths
    private @Unmodifiable List<String> changedPaths(Path repository, String from, String to) {
        List<String> paths = new ArrayList<>();
        for (String line : gitOutput(repository, "diff", "--name-only", from, to).split("\n", -1)) {
            if (!line.isBlank()) {
                paths.add(line);
            }
        }
        return List.copyOf(paths);
    }

    /// Resolves the short branch name of one local ref.
    ///
    /// @param ref fully qualified local branch ref
    /// @return short branch name
    static String branchName(String ref) {
        return ref.substring(ref.lastIndexOf('/') + 1);
    }

    /// Writes one Git message file inside the temporary worktree parent.
    ///
    /// @param checkout temporary detached worktree
    /// @param message commit message
    /// @return absolute message file path
    private static String writeMessage(Path checkout, String message) {
        Path file = checkout.getParent().resolve("git-message.txt");
        try {
            Files.writeString(file, message + "\n", StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot write Git message file: " + file, exception);
        }
        return file.toString();
    }

    /// Counts non-overlapping occurrences of one byte pattern.
    ///
    /// @param content searched content
    /// @param pattern byte pattern
    /// @return occurrence count
    static int countOccurrences(byte[] content, byte[] pattern) {
        int count = 0;
        int index = 0;
        while (true) {
            int found = indexOf(content, pattern, index);
            if (found < 0) {
                return count;
            }
            count++;
            index = found + pattern.length;
        }
    }

    /// Replaces every occurrence of one byte pattern.
    ///
    /// @param content searched content
    /// @param pattern byte pattern
    /// @param replacement replacement bytes
    /// @return replaced content
    static byte[] replaceOccurrence(byte[] content, byte[] pattern, byte[] replacement) {
        ByteArrayOutputStream output = new ByteArrayOutputStream(content.length);
        int index = 0;
        while (index < content.length) {
            int found = indexOf(content, pattern, index);
            if (found < 0) {
                output.write(content, index, content.length - index);
                break;
            }
            output.write(content, index, found - index);
            output.writeBytes(replacement);
            index = found + pattern.length;
        }
        return output.toByteArray();
    }

    /// Finds one byte pattern from a start offset.
    ///
    /// @param content searched content
    /// @param pattern byte pattern
    /// @param from start offset
    /// @return match offset, or `-1` when the pattern is absent
    private static int indexOf(byte[] content, byte[] pattern, int from) {
        if (pattern.length == 0) {
            throw new IllegalArgumentException("Empty byte pattern");
        }
        for (int index = from; index + pattern.length <= content.length; index++) {
            boolean matched = true;
            for (int offset = 0; offset < pattern.length; offset++) {
                if (content[index + offset] != pattern[offset]) {
                    matched = false;
                    break;
                }
            }
            if (matched) {
                return index;
            }
        }
        return -1;
    }

    /// Builds the nested Gradle environment for one release branch.
    ///
    /// @param branch release branch name
    /// @return complete nested process environment
    private static Map<String, Object> nestedEnvironment(String branch) {
        @Nullable ReleaseType releaseType = GitVersionResolver.releaseTypeForBranch(branch);
        if (releaseType == null) {
            throw new IllegalArgumentException("Unsupported release branch: " + branch);
        }
        Map<String, Object> environment = new HashMap<>(System.getenv());
        for (String name : OVERRIDING_ENVIRONMENT) {
            environment.remove(name);
        }
        environment.put("BRANCH_NAME", branch);
        environment.put("RELEASE_CHANNEL", releaseType.getName());
        environment.put("JAVA_HOME", System.getProperty("java.home"));
        return environment;
    }

    /// Runs the nested Wrapper with inherited output.
    ///
    /// @param checkout temporary detached worktree
    /// @param environment complete nested environment
    /// @param arguments Gradle arguments
    private void runNestedGradle(Path checkout, Map<String, Object> environment, List<String> arguments) {
        runProcess(checkout,
                GitBranchGradleTask.nestedGradleCommand(checkout, isWindows(), withNoDaemon(arguments)),
                environment, false);
    }

    /// Runs the nested Wrapper and returns its combined output.
    ///
    /// @param checkout temporary detached worktree
    /// @param environment complete nested environment
    /// @param arguments Gradle arguments
    /// @return combined process output
    private String captureNestedGradle(Path checkout, Map<String, Object> environment, List<String> arguments) {
        return captureProcess(checkout,
                GitBranchGradleTask.nestedGradleCommand(checkout, isWindows(), withNoDaemon(arguments)),
                environment, null, false);
    }

    /// Adds `--no-daemon` so a nested Gradle gate cannot leave a long-lived daemon
    /// holding the outer process output streams.
    ///
    /// @param arguments Gradle arguments
    /// @return immutable arguments that request a single-use daemon for the nested build
    static @Unmodifiable List<String> withNoDaemon(@Unmodifiable List<String> arguments) {
        if (arguments.contains(NO_DAEMON_OPTION)) {
            return List.copyOf(arguments);
        }
        List<String> result = new ArrayList<>(arguments.size() + 1);
        result.addAll(arguments);
        result.add(NO_DAEMON_OPTION);
        return List.copyOf(result);
    }

    /// Reports whether the current platform uses the Windows Wrapper command.
    ///
    /// @return `true` on Windows
    static boolean isWindows() {
        return System.getProperty("os.name", "").startsWith("Windows");
    }

    /// Executes Git and fails on a non-zero exit value unless failures are tolerated.
    ///
    /// @param workingDirectory process working directory
    /// @param ignoreExitValue whether a non-zero result is tolerated
    /// @param arguments Git arguments
    private void git(Path workingDirectory, boolean ignoreExitValue, String... arguments) {
        runProcess(workingDirectory, gitCommand(arguments), null, ignoreExitValue);
    }

    /// Executes Git and returns its combined output.
    ///
    /// @param workingDirectory process working directory
    /// @param arguments Git arguments
    /// @return combined process output
    private String gitOutput(Path workingDirectory, String... arguments) {
        return captureProcess(workingDirectory, gitCommand(arguments), null, null, false);
    }

    /// Builds one Git command line.
    ///
    /// @param arguments Git arguments
    /// @return immutable command line
    private static @Unmodifiable List<String> gitCommand(String... arguments) {
        List<String> command = new ArrayList<>(arguments.length + 1);
        command.add("git");
        command.addAll(List.of(arguments));
        return List.copyOf(command);
    }

    /// Runs one process with inherited output.
    ///
    /// @param workingDirectory process working directory
    /// @param command executable and arguments
    /// @param environment complete process environment, or `null` to inherit
    /// @param ignoreExitValue whether a non-zero result is tolerated
    private void runProcess(
            Path workingDirectory,
            @Unmodifiable List<String> command,
            @Nullable Map<String, Object> environment,
            boolean ignoreExitValue) {
        if (ignoreExitValue) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            execOperations.exec(spec -> configureProcess(
                    spec, workingDirectory, command, true, environment, output, null));
            return;
        }
        execOperations.exec(spec -> configureProcess(
                spec, workingDirectory, command, false, environment, null, null));
    }

    /// Runs one process and returns its combined output.
    ///
    /// @param workingDirectory process working directory
    /// @param command executable and arguments
    /// @param environment complete process environment, or `null` to inherit
    /// @param standardInput optional standard input
    /// @param ignoreExitValue whether a non-zero result is tolerated
    /// @return combined process output
    private String captureProcess(
            Path workingDirectory,
            @Unmodifiable List<String> command,
            @Nullable Map<String, Object> environment,
            @Nullable String standardInput,
            boolean ignoreExitValue) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try {
            execOperations.exec(spec -> configureProcess(
                    spec, workingDirectory, command, ignoreExitValue, environment, output, standardInput));
        } catch (RuntimeException exception) {
            throw new IllegalStateException(
                    "Command failed: " + String.join(" ", command) + "\n" + output, exception);
        }
        return output.toString(StandardCharsets.UTF_8);
    }

    /// Applies one process configuration.
    ///
    /// @param spec Gradle process specification
    /// @param workingDirectory process working directory
    /// @param command executable and arguments
    /// @param ignoreExitValue whether a non-zero result is tolerated
    /// @param environment complete process environment, or `null` to inherit
    /// @param capturedOutput optional capture stream
    /// @param standardInput optional standard input
    private static void configureProcess(
            ExecSpec spec,
            Path workingDirectory,
            @Unmodifiable List<String> command,
            boolean ignoreExitValue,
            @Nullable Map<String, Object> environment,
            @Nullable ByteArrayOutputStream capturedOutput,
            @Nullable String standardInput) {
        spec.setWorkingDir(workingDirectory);
        spec.commandLine(command);
        spec.setIgnoreExitValue(ignoreExitValue);
        if (environment != null) {
            spec.setEnvironment(environment);
        }
        if (capturedOutput != null) {
            spec.setStandardOutput(capturedOutput);
            spec.setErrorOutput(capturedOutput);
        }
        if (standardInput != null) {
            spec.setStandardInput(new ByteArrayInputStream(standardInput.getBytes(StandardCharsets.UTF_8)));
        }
    }

    /// Deletes one task-owned directory tree without following external links.
    ///
    /// @param root task-owned directory
    private static void deleteTree(Path root) {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot delete temporary directory: " + root, exception);
        }
    }
}
