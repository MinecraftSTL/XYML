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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Verifies Git-derived release epochs and feature versions with real temporary repositories.
@NotNullByDefault
final class GitVersionResolverTest {
    /// Fixed Unix timestamp used by deterministic repository fixtures.
    private static final long FIXTURE_BASE_TIMESTAMP = 1_700_000_000L;

    /// Temporary directory supplied by JUnit for isolated repositories.
    @TempDir
    private Path temporaryDirectory;

    /// Last timestamp assigned to a fixture commit or merge.
    private long fixtureTimestamp = FIXTURE_BASE_TIMESTAMP;

    /// Preserves the original merge-base calculation for histories without release-boundary merges.
    @Test
    void resolvesLegacyVersionsFromGitTopology() throws IOException {
        Path repository = createLegacyRepository();
        String stableVersion = "1.2.3";

        assertEquals(stableVersion, GitVersionResolver.readStableVersion(repository, "refs/heads/main"));
        assertEquals("1.2.3.2", GitVersionResolver.resolveReleaseVersion(
                repository, ReleaseType.BETA, stableVersion, "refs/heads/beta", "refs/heads/main"));
        assertEquals("1.2.3.0.1", GitVersionResolver.resolveReleaseVersion(
                repository, ReleaseType.ALPHA, stableVersion, "refs/heads/alpha", "refs/heads/beta"));
        assertEquals("1.2.3.0.1.2", GitVersionResolver.resolveReleaseVersion(
                repository, ReleaseType.DEV, stableVersion, "refs/heads/dev", "refs/heads/alpha"));
        assertEquals("1.2.3.0.1.2.", GitVersionResolver.resolveCurrentFeatureVersion(repository, stableVersion));

        Files.writeString(repository.resolve("uncommitted.txt"), "dirty\n", StandardCharsets.UTF_8);
        assertEquals("1.2.3.0.1.2.", GitVersionResolver.resolveCurrentFeatureVersion(repository, stableVersion));

        Files.delete(repository.resolve("uncommitted.txt"));
        git(repository, "checkout", "dev");
        commit(repository, "dev-3");
        merge(repository, "feature/versioning", "integrate feature into dev");
        git(repository, "checkout", "feature/versioning");
        assertEquals("1.2.3.0.1.2.", GitVersionResolver.resolveCurrentFeatureVersion(repository, stableVersion));
        merge(repository, "dev", "merge latest dev into feature");
        assertEquals("1.2.3.0.1.4.", GitVersionResolver.resolveCurrentFeatureVersion(repository, stableVersion));
    }

    /// Resolves versions from the checked-out permanent release branches.
    @Test
    void resolvesCurrentReleaseBranchVersions() throws IOException {
        Path repository = createLegacyRepository();
        String stableVersion = "1.2.3";

        git(repository, "checkout", "main");
        assertEquals("1.2.3", GitVersionResolver.resolveCurrentReleaseVersion(
                repository, ReleaseType.STABLE, stableVersion));
        git(repository, "checkout", "beta");
        assertEquals("1.2.3.2", GitVersionResolver.resolveCurrentReleaseVersion(
                repository, ReleaseType.BETA, stableVersion));
        git(repository, "checkout", "alpha");
        assertEquals("1.2.3.0.1", GitVersionResolver.resolveCurrentReleaseVersion(
                repository, ReleaseType.ALPHA, stableVersion));
        git(repository, "checkout", "dev");
        assertEquals("1.2.3.0.1.2", GitVersionResolver.resolveCurrentReleaseVersion(
                repository, ReleaseType.DEV, stableVersion));
        String devTip = revision(repository, "HEAD");
        commit(repository, "dev-3");
        git(repository, "checkout", "--detach", devTip);
        assertEquals("1.2.3.0.1.2", GitVersionResolver.resolveCurrentReleaseVersion(
                repository, ReleaseType.DEV, stableVersion));
    }

    /// Classifies only the four permanent release branch names.
    @Test
    void classifiesReleaseBranches() {
        assertEquals(ReleaseType.STABLE, GitVersionResolver.releaseTypeForBranch("main"));
        assertEquals(ReleaseType.BETA, GitVersionResolver.releaseTypeForBranch("beta"));
        assertEquals(ReleaseType.ALPHA, GitVersionResolver.releaseTypeForBranch("alpha"));
        assertEquals(ReleaseType.DEV, GitVersionResolver.releaseTypeForBranch("dev"));
        assertNull(GitVersionResolver.releaseTypeForBranch("feature/versioning"));
        assertNull(GitVersionResolver.releaseTypeForBranch(null));
    }

    /// Falls back to a complete Origin release base instead of mixing local Dev with Origin Alpha.
    @Test
    void keepsFeatureReleaseNamespacesSeparate() throws IOException {
        Path repository = createLegacyRepository();
        git(repository, "update-ref", "refs/remotes/origin/alpha", "refs/heads/alpha");
        git(repository, "branch", "-D", "alpha");

        assertEquals("1.2.3.2.", GitVersionResolver.resolveCurrentFeatureVersion(repository, "1.2.3"));
    }

    /// Prefers a current local Dev/Alpha pair over a complete but stale origin namespace.
    @Test
    void prefersLocalFeatureReleaseRefs() throws IOException {
        Path repository = createLegacyRepository();
        for (String branch : List.of("main", "beta", "alpha", "dev")) {
            git(repository, "update-ref", "refs/remotes/origin/" + branch, "refs/heads/" + branch);
        }
        git(repository, "checkout", "dev");
        commit(repository, "local-dev-3");
        git(repository, "checkout", "-b", "feature/local-dev");
        commit(repository, "local-feature-only");
        assertEquals("1.2.3.0.1.3.", GitVersionResolver.resolveCurrentFeatureVersion(repository, "1.2.3"));
    }

    /// Uses Alpha as the base when no Dev or Beta history is reachable.
    @Test
    void featureVersionUsesAlphaBase() throws IOException {
        Path repository = createLegacyRepository();
        git(repository, "checkout", "-b", "feature/from-alpha", "alpha");
        commit(repository, "feature-from-alpha");

        assertEquals("1.2.3.0.1.", GitVersionResolver.resolveCurrentFeatureVersion(repository, "1.2.3"));
    }

    /// Uses Beta as the base when no Dev or Alpha history is reachable.
    @Test
    void featureVersionUsesBetaBase() throws IOException {
        Path repository = createLegacyRepository();
        git(repository, "checkout", "-b", "feature/from-beta", "beta");
        commit(repository, "feature-from-beta");

        assertEquals("1.2.3.2.", GitVersionResolver.resolveCurrentFeatureVersion(repository, "1.2.3"));
    }

    /// Uses Stable as the base when no testing-channel history is reachable.
    @Test
    void featureVersionUsesStableBase() throws IOException {
        Path repository = createLegacyRepository();
        git(repository, "checkout", "-b", "feature/from-main", "main");
        commit(repository, "feature-from-main");

        assertEquals("1.2.3.", GitVersionResolver.resolveCurrentFeatureVersion(repository, "1.2.3"));
    }

    /// Returns the fixed fallback when no release branch exists.
    @Test
    void featureVersionFallsBackWithoutReleaseRefs() throws IOException {
        Path repository = createLegacyRepository();
        git(repository, "branch", "-D", "main", "beta", "alpha", "dev");

        assertEquals("0.0.0.0.0.0.", GitVersionResolver.resolveCurrentFeatureVersion(repository, "1.2.3"));
    }

    /// Keeps B in A's old epoch while the first Dev commit after promoting A starts the new Alpha epoch at zero.
    @Test
    void preservesDevHistoryAcrossSelectiveAlphaPromotion() throws IOException {
        Path repository = createEpochRepository();
        String stableVersion = "1.2.3";
        String commitA = revision(repository, "HEAD");
        commit(repository, "dev-b");
        String commitB = revision(repository, "HEAD");

        git(repository, "checkout", "alpha");
        merge(repository, commitA, "promote dev A to alpha");
        assertEquals("1.2.3.0.1", GitVersionResolver.resolveCurrentReleaseVersion(
                repository, ReleaseType.ALPHA, stableVersion));

        git(repository, "checkout", "dev");
        commit(repository, "dev-c");
        String commitC = revision(repository, "HEAD");

        assertEquals("1.2.3.0.0.0", resolveDev(repository, stableVersion, commitA));
        assertEquals("1.2.3.0.0.1", resolveDev(repository, stableVersion, commitB));
        assertEquals("1.2.3.0.1.0", resolveDev(repository, stableVersion, commitC));

        git(repository, "checkout", "-b", "feature/from-b", commitB);
        commit(repository, "feature-from-b-1");
        commit(repository, "feature-from-b-2");
        assertEquals("1.2.3.0.0.1.", GitVersionResolver.resolveCurrentFeatureVersion(repository, stableVersion));
    }

    /// Falls back to merge-base history when Git timestamps cannot order a promotion and a Dev commit.
    @Test
    void doesNotGuessAcrossEqualCrossBranchTimestamps() throws IOException {
        Path repository = createEpochRepository();
        String devCandidate = revision(repository, "HEAD");

        git(repository, "checkout", "alpha");
        long sharedTimestamp = nextFixtureTimestamp(repository);
        gitWithTimestamp(
                repository,
                sharedTimestamp,
                "merge",
                "--no-ff",
                devCandidate,
                "-m",
                "promote Dev candidate to Alpha");
        fixtureTimestamp = sharedTimestamp;
        git(repository, "checkout", "dev");
        commitAt(repository, "dev-at-promotion-second", sharedTimestamp);
        commit(repository, "dev-after-ambiguous-second");

        assertEquals("1.2.3.0.1.2", GitVersionResolver.resolveCurrentReleaseVersion(
                repository, ReleaseType.DEV, "1.2.3"));
    }

    /// Uses the Dev first-parent base and ignores commits that only exist on the feature branch.
    @Test
    void featureVersionExcludesFeatureOnlyCommits() throws IOException {
        Path repository = createEpochRepository();
        String stableVersion = "1.2.3";

        commit(repository, "dev-feature-base-1");
        commit(repository, "dev-feature-base-2");
        commit(repository, "dev-feature-base-3");
        git(repository, "checkout", "-b", "feature/third-dev-commit");
        commit(repository, "feature-only-1");
        commit(repository, "feature-only-2");
        commit(repository, "feature-only-3");

        assertEquals("1.2.3.0.0.3.", GitVersionResolver.resolveCurrentFeatureVersion(repository, stableVersion));
    }

    /// Propagates later Beta and Alpha promotions to Dev without routine reverse synchronization.
    @Test
    void cascadesBetaEpochToLowerChannelsWithoutReverseSync() throws IOException {
        Path repository = createEpochRepository();
        String stableVersion = "1.2.3";
        String devCandidate = revision(repository, "HEAD");

        git(repository, "checkout", "alpha");
        merge(repository, devCandidate, "promote Dev candidate to Alpha");
        String alphaCandidate = revision(repository, "HEAD");

        git(repository, "checkout", "beta");
        merge(repository, alphaCandidate, "promote Alpha candidate to Beta");
        assertEquals("1.2.3.1", GitVersionResolver.resolveCurrentReleaseVersion(
                repository, ReleaseType.BETA, stableVersion));

        git(repository, "checkout", "dev");
        commit(repository, "dev-after-beta-promotion");
        assertEquals("1.2.3.1.0.0", GitVersionResolver.resolveCurrentReleaseVersion(
                repository, ReleaseType.DEV, stableVersion));
        String laterDevCandidate = revision(repository, "HEAD");

        git(repository, "checkout", "alpha");
        merge(repository, laterDevCandidate, "promote later Dev candidate to Alpha");
        assertEquals("1.2.3.1.1", GitVersionResolver.resolveCurrentReleaseVersion(
                repository, ReleaseType.ALPHA, stableVersion));

        git(repository, "checkout", "dev");
        commit(repository, "dev-after-alpha-promotion");
        assertEquals("1.2.3.1.1.0", GitVersionResolver.resolveCurrentReleaseVersion(
                repository, ReleaseType.DEV, stableVersion));
    }

    /// Counts an unchanged Alpha-to-Dev merge as ordinary history instead of treating it as an epoch boundary.
    @Test
    void doesNotTreatRoutineReverseMergeAsSynchronizationBoundary() throws IOException {
        Path repository = createEpochRepository();
        String stableVersion = "1.2.3";
        String devCandidate = revision(repository, "HEAD");

        git(repository, "checkout", "alpha");
        merge(repository, devCandidate, "promote Dev candidate to Alpha");
        git(repository, "checkout", "dev");
        commit(repository, "dev-after-alpha-promotion");
        assertEquals("1.2.3.0.1.0", GitVersionResolver.resolveCurrentReleaseVersion(
                repository, ReleaseType.DEV, stableVersion));

        merge(repository, "alpha", "ordinary alpha to dev merge");
        assertEquals("1.2.3.0.1.1", GitVersionResolver.resolveCurrentReleaseVersion(
                repository, ReleaseType.DEV, stableVersion));
    }

    /// Rejects a merge whose result invents a Stable version not carried by its second parent.
    @Test
    void doesNotAcceptMergeEditedStableVersionAsSynchronization() throws IOException {
        Path repository = createEpochRepository();
        Path projectConfig = repository.resolve("config/project.properties");
        String devCandidate = revision(repository, "HEAD");

        git(repository, "checkout", "alpha");
        merge(repository, devCandidate, "promote Dev candidate to Alpha");
        git(repository, "checkout", "dev");
        long mergeTimestamp = nextFixtureTimestamp(repository);
        gitWithTimestamp(repository, mergeTimestamp, "merge", "--no-ff", "--no-commit", "alpha");
        Files.writeString(projectConfig, "stableVersion=1.2.4\n", StandardCharsets.UTF_8);
        git(repository, "add", "config/project.properties");
        gitWithTimestamp(repository, mergeTimestamp, "commit", "-m", "invent stable version during merge");
        fixtureTimestamp = mergeTimestamp;

        assertEquals("1.2.4.0.0.1", GitVersionResolver.resolveCurrentReleaseVersion(
                repository, ReleaseType.DEV, "1.2.4"));
    }

    /// Rejects a Stable synchronization delayed by an ordinary source-channel commit.
    @Test
    void doesNotAcceptDelayedStableBaselineSynchronization() throws IOException {
        Path repository = createEpochRepository();
        Path projectConfig = repository.resolve("config/project.properties");

        git(repository, "checkout", "main");
        git(repository, "checkout", "-b", "hotfix/stable-2.0.0");
        Files.writeString(projectConfig, "stableVersion=2.0.0\n", StandardCharsets.UTF_8);
        commit(repository, "advance-to-stable-2.0.0");
        git(repository, "checkout", "main");
        merge(repository, "hotfix/stable-2.0.0", "promote stable 2.0.0");

        git(repository, "checkout", "beta");
        merge(repository, "main", "sync stable baseline to beta");
        commit(repository, "ordinary-beta-commit-after-sync");
        git(repository, "checkout", "alpha");
        merge(repository, "beta", "delayed stable baseline sync to alpha");

        assertEquals("2.0.0.0.1", GitVersionResolver.resolveCurrentReleaseVersion(
                repository, ReleaseType.ALPHA, "2.0.0"));
    }

    /// Clears every lower counter while a new Stable baseline is synchronized through adjacent channels.
    @Test
    void clearsCountersDuringStableBaselineSynchronization() throws IOException {
        Path repository = createEpochRepository();
        Path projectConfig = repository.resolve("config/project.properties");

        commit(repository, "old-dev-before-stable-sync-1");
        commit(repository, "old-dev-before-stable-sync-2");

        git(repository, "checkout", "main");
        git(repository, "checkout", "-b", "hotfix/stable-2.0.0");
        Files.writeString(projectConfig, "stableVersion=2.0.0\n", StandardCharsets.UTF_8);
        commit(repository, "advance-to-stable-2.0.0");
        git(repository, "checkout", "main");
        merge(repository, "hotfix/stable-2.0.0", "promote stable 2.0.0");

        git(repository, "checkout", "beta");
        merge(repository, "main", "sync stable baseline to beta");
        assertEquals("2.0.0.0", GitVersionResolver.resolveCurrentReleaseVersion(
                repository, ReleaseType.BETA, "2.0.0"));

        git(repository, "checkout", "alpha");
        merge(repository, "beta", "sync stable baseline to alpha");
        assertEquals("2.0.0.0.0", GitVersionResolver.resolveCurrentReleaseVersion(
                repository, ReleaseType.ALPHA, "2.0.0"));

        git(repository, "checkout", "dev");
        merge(repository, "alpha", "sync stable baseline to dev");
        assertEquals("2.0.0.0.0.0", GitVersionResolver.resolveCurrentReleaseVersion(
                repository, ReleaseType.DEV, "2.0.0"));

        git(repository, "checkout", "-b", "feature/after-stable-sync");
        commit(repository, "feature-only-after-stable-sync");
        assertEquals("2.0.0.0.0.0.", GitVersionResolver.resolveCurrentFeatureVersion(repository, "2.0.0"));
    }

    /// Clears counters when a Beta candidate becomes Stable before the unchanged baseline returns through Beta.
    @Test
    void clearsCountersAfterStableCandidatePromotion() throws IOException {
        Path repository = createEpochRepository();
        Path projectConfig = repository.resolve("config/project.properties");

        git(repository, "checkout", "beta");
        Files.writeString(projectConfig, "stableVersion=2.0.0\n", StandardCharsets.UTF_8);
        commit(repository, "prepare-stable-2.0.0-on-beta");
        git(repository, "checkout", "main");
        merge(repository, "beta", "promote beta to stable 2.0.0");

        git(repository, "checkout", "beta");
        merge(repository, "main", "return stable baseline to beta");
        assertEquals("2.0.0.0", GitVersionResolver.resolveCurrentReleaseVersion(
                repository, ReleaseType.BETA, "2.0.0"));
        git(repository, "checkout", "alpha");
        merge(repository, "beta", "sync stable baseline to alpha");
        assertEquals("2.0.0.0.0", GitVersionResolver.resolveCurrentReleaseVersion(
                repository, ReleaseType.ALPHA, "2.0.0"));
        git(repository, "checkout", "dev");
        merge(repository, "alpha", "sync stable baseline to dev");
        assertEquals("2.0.0.0.0.0", GitVersionResolver.resolveCurrentReleaseVersion(
                repository, ReleaseType.DEV, "2.0.0"));
    }

    /// Resolves one Dev commit against the current Alpha release branch.
    ///
    /// @param repository Git repository root
    /// @param stableVersion stable version prefix
    /// @param targetRef Dev commit or ref
    /// @return complete Dev version
    private static String resolveDev(Path repository, String stableVersion, String targetRef) {
        return GitVersionResolver.resolveReleaseVersion(
                repository,
                ReleaseType.DEV,
                stableVersion,
                targetRef,
                "refs/heads/alpha");
    }

    /// Creates a release graph initialized by a Stable baseline propagated through every adjacent channel.
    ///
    /// @return initialized repository checked out on Dev
    private Path createEpochRepository() throws IOException {
        resetFixtureClock();
        Path repository = initializeRepository("epoch-repository", "1.2.2");
        Path projectConfig = repository.resolve("config/project.properties");
        git(repository, "branch", "beta");
        git(repository, "branch", "alpha");
        git(repository, "branch", "dev");

        git(repository, "checkout", "-b", "hotfix/stable-1.2.3");
        Files.writeString(projectConfig, "stableVersion=1.2.3\n", StandardCharsets.UTF_8);
        commit(repository, "advance-to-stable-1.2.3");
        git(repository, "checkout", "main");
        merge(repository, "hotfix/stable-1.2.3", "promote stable 1.2.3");
        git(repository, "checkout", "beta");
        merge(repository, "main", "sync stable baseline to beta");
        git(repository, "checkout", "alpha");
        merge(repository, "beta", "sync stable baseline to alpha");
        git(repository, "checkout", "dev");
        merge(repository, "alpha", "sync stable baseline to dev");
        return repository;
    }

    /// Creates a repository whose release branches have known first-parent distances and no epoch merges.
    ///
    /// @return initialized repository checked out on a feature branch
    private Path createLegacyRepository() throws IOException {
        resetFixtureClock();
        Path repository = initializeRepository("legacy-repository", "1.2.3");

        git(repository, "checkout", "-b", "beta");
        commit(repository, "beta-1");
        commit(repository, "beta-2");
        git(repository, "checkout", "-b", "alpha");
        commit(repository, "alpha-1");
        git(repository, "checkout", "-b", "dev");
        commit(repository, "dev-1");
        commit(repository, "dev-2");
        git(repository, "checkout", "-b", "feature/versioning");
        commit(repository, "feature-1");
        commit(repository, "feature-2");
        commit(repository, "feature-3");
        return repository;
    }

    /// Initializes a temporary Git repository with one Stable commit.
    ///
    /// @param directoryName fixture directory name
    /// @param stableVersion initial Stable version
    /// @return initialized repository checked out on Main
    private Path initializeRepository(String directoryName, String stableVersion) throws IOException {
        Path repository = temporaryDirectory.resolve(directoryName);
        Files.createDirectories(repository);
        git(repository, "init");
        git(repository, "config", "user.name", "XYML Test");
        git(repository, "config", "user.email", "xyml-test@example.invalid");
        git(repository, "checkout", "-b", "main");

        Path projectConfig = repository.resolve("config/project.properties");
        Files.createDirectories(projectConfig.getParent());
        Files.writeString(projectConfig, "stableVersion=" + stableVersion + "\n", StandardCharsets.UTF_8);
        commitAt(repository, "initial-stable", FIXTURE_BASE_TIMESTAMP);
        return repository;
    }

    /// Creates and commits one uniquely named fixture file.
    ///
    /// @param repository Git repository root
    /// @param name unique commit name and subject
    private void commit(Path repository, String name) throws IOException {
        commitAt(repository, name, nextFixtureTimestamp(repository));
    }

    /// Creates and commits one fixture file at a deterministic timestamp.
    ///
    /// @param repository Git repository root
    /// @param name unique commit name and subject
    /// @param timestamp Unix timestamp in seconds
    private void commitAt(Path repository, String name, long timestamp) throws IOException {
        Path historyDirectory = repository.resolve("history");
        Files.createDirectories(historyDirectory);
        Files.writeString(historyDirectory.resolve(name + ".txt"), name + "\n", StandardCharsets.UTF_8);
        git(repository, "add", ".");
        gitWithTimestamp(repository, timestamp, "commit", "-m", name);
        fixtureTimestamp = Math.max(fixtureTimestamp, timestamp);
    }

    /// Creates a two-parent merge at a deterministic timestamp.
    ///
    /// @param repository Git repository root
    /// @param sourceRef source commit or branch
    /// @param subject merge subject
    private void merge(Path repository, String sourceRef, String subject) throws IOException {
        gitWithTimestamp(
                repository,
                nextFixtureTimestamp(repository),
                "merge",
                "--no-ff",
                sourceRef,
                "-m",
                subject);
    }

    /// Resets the fixture clock before creating an independent repository.
    private void resetFixtureClock() {
        fixtureTimestamp = FIXTURE_BASE_TIMESTAMP;
    }

    /// Returns a timestamp strictly newer than the checked-out parent commit.
    ///
    /// @param repository Git repository root
    /// @return next deterministic Unix timestamp in seconds
    private long nextFixtureTimestamp(Path repository) throws IOException {
        long parentTimestamp = commitTimestamp(repository, "HEAD");
        fixtureTimestamp = Math.max(fixtureTimestamp, parentTimestamp) + 1;
        return fixtureTimestamp;
    }

    /// Reads one commit's committer timestamp.
    ///
    /// @param repository Git repository root
    /// @param ref commit or ref
    /// @return Unix timestamp in seconds
    private static long commitTimestamp(Path repository, String ref) throws IOException {
        String output = runGit(repository, "show", "-s", "--format=%ct", ref).trim();
        try {
            return Long.parseLong(output);
        } catch (NumberFormatException exception) {
            throw new IOException("Git returned an invalid commit timestamp: " + output, exception);
        }
    }

    /// Resolves a Git ref to its full commit SHA.
    ///
    /// @param repository Git repository root
    /// @param ref Git ref or revision
    /// @return full commit SHA
    private static String revision(Path repository, String ref) throws IOException {
        return runGit(repository, "rev-parse", "--verify", ref + "^{commit}").trim();
    }

    /// Executes Git in the temporary repository.
    ///
    /// @param repository Git repository root
    /// @param arguments Git arguments
    private static void git(Path repository, String... arguments) throws IOException {
        runGit(repository, arguments);
    }

    /// Executes Git with a deterministic author and committer timestamp.
    ///
    /// @param repository Git repository root
    /// @param timestamp Unix timestamp in seconds
    /// @param arguments Git arguments
    private static void gitWithTimestamp(
            Path repository,
            long timestamp,
            String... arguments) throws IOException {
        runGit(repository, timestamp, arguments);
    }

    /// Executes Git and returns its standard output.
    ///
    /// @param repository Git repository root
    /// @param arguments Git arguments
    /// @return Git output
    private static String runGit(Path repository, String... arguments) throws IOException {
        return runGit(repository, null, arguments);
    }

    /// Executes Git with an optional timestamp and returns its standard output.
    ///
    /// @param repository Git repository root
    /// @param timestamp Unix timestamp in seconds, or `null` to preserve the environment
    /// @param arguments Git arguments
    /// @return Git output
    private static String runGit(
            Path repository,
            @Nullable Long timestamp,
            String... arguments) throws IOException {
        List<String> command = new ArrayList<>(arguments.length + 1);
        command.add("git");
        command.addAll(List.of(arguments));
        ProcessBuilder processBuilder = new ProcessBuilder(command)
                .directory(repository.toFile())
                .redirectErrorStream(true);
        if (timestamp != null) {
            String value = "@" + timestamp + " +0000";
            processBuilder.environment().put("GIT_AUTHOR_DATE", value);
            processBuilder.environment().put("GIT_COMMITTER_DATE", value);
        }
        Process process = processBuilder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        try {
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new IOException("Git command failed: " + String.join(" ", command) + "\n" + output);
            }
            return output;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while running Git", exception);
        }
    }
}
