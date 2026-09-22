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

import org.gradle.testfixtures.ProjectBuilder;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Verifies release promotion topology and worktree behavior against synthetic Git repositories.
@NotNullByDefault
final class ReleasePromotionTopologyTest {
    /// Accepts a complete carrier chain and rejects wrong carriers and baselines.
    @Test
    void verifiesBaselineCarrierChains(@TempDir Path directory) throws IOException {
        Repository repository = new Repository(directory);
        repository.writeStableVersion("1.0.0");
        String base = repository.commit("build: start 1.0.0");

        repository.git("checkout", "-b", "prepare", base);
        repository.writeStableVersion("1.0.1");
        String prepare = repository.commit("build: prepare stable version 1.0.1");

        repository.git("checkout", "main");
        repository.git("merge", "--no-ff", prepare, "-m", "merge: promote beta 1.0.0.1 to main 1.0.1");
        String mainRelease = repository.revParse("HEAD");

        repository.git("checkout", "-b", "beta", base);
        repository.git("merge", "--no-ff", mainRelease, "-m", "merge: sync stable 1.0.1 to beta");
        String betaSync = repository.revParse("HEAD");

        repository.git("checkout", "-b", "alpha", base);
        repository.git("merge", "--no-ff", betaSync, "-m", "merge: sync stable 1.0.1 to alpha");
        String alphaSync = repository.revParse("HEAD");

        repository.git("checkout", "-b", "dev", base);
        repository.git("merge", "--no-ff", alphaSync, "-m", "merge: sync stable 1.0.1 to dev");
        String devSync = repository.revParse("HEAD");

        ReleaseMergeAudit audit = new ReleaseMergeAudit(repository::gitOutputUnchecked);

        assertDoesNotThrow(() -> audit.requireBaselineCarrier("dev", devSync, "1.0.1"));
        assertDoesNotThrow(() -> audit.requireBaselineCarrier("alpha", alphaSync, "1.0.1"));
        assertDoesNotThrow(() -> audit.requireBaselineCarrier("beta", betaSync, "1.0.1"));
        assertDoesNotThrow(() -> audit.requireBaselineCarrier("main", mainRelease, "1.0.1"));
        assertDoesNotThrow(() -> audit.requireTwoParentMerge(mainRelease, base, prepare));
        assertDoesNotThrow(() -> audit.requireBaselineFromSecondParent(mainRelease, prepare));
        assertEquals("1.0.0", audit.stableVersion(base));
        assertEquals("1.0.1", audit.stableVersion(devSync));

        assertThrows(IllegalStateException.class, () -> audit.requireBaselineCarrier("dev", devSync, "1.0.2"));
        assertThrows(IllegalStateException.class, () -> audit.requireBaselineCarrier("beta", mainRelease, "1.0.1"));
        assertThrows(IllegalStateException.class, () -> audit.requireTwoParentMerge(base, base, prepare));
        assertThrows(IllegalStateException.class, () -> audit.requireTwoParentMerge(mainRelease, prepare, base));
        assertThrows(IllegalStateException.class, () -> audit.requireBaselineFromSecondParent(betaSync, base));

        repository.git("checkout", "-b", "unchanged", mainRelease);
        repository.writeText("README.md", "documentation\n");
        String unchanged = repository.commit("chore: keep the stable baseline");
        repository.git("checkout", "main");
        repository.git("merge", "--no-ff", unchanged, "-m", "merge: keep stable baseline");
        String unchangedMain = repository.revParse("HEAD");
        assertThrows(IllegalStateException.class,
                () -> audit.requireBaselineCarrier("main", unchangedMain, "1.0.1"));
    }

    /// Commits a staged merge from the temporary worktree instead of the controlling checkout.
    ///
    /// @param directory temporary test directory
    /// @throws IOException when Git cannot prepare the repository
    @Test
    void commitsInTemporaryWorktree(@TempDir Path directory) throws IOException {
        Repository repository = new Repository(directory.resolve("repository"));
        repository.writeText("base.txt", "base\n");
        String base = repository.commit("test: base");
        repository.writeText("main.txt", "main\n");
        String mainHead = repository.commit("test: main");

        Path checkout = directory.resolve("checkout");
        repository.git("worktree", "add", "--detach", checkout.toString(), base);
        repository.git("-C", checkout.toString(), "merge", "--no-ff", "--no-commit", mainHead);

        ReleasePromotionTask task = newPromotionTask(repository);
        String worktreeHead = task.commitMerge(checkout, "test: merge in temporary worktree");

        assertEquals(repository.gitOutput("-C", checkout.toString(), "rev-parse", "HEAD").trim(), worktreeHead);
        assertEquals(worktreeHead + " " + base + " " + mainHead,
                repository.gitOutput(
                        "-C", checkout.toString(), "rev-list", "--parents", "-n", "1", worktreeHead).trim());
        assertNotEquals(mainHead, worktreeHead);
    }

    /// Prepares a stable version in the temporary worktree without reading the controlling checkout.
    ///
    /// @param directory temporary test directory
    /// @throws IOException when Git cannot prepare the repository
    @Test
    void preparesStableVersionInTemporaryWorktree(@TempDir Path directory) throws IOException {
        Repository repository = new Repository(directory.resolve("repository"));
        repository.writeStableVersion("1.0.0");
        String base = repository.commit("test: base");
        repository.writeText("main.txt", "main\n");
        String mainHead = repository.commit("test: main");

        Path checkout = directory.resolve("checkout");
        repository.git("worktree", "add", "--detach", checkout.toString(), base);
        ReleasePromotionTask task = newPromotionTask(repository);
        String prepared = task.commitStableVersion(checkout, "1.0.1");

        assertEquals(repository.gitOutput("-C", checkout.toString(), "rev-parse", "HEAD").trim(), prepared);
        assertNotEquals(mainHead, prepared);
        assertEquals("1.0.1", GitVersionResolver.readStableVersion(checkout, "HEAD"));
    }

    /// Creates a promotion task with Gradle's process service for real temporary-worktree Git calls.
    ///
    /// @param repository synthetic repository
    /// @return configured promotion task
    private static ReleasePromotionTask newPromotionTask(Repository repository) {
        return ProjectBuilder.builder()
                .withProjectDir(repository.root.toFile())
                .build()
                .getTasks()
                .create("releasePromoteAlpha", ReleasePromotionTask.class);
    }

    /// Minimal synthetic repository used by the topology checks.
    @NotNullByDefault
    private static final class Repository {
        /// Repository root.
        private final Path root;

        /// Creates an empty repository with a deterministic identity.
        ///
        /// @param root repository directory
        /// @throws IOException when Git cannot be prepared
        Repository(Path root) throws IOException {
            this.root = root;
            Files.createDirectories(root);
            git("init", "--initial-branch=main");
            git("config", "user.name", "XYML Test");
            git("config", "user.email", "xyml-test@example.com");
            git("config", "commit.gpgsign", "false");
        }

        /// Writes the tracked stable version property file.
        ///
        /// @param version stable version to store
        /// @throws IOException when the file cannot be written
        void writeStableVersion(String version) throws IOException {
            writeText("config/project.properties", "stableVersion=" + version + "\n");
        }

        /// Writes one repository-relative text file.
        ///
        /// @param relativePath repository-relative path
        /// @param content file content
        /// @throws IOException when the file cannot be written
        void writeText(String relativePath, String content) throws IOException {
            Path file = root.resolve(relativePath);
            Files.createDirectories(file.getParent());
            Files.writeString(file, content, StandardCharsets.UTF_8);
        }

        /// Stages every change and commits it.
        ///
        /// @param message commit message
        /// @return commit id
        /// @throws IOException when Git cannot commit
        String commit(String message) throws IOException {
            git("add", "--all");
            git("commit", "-m", message);
            return revParse("HEAD");
        }

        /// Resolves one revision.
        ///
        /// @param revision revision to resolve
        /// @return commit id
        /// @throws IOException when Git cannot resolve the revision
        String revParse(String revision) throws IOException {
            return gitOutput("rev-parse", revision).trim();
        }

        /// Runs Git in the repository.
        ///
        /// @param arguments Git arguments
        /// @throws IOException when Git fails
        void git(String... arguments) throws IOException {
            gitOutput(arguments);
        }

        /// Runs Git and returns its combined output without checked exceptions.
        ///
        /// @param arguments Git arguments
        /// @return combined output
        String gitOutputUnchecked(String... arguments) {
            try {
                return gitOutput(arguments);
            } catch (IOException exception) {
                throw new IllegalStateException("Git query failed", exception);
            }
        }

        /// Runs Git and returns its combined output.
        ///
        /// @param arguments Git arguments
        /// @return combined output
        /// @throws IOException when Git fails or the process cannot start
        String gitOutput(String... arguments) throws IOException {
            List<String> command = new ArrayList<>(arguments.length + 1);
            command.add("git");
            command.addAll(List.of(arguments));
            Process process = new ProcessBuilder(command)
                    .directory(root.toFile())
                    .redirectErrorStream(true)
                    .start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            try {
                int exitCode = process.waitFor();
                if (exitCode != 0) {
                    throw new IOException("Git failed: " + String.join(" ", command) + "\n" + output);
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while running Git", exception);
            }
            return output;
        }
    }
}
