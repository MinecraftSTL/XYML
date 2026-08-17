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

import com.sun.jna.Platform;
import com.sun.jna.platform.win32.Advapi32Util;
import com.sun.jna.platform.win32.WinReg;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.TaskAction;
import org.gradle.process.ExecOperations;
import org.gradle.process.ExecSpec;
import org.gradle.work.DisableCachingByDefault;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import javax.inject.Inject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/// Runs Gradle against the latest commit of one release branch in an isolated temporary Git worktree.
///
/// The task refreshes all four `origin` release refs together, infers a version from their topology, and passes that
/// exact version to the nested build. Build artifacts are copied back into the configured output directory. Run tasks
/// omit an output directory and keep the temporary worktree alive until the launched application exits.
@NotNullByDefault
@DisableCachingByDefault(because = "The task fetches remote Git refs and always evaluates their latest commits")
public abstract class GitBranchGradleTask extends DefaultTask {
    /// Windows Internet Settings registry key containing the user's system proxy.
    private static final String INTERNET_SETTINGS_KEY =
            "Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings";

    /// Process execution service used for Git and nested Gradle commands.
    private final ExecOperations execOperations;

    /// Release branch to refresh and check out.
    @Input
    public abstract Property<String> getBranchName();

    /// Release type represented by the branch.
    @Input
    public abstract Property<ReleaseType> getReleaseType();

    /// Arguments passed to the nested Gradle Wrapper.
    @Input
    public abstract ListProperty<String> getGradleArguments();

    /// Whether the task must refresh remote refs before resolving the target commit.
    @Input
    public abstract Property<Boolean> getFetchRemote();

    /// Optional explicit Git HTTP proxy. When absent on Windows, the system proxy is detected from the registry.
    @Input
    @Optional
    public abstract Property<String> getGitProxy();

    /// Root directory of the controlling Git repository.
    @Internal
    public abstract DirectoryProperty getRepositoryDirectory();

    /// Optional destination for artifacts copied from `XYML/build/libs` after a successful nested build.
    @OutputDirectory
    @Optional
    public abstract DirectoryProperty getArtifactDirectory();

    /// Creates a task that always checks the current remote state.
    ///
    /// @param execOperations process execution service used by this task
    @Inject
    public GitBranchGradleTask(ExecOperations execOperations) {
        this.execOperations = execOperations;
        getFetchRemote().convention(true);
        getOutputs().upToDateWhen(ignored -> false);
    }

    /// Fetches, checks out, versions, and executes the configured release-branch workflow.
    @TaskAction
    public void run() throws IOException {
        Path repository = getRepositoryDirectory().get().getAsFile().toPath().toAbsolutePath().normalize();
        String branchName = getBranchName().get();
        ReleaseType releaseType = getReleaseType().get();
        if (getFetchRemote().get()) {
            refreshReleaseRefs(repository);
        }

        String targetRef = "refs/remotes/origin/" + branchName;
        String commit = GitVersionResolver.resolveCommit(repository, targetRef);
        String stableVersion = GitVersionResolver.readStableVersion(repository, commit);
        String version = GitVersionResolver.resolveReleaseVersion(
                repository,
                releaseType,
                stableVersion,
                commit,
                adjacentStableRef(releaseType));

        getLogger().lifecycle("XYML {} branch commit: {}", branchName, commit);
        getLogger().lifecycle("XYML inferred {} version: {}", releaseType.getName(), version);

        Path temporaryRoot = Files.createTempDirectory("xyml-" + branchName + "-");
        Path checkout = temporaryRoot.resolve("checkout");
        try {
            executeGit(repository, false, "worktree", "add", "--detach", checkout.toString(), commit);
            executeNestedGradle(repository, checkout, branchName, releaseType, stableVersion, version);
            if (getArtifactDirectory().isPresent()) {
                copyArtifacts(repository, checkout, branchName, commit, version);
            }
        } finally {
            executeGit(repository, true, "worktree", "remove", "--force", checkout.toString());
            executeGit(repository, true, "worktree", "prune");
            deleteTree(temporaryRoot);
        }
    }

    /// Refreshes all release refs in one fetch so channel counters use a consistent remote snapshot.
    ///
    /// @param repository Git repository root
    private void refreshReleaseRefs(Path repository) {
        List<String> arguments = new ArrayList<>();
        arguments.add("fetch");
        arguments.add("--prune");
        arguments.add("origin");
        for (String branch : List.of("main", "beta", "alpha", "dev")) {
            arguments.add("+refs/heads/" + branch + ":refs/remotes/origin/" + branch);
        }

        String proxy = getGitProxy().getOrNull();
        if (proxy == null) {
            proxy = windowsSystemProxy();
        }
        List<String> command = new ArrayList<>();
        command.add("git");
        if (proxy != null) {
            getLogger().lifecycle("Using the Windows system proxy for the GitHub branch refresh.");
            command.add("-c");
            command.add("http.proxy=" + proxy);
        }
        command.addAll(arguments);
        execute(repository, command, false, null);
    }

    /// Runs the Gradle Wrapper inside the detached worktree with explicit release metadata.
    ///
    /// @param repository controlling Git repository root
    /// @param checkout temporary detached worktree
    /// @param branchName release branch name
    /// @param releaseType release type
    /// @param stableVersion stable version prefix
    /// @param version complete inferred release version
    private void executeNestedGradle(
            Path repository,
            Path checkout,
            String branchName,
            ReleaseType releaseType,
            String stableVersion,
            String version) {
        boolean windows = Platform.isWindows();
        List<String> command = new ArrayList<>();
        if (windows) {
            command.add("cmd.exe");
            command.add("/d");
            command.add("/c");
            command.add(checkout.resolve("gradlew.bat").toString());
        } else {
            command.add(checkout.resolve("gradlew").toString());
        }
        command.addAll(getGradleArguments().get());

        Map<String, Object> environment = new HashMap<>(System.getenv());
        environment.remove("BUILD_NUMBER");
        environment.remove("GITHUB_HEAD_REF");
        environment.remove("GITHUB_REF_NAME");
        environment.remove("CHANGE_BRANCH");
        environment.put("BRANCH_NAME", branchName);
        environment.put("RELEASE_CHANNEL", releaseType.getName());
        environment.put("RELEASE_VERSION", version);
        environment.put("STABLE_VERSION", stableVersion);
        environment.put("JAVA_HOME", System.getProperty("java.home"));
        environment.put("GRADLE_USER_HOME", repository.resolve(".gradle-user-home").toString());
        execute(checkout, command, false, environment);
    }

    /// Copies application artifacts and writes immutable build metadata.
    ///
    /// @param repository controlling Git repository root
    /// @param checkout temporary detached worktree
    /// @param branchName release branch name
    /// @param commit built commit SHA
    /// @param version inferred version
    private void copyArtifacts(
            Path repository,
            Path checkout,
            String branchName,
            String commit,
            String version) throws IOException {
        Path source = checkout.resolve("XYML/build/libs");
        if (!Files.isDirectory(source)) {
            throw new IOException("Nested build did not produce XYML/build/libs at " + source);
        }
        Path target = getArtifactDirectory().get().getAsFile().toPath().toAbsolutePath().normalize();
        Path allowedRoot = repository.resolve("build/channel-builds").toAbsolutePath().normalize();
        if (!target.startsWith(allowedRoot)) {
            throw new IOException("Channel artifacts must remain under " + allowedRoot + ": " + target);
        }

        deleteTree(target);
        Files.createDirectories(target);
        try (Stream<Path> paths = Files.walk(source)) {
            for (Path path : paths.toList()) {
                Path destination = target.resolve(source.relativize(path).toString());
                if (Files.isDirectory(path)) {
                    Files.createDirectories(destination);
                } else {
                    Files.copy(path, destination, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
        Files.writeString(
                target.resolve("build-info.properties"),
                "branch=" + branchName + "\ncommit=" + commit + "\nversion=" + version + "\n",
                StandardCharsets.UTF_8);
        getLogger().lifecycle("XYML {} artifacts: {}", branchName, target);
    }

    /// Executes Git in the controlling repository.
    ///
    /// @param repository Git repository root
    /// @param ignoreExitValue whether a non-zero result is tolerated
    /// @param arguments Git arguments
    private void executeGit(Path repository, boolean ignoreExitValue, String... arguments) {
        List<String> command = new ArrayList<>(arguments.length + 1);
        command.add("git");
        command.addAll(List.of(arguments));
        execute(repository, command, ignoreExitValue, null);
    }

    /// Executes a process with inherited output and an optional complete environment replacement.
    ///
    /// @param workingDirectory process working directory
    /// @param command executable and arguments
    /// @param ignoreExitValue whether a non-zero result is tolerated
    /// @param environment complete process environment, or `null` to inherit Gradle's environment
    private void execute(
            Path workingDirectory,
            List<String> command,
            boolean ignoreExitValue,
            @Nullable Map<String, Object> environment) {
        execOperations.exec(spec -> configureProcess(
                spec, workingDirectory, command, ignoreExitValue, environment));
    }

    /// Applies common process settings to one Gradle execution specification.
    ///
    /// @param spec process specification
    /// @param workingDirectory process working directory
    /// @param command executable and arguments
    /// @param ignoreExitValue whether a non-zero result is tolerated
    /// @param environment complete process environment, or `null` to inherit Gradle's environment
    private static void configureProcess(
            ExecSpec spec,
            Path workingDirectory,
            List<String> command,
            boolean ignoreExitValue,
            @Nullable Map<String, Object> environment) {
        spec.setWorkingDir(workingDirectory);
        spec.commandLine(command);
        spec.setIgnoreExitValue(ignoreExitValue);
        if (environment != null) {
            spec.setEnvironment(environment);
        }
    }

    /// Returns the adjacent, more stable remote-tracking ref for one release channel.
    ///
    /// @param releaseType target release type
    /// @return adjacent remote ref, or `null` for Stable
    private static @Nullable String adjacentStableRef(ReleaseType releaseType) {
        return switch (releaseType) {
            case STABLE -> null;
            case BETA -> "refs/remotes/origin/main";
            case ALPHA -> "refs/remotes/origin/beta";
            case DEV -> "refs/remotes/origin/alpha";
        };
    }

    /// Reads and normalizes the enabled Windows user proxy.
    ///
    /// @return Git-compatible proxy URL, or `null` when no static system proxy is enabled
    private static @Nullable String windowsSystemProxy() {
        if (!Platform.isWindows()) {
            return null;
        }
        try {
            int enabled = Advapi32Util.registryGetIntValue(
                    WinReg.HKEY_CURRENT_USER, INTERNET_SETTINGS_KEY, "ProxyEnable");
            if (enabled == 0) {
                return null;
            }
            String proxyServer = Advapi32Util.registryGetStringValue(
                    WinReg.HKEY_CURRENT_USER, INTERNET_SETTINGS_KEY, "ProxyServer");
            return normalizeProxyServer(proxyServer);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    /// Selects the HTTPS or HTTP endpoint from a Windows `ProxyServer` value.
    ///
    /// @param proxyServer registry value in direct or protocol-specific form
    /// @return normalized proxy URL, or `null` for an empty value
    static @Nullable String normalizeProxyServer(@Nullable String proxyServer) {
        if (proxyServer == null || proxyServer.isBlank()) {
            return null;
        }
        String selected = null;
        for (String entry : proxyServer.split(";")) {
            String trimmed = entry.trim();
            int separator = trimmed.indexOf('=');
            if (separator < 0) {
                selected = trimmed;
                break;
            }
            String protocol = trimmed.substring(0, separator).toLowerCase(Locale.ROOT);
            if ("https".equals(protocol) || selected == null && "http".equals(protocol)) {
                selected = trimmed.substring(separator + 1).trim();
                if ("https".equals(protocol)) {
                    break;
                }
            }
        }
        if (selected == null || selected.isBlank()) {
            return null;
        }
        if (selected.contains("://")) {
            return selected;
        }
        return "http://" + selected;
    }

    /// Deletes one task-owned directory tree without following external links.
    ///
    /// @param root task-owned directory
    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
