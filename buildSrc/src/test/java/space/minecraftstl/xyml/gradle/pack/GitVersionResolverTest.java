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
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/// Verifies Git-distance release counters and feature versions with a real temporary repository.
@NotNullByDefault
final class GitVersionResolverTest {
    /// Temporary directory supplied by JUnit for isolated repositories.
    @TempDir
    private Path temporaryDirectory;

    /// Resolves every release depth and the six-component feature version from first-parent history.
    @Test
    void resolvesVersionsFromGitTopology() throws IOException {
        Path repository = createRepository();
        String stableVersion = "1.2.3";

        assertEquals(stableVersion, GitVersionResolver.readStableVersion(repository, "refs/heads/main"));
        assertEquals("1.2.3", GitVersionResolver.resolveReleaseVersion(
                repository, ReleaseType.STABLE, stableVersion, "refs/heads/main", null));
        assertEquals("1.2.3.2", GitVersionResolver.resolveReleaseVersion(
                repository, ReleaseType.BETA, stableVersion, "refs/heads/beta", "refs/heads/main"));
        assertEquals("1.2.3.0.1", GitVersionResolver.resolveReleaseVersion(
                repository, ReleaseType.ALPHA, stableVersion, "refs/heads/alpha", "refs/heads/beta"));
        assertEquals("1.2.3.0.0.2", GitVersionResolver.resolveReleaseVersion(
                repository, ReleaseType.DEV, stableVersion, "refs/heads/dev", "refs/heads/alpha"));
        assertEquals("1.2.3.0.0.5", GitVersionResolver.resolveFeatureVersion(
                repository,
                stableVersion,
                "refs/heads/feature/versioning",
                "refs/heads/dev",
                "refs/heads/alpha"));
        assertEquals("1.2.3.0.0.5", GitVersionResolver.resolveCurrentFeatureVersion(repository, stableVersion));

        Files.writeString(repository.resolve("uncommitted.txt"), "dirty\n", StandardCharsets.UTF_8);
        assertEquals("1.2.3.0.0.5", GitVersionResolver.resolveCurrentFeatureVersion(repository, stableVersion));
    }

    /// Classifies only the four exact release branch names as release builds.
    @Test
    void classifiesReleaseBranches() {
        assertEquals(ReleaseType.STABLE, GitVersionResolver.releaseTypeForBranch("main"));
        assertEquals(ReleaseType.BETA, GitVersionResolver.releaseTypeForBranch("beta"));
        assertEquals(ReleaseType.ALPHA, GitVersionResolver.releaseTypeForBranch("alpha"));
        assertEquals(ReleaseType.DEV, GitVersionResolver.releaseTypeForBranch("dev"));
        assertNull(GitVersionResolver.releaseTypeForBranch("feature/versioning"));
        assertNull(GitVersionResolver.releaseTypeForBranch(null));
    }

    /// Creates a repository whose release branches have known first-parent distances.
    ///
    /// @return initialized repository with main, beta, alpha, dev, and feature branches
    private Path createRepository() throws IOException {
        Path repository = temporaryDirectory.resolve("repository");
        Files.createDirectories(repository);
        git(repository, "init");
        git(repository, "config", "user.name", "XYML Test");
        git(repository, "config", "user.email", "xyml-test@example.invalid");
        git(repository, "checkout", "-b", "main");

        Path projectConfig = repository.resolve("config/project.properties");
        Files.createDirectories(projectConfig.getParent());
        Files.writeString(projectConfig, "stableVersion=1.2.3\n", StandardCharsets.UTF_8);
        commit(repository, "stable");

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

    /// Appends one marker and commits the resulting repository state.
    ///
    /// @param repository Git repository root
    /// @param marker unique commit marker and subject
    private static void commit(Path repository, String marker) throws IOException {
        Files.writeString(
                repository.resolve("history.txt"),
                marker + "\n",
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND);
        git(repository, "add", ".");
        git(repository, "commit", "-m", marker);
    }

    /// Executes Git in the temporary repository and fails the test on a non-zero exit code.
    ///
    /// @param repository Git repository root
    /// @param arguments Git arguments
    private static void git(Path repository, String... arguments) throws IOException {
        List<String> command = new ArrayList<>(arguments.length + 1);
        command.add("git");
        command.addAll(List.of(arguments));
        Process process = new ProcessBuilder(command)
                .directory(repository.toFile())
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        try {
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new IOException("Git command failed: " + String.join(" ", command) + "\n" + output);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while running Git", exception);
        }
    }
}
