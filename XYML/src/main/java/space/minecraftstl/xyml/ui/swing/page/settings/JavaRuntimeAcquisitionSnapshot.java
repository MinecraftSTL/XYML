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
package space.minecraftstl.xyml.ui.swing.page.settings;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.game.GameJavaVersion;
import space.minecraftstl.xyml.util.platform.Platform;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/// Immutable local capability snapshot for Java runtime acquisition.
///
/// @param platform current target platform used for all built-in choices
/// @param mojangRuntimes supported Mojang runtime choices in launcher-defined display order
@NotNullByDefault
public record JavaRuntimeAcquisitionSnapshot(
        Platform platform,
        @Unmodifiable List<MojangJavaRuntimeOption> mojangRuntimes) {
    /// Defensively snapshots runtime choices and rejects duplicate Java components.
    public JavaRuntimeAcquisitionSnapshot {
        platform = Objects.requireNonNull(platform, "platform");
        mojangRuntimes = List.copyOf(Objects.requireNonNull(mojangRuntimes, "mojangRuntimes"));

        Set<MojangRuntimeKey> observedVersions = new HashSet<>();
        for (MojangJavaRuntimeOption option : mojangRuntimes) {
            GameJavaVersion version = Objects.requireNonNull(
                    option,
                    "mojangRuntimes contains null").version();
            MojangRuntimeKey key = new MojangRuntimeKey(version.component(), version.majorVersion());
            if (!observedVersions.add(key)) {
                throw new IllegalArgumentException(
                        "mojangRuntimes contains duplicate Java component: "
                                + version.component() + "@" + version.majorVersion());
            }
        }
    }

    /// Exact Mojang runtime identity unaffected by [GameJavaVersion#equals(Object)] major-only semantics.
    ///
    /// @param component Mojang component identifier
    /// @param majorVersion Java major version
    @NotNullByDefault
    private record MojangRuntimeKey(String component, int majorVersion) {
        /// Rejects an absent component identifier.
        private MojangRuntimeKey {
            component = Objects.requireNonNull(component, "component");
        }
    }
}
