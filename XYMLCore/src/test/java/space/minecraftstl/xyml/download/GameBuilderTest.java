// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

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
