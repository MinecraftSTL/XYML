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

/// Computes the next stable version from one explicitly requested increment.
///
/// The selected component is incremented and every lower component is cleared, so 1.0.5 becomes 1.0.6 for a
/// patch increment, 1.1.0 for a minor increment, and 2.0.0 for a major increment.
@NotNullByDefault
final class StableVersionIncrement {
    /// Number of components required by a stable version.
    private static final int STABLE_COMPONENT_COUNT = 3;

    /// Version component selected by the release request.
    enum Kind {
        /// Increments the major component and clears every lower component.
        MAJOR,

        /// Increments the minor component and clears the patch component.
        MINOR,

        /// Increments the patch component.
        PATCH
    }

    /// Prevents instantiation of this stateless helper.
    private StableVersionIncrement() {
    }

    /// Resolves one increment keyword.
    ///
    /// @param value configured increment keyword
    /// @return matching increment kind
    /// @throws IllegalArgumentException when the keyword is not major, minor, or patch
    static Kind parseKind(String value) {
        return switch (value) {
            case "major" -> Kind.MAJOR;
            case "minor" -> Kind.MINOR;
            case "patch" -> Kind.PATCH;
            default -> throw new IllegalArgumentException(
                    "Unsupported stable version increment: " + value + " (expected major, minor, or patch)");
        };
    }

    /// Computes the target stable version for one increment.
    ///
    /// @param currentStableVersion current three-component stable version
    /// @param kind component to increment
    /// @return target stable version with every lower component cleared
    /// @throws IllegalArgumentException when the current version is malformed or the increment overflows
    static String targetVersion(String currentStableVersion, Kind kind) {
        String[] components = currentStableVersion.split("\\.", -1);
        if (components.length != STABLE_COMPONENT_COUNT) {
            throw new IllegalArgumentException(
                    "Stable version must contain " + STABLE_COMPONENT_COUNT + " components: " + currentStableVersion);
        }
        int[] values = new int[STABLE_COMPONENT_COUNT];
        for (int index = 0; index < STABLE_COMPONENT_COUNT; index++) {
            values[index] = parseComponent(currentStableVersion, components[index]);
        }
        int position = switch (kind) {
            case MAJOR -> 0;
            case MINOR -> 1;
            case PATCH -> 2;
        };
        try {
            values[position] = Math.addExact(values[position], 1);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("Stable version component overflows: " + currentStableVersion, exception);
        }
        for (int index = position + 1; index < STABLE_COMPONENT_COUNT; index++) {
            values[index] = 0;
        }
        String target = values[0] + "." + values[1] + "." + values[2];
        ReleaseVersionResolver.validateVersion(ReleaseType.STABLE, target);
        return target;
    }

    /// Parses one decimal version component.
    ///
    /// @param version complete version reported in failures
    /// @param component component text
    /// @return non-negative component value
    private static int parseComponent(String version, String component) {
        if (component.isEmpty() || !isDecimal(component)) {
            throw new IllegalArgumentException("Stable version must use decimal components only: " + version);
        }
        try {
            return Integer.parseInt(component);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Stable version component is out of range: " + version, exception);
        }
    }

    /// Reports whether one component contains ASCII decimal digits only.
    ///
    /// @param component component text
    /// @return true when every character is an ASCII decimal digit
    private static boolean isDecimal(String component) {
        for (char character : component.toCharArray()) {
            if (character < '0') {
                return false;
            }
            if (character > '9') {
                return false;
            }
        }
        return true;
    }
}
