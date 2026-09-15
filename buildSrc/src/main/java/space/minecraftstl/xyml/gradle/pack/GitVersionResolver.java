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

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/// Infers XYML release and feature versions from Git branch topology.
///
/// A release-channel counter is the number of first-parent commits between the target commit and its merge base with
/// the adjacent, more stable release branch. Dev release versions inherit the alpha counter from their alpha base.
/// Feature versions retain the six-component `x.y.z.0.0.d` shape and append their feature distance to the Dev counter.
@NotNullByDefault
public final class GitVersionResolver {
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

    /// Resolves the current checkout as a feature version relative to the local release branches.
    ///
    /// Local `dev` and `alpha` branches take precedence over remote-tracking refs. This makes an IDEA build reflect
    /// the developer's actual integration branches without performing network access during Gradle configuration.
    ///
    /// @param repository Git repository root
    /// @param stableVersion stable version stored by the current checkout
    /// @return six-component feature version using the Dev version shape
    public static String resolveCurrentFeatureVersion(Path repository, String stableVersion) {
        String devRef = preferredBranchRef(repository, "dev");
        String alphaRef = preferredBranchRef(repository, "alpha");
        return resolveFeatureVersion(repository, stableVersion, "HEAD", devRef, alphaRef);
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
        @Nullable String adjacentRef = adjacentBranch == null
                ? null
                : preferredBranchRef(repository, adjacentBranch);
        return resolveReleaseVersion(repository, releaseType, stableVersion, "HEAD", adjacentRef);
    }

    /// Resolves a feature or detached commit relative to its Dev merge base.
    ///
    /// @param repository Git repository root
    /// @param stableVersion stable version prefix
    /// @param headRef feature commit or ref
    /// @param devRef Dev branch ref used to find the feature base
    /// @param alphaRef Alpha branch ref used to infer the Dev counter at that base
    /// @return inferred feature version
    public static String resolveFeatureVersion(
            Path repository,
            String stableVersion,
            String headRef,
            String devRef,
            String alphaRef) {
        String devBase = mergeBase(repository, devRef, headRef);
        String alphaBase = mergeBase(repository, alphaRef, devBase);
        int inheritedDevDistance = firstParentDistance(repository, alphaBase, devBase);
        int featureDistance = firstParentDistance(repository, devBase, headRef);
        int combinedDistance = Math.addExact(inheritedDevDistance, featureDistance);
        String featureVersion = stableVersion + ".0.0." + combinedDistance;
        ReleaseVersionResolver.validateVersion(ReleaseType.DEV, featureVersion);
        return featureVersion;
    }

    /// Resolves a release-channel version for an arbitrary target commit.
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

        String base = mergeBase(repository, adjacentStableRef, targetRef);
        int distance = firstParentDistance(repository, base, targetRef);
        StringBuilder version = new StringBuilder(stableVersion);
        if (releaseType == ReleaseType.DEV) {
            version.append(".0.").append(resolveAlphaCounter(repository, adjacentStableRef));
        } else {
            for (int component = 4; component < releaseType.getVersionComponentCount(); component++) {
                version.append(".0");
            }
        }
        version.append('.').append(distance);
        String result = version.toString();
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
        String stableVersion = properties.getProperty("stableVersion");
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

    /// Finds a local release branch, falling back to its `origin` remote-tracking ref.
    ///
    /// @param repository Git repository root
    /// @param branchName exact release branch name
    /// @return an existing full ref name
    public static String preferredBranchRef(Path repository, String branchName) {
        List<String> candidates = List.of(
                "refs/heads/" + branchName,
                "refs/remotes/origin/" + branchName);
        for (String candidate : candidates) {
            if (tryGit(repository, "rev-parse", "--verify", "--quiet", candidate + "^{commit}") != null) {
                return candidate;
            }
        }
        throw new IllegalStateException("Cannot find local or origin/" + branchName + " Git ref");
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

    /// Resolves the alpha counter inherited by a Dev or feature version.
    ///
    /// @param repository Git repository root
    /// @param alphaRef alpha branch ref whose counter is inherited
    /// @return first-parent alpha distance from the beta merge base
    private static int resolveAlphaCounter(Path repository, String alphaRef) {
        String betaRef = preferredBranchRef(repository, "beta");
        String base = mergeBase(repository, betaRef, alphaRef);
        return firstParentDistance(repository, base, alphaRef);
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
        String output = executeGit(repository, false, arguments);
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
}
