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
package space.minecraftstl.xyml.ui.swing.page.shaderpacks;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import space.minecraftstl.xyml.util.io.DeletionMode;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies the asynchronous catalog model lifecycle and serialized mutation boundary.
@NotNullByDefault
final class DefaultShaderPackCatalogModelTest {
    /// Verifies initial scanning and successful mutation publication.
    @Test
    void publishesScanAndMutationSnapshots() {
        FakeAccess access = new FakeAccess();
        DefaultShaderPackCatalogModel model = new DefaultShaderPackCatalogModel(
                access,
                Runnable::run,
                "idle",
                "loading",
                "ready",
                "empty",
                "failed",
                "writing",
                "write failed");

        model.loadIfNeeded();

        assertEquals(ShaderPackCatalogStatus.READY, model.snapshot().status());
        assertEquals(1, model.snapshot().itemCount());
        assertEquals(Set.of(ShaderPackBackend.IRIS_OCULUS), model.snapshot().availableBackends());

        model.setShaderPackEnabled(Path.of("pack"), Set.of(ShaderPackBackend.IRIS_OCULUS), true).toCompletableFuture().join();

        assertTrue(access.enabled);
        assertEquals(ShaderPackCatalogWriteStatus.IDLE, model.snapshot().writeStatus());
        model.close();
    }

    /// Minimal synchronous access implementation used by model tests.
    @NotNullByDefault
    private static final class FakeAccess implements ShaderPackCatalogAccess {
        /// Path returned by the fixture.
        private final Path path = Path.of("pack").toAbsolutePath().normalize();

        /// Whether a mutation was observed.
        private boolean enabled;

        /// Returns one candidate.
        @Override
        public List<Path> loadIndex() {
            return List.of(path);
        }

        /// Returns one valid row.
        @Override
        public List<ShaderPackCatalogItem> loadItems(List<Path> paths) {
            return List.of(new ShaderPackCatalogItem(path, "pack", "pack", true, Set.of()));
        }

        /// Returns one available backend.
        @Override
        public Set<ShaderPackBackend> detectAvailableBackends() {
            return Set.of(ShaderPackBackend.IRIS_OCULUS);
        }

        /// Records a successful import.
        @Override
        public void importShaderPacks(List<Path> sources) {
        }

        /// Records a successful enable mutation.
        @Override
        public void setEnabled(Path path, Set<ShaderPackBackend> backends, boolean enabled) {
            this.enabled = enabled;
        }

        /// Records a successful deletion.
        @Override
        public void delete(Path path, DeletionMode mode) {
        }
    }
}
