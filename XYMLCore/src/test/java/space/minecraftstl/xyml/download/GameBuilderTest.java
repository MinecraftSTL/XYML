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
package space.minecraftstl.xyml.download;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import space.minecraftstl.xyml.game.GameComponentType;
import space.minecraftstl.xyml.task.Task;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/// Tests ordered remote-installer retention in [GameBuilder].
@NotNullByDefault
public final class GameBuilderTest {
    /// Different loaders with equal self-version text retain their caller-supplied order.
    @Test
    public void remoteVersionsRetainOrderWithoutEqualityBasedDeduplication() {
        ComponentRemoteVersion forge = remoteVersion(GameComponentType.FORGE, "47.2.0");
        ComponentRemoteVersion fabric = remoteVersion(GameComponentType.FABRIC, "47.2.0");
        ComponentRemoteVersion quilt = remoteVersion(GameComponentType.QUILT, "0.26.3");
        RecordingGameBuilder builder = new RecordingGameBuilder();

        builder.version(forge).version(fabric).version(quilt);

        @Unmodifiable List<ComponentRemoteVersion> selected = builder.remoteVersionsSnapshot();
        assertEquals(List.of("forge", "fabric", "quilt"), selected.stream()
                .map(value -> value.getComponentType().getPatchId())
                .toList());
        assertSame(forge, selected.get(0));
        assertSame(fabric, selected.get(1));
        assertSame(quilt, selected.get(2));
    }

    /// Creates minimal remote installer metadata for builder-selection tests.
    ///
    /// @param componentType selected component type
    /// @param selfVersion selected installer version text
    /// @return remote installer metadata
    private static ComponentRemoteVersion remoteVersion(GameComponentType componentType, String selfVersion) {
        return new TestComponentRemoteVersion(componentType, "1.21.1", selfVersion);
    }

    /// Captures protected builder state without starting a real installation task.
    @NotNullByDefault
    private static final class RecordingGameBuilder extends GameBuilder {
        /// Returns an immutable snapshot of remote installers recorded by the base builder.
        ///
        /// @return immutable ordered remote installers
        private @Unmodifiable List<ComponentRemoteVersion> remoteVersionsSnapshot() {
            return List.copyOf(remoteVersions);
        }

        /// Rejects task execution because these tests inspect only builder configuration.
        ///
        /// @return never returns normally
        @Override
        public Task<?> buildAsync() {
            throw new UnsupportedOperationException("No task is required for builder state tests");
        }
    }
}
