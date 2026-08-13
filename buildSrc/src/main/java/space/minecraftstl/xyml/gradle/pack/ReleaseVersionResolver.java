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

import java.util.Arrays;
import java.util.regex.Pattern;

/// Resolves and validates XYML versions for the four release channels and marks feature-branch builds.
///
/// Stable, beta, alpha, and development releases contain exactly three, four, five, and six canonical decimal
/// components respectively. An explicit release version is used for promotions whose parent counters are already
/// known. A build number supplies the last component for ordinary channel builds, with zero placeholders for parent
/// channels that have not yet produced a candidate. Only `main`, `beta`, `alpha`, and `dev` are release branches;
/// every other or unknown branch receives a trailing empty component.
@NotNullByDefault
public final class ReleaseVersionResolver {
    /// Canonical non-negative decimal component without leading zeroes.
    private static final Pattern DECIMAL_COMPONENT = Pattern.compile("0|[1-9][0-9]*");

    /// Prevents construction of this stateless resolver.
    private ReleaseVersionResolver() {
    }

    /// Resolves one build version from channel metadata and optional CI inputs.
    ///
    /// @param channel target release channel
    /// @param stableVersion current three-component stable baseline
    /// @param explicitVersion complete release version, or `null` to derive it
    /// @param buildNumber positive decimal CI build number, or `null` for a local build
    /// @param official whether missing CI version inputs must fail the build
    /// @param branchName current Git branch name, or `null` when it cannot be determined
    /// @return validated release version, marked with a trailing empty component outside the four release branches
    /// @throws IllegalArgumentException when any supplied version value violates the release model
    public static String resolve(
            ReleaseType channel,
            String stableVersion,
            @Nullable String explicitVersion,
            @Nullable String buildNumber,
            boolean official,
            @Nullable String branchName) {
        validateVersion(ReleaseType.STABLE, stableVersion);

        String resolvedVersion;
        if (explicitVersion != null) {
            validateVersion(channel, explicitVersion);
            if (!hasStablePrefix(explicitVersion, stableVersion)) {
                throw new IllegalArgumentException(
                        "Release version " + explicitVersion + " does not use stable baseline " + stableVersion);
            }
            resolvedVersion = explicitVersion;
        } else if (channel == ReleaseType.STABLE) {
            resolvedVersion = stableVersion;
        } else if (buildNumber != null) {
            if (!DECIMAL_COMPONENT.matcher(buildNumber).matches() || "0".equals(buildNumber)) {
                throw new IllegalArgumentException("BUILD_NUMBER must be a positive canonical decimal component");
            }
            resolvedVersion = derivedVersion(channel, stableVersion, buildNumber);
        } else {
            if (official) {
                throw new IllegalArgumentException(
                        "Official " + channel.getName() + " builds require RELEASE_VERSION or BUILD_NUMBER");
            }
            resolvedVersion = derivedVersion(channel, stableVersion, "0");
        }
        return isReleaseBranch(branchName) ? resolvedVersion : resolvedVersion + ".";
    }

    /// Validates an exact channel version.
    ///
    /// @param channel release channel defining the required component count
    /// @param version complete decimal version
    /// @throws IllegalArgumentException when the version is not canonical or has the wrong component count
    public static void validateVersion(ReleaseType channel, String version) {
        String[] components = version.split("\\.", -1);
        if (components.length != channel.getVersionComponentCount()
                || Arrays.stream(components).anyMatch(component -> !DECIMAL_COMPONENT.matcher(component).matches())) {
            throw new IllegalArgumentException(
                    channel.getName() + " versions must contain exactly " + channel.getVersionComponentCount()
                            + " canonical decimal components: " + version);
        }
    }

    /// Builds the channel suffix around one last component.
    ///
    /// @param channel non-stable release channel
    /// @param stableVersion stable version prefix
    /// @param lastComponent positive build number or local zero placeholder
    /// @return version with the exact channel depth
    private static String derivedVersion(ReleaseType channel, String stableVersion, String lastComponent) {
        StringBuilder version = new StringBuilder(stableVersion);
        for (int component = 4; component < channel.getVersionComponentCount(); component++) {
            version.append(".0");
        }
        return version.append('.').append(lastComponent).toString();
    }

    /// Returns whether an explicit release shares the configured stable prefix.
    ///
    /// @param releaseVersion complete explicit release version
    /// @param stableVersion configured stable baseline
    /// @return whether the first three components match exactly
    private static boolean hasStablePrefix(String releaseVersion, String stableVersion) {
        return releaseVersion.equals(stableVersion) || releaseVersion.startsWith(stableVersion + ".");
    }

    /// Returns whether a branch owns one of the four release channels.
    ///
    /// Branch names are exact and case-sensitive, matching Git branch semantics and the repository release model.
    /// An unavailable branch is treated as a feature branch so it cannot be mistaken for a release artifact.
    ///
    /// @param branchName current Git branch name, or `null` when unavailable
    /// @return whether the branch is `main`, `beta`, `alpha`, or `dev`
    private static boolean isReleaseBranch(@Nullable String branchName) {
        return branchName != null && switch (branchName) {
            case "main", "beta", "alpha", "dev" -> true;
            default -> false;
        };
    }
}
