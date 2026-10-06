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
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import space.minecraftstl.xyml.observable.Subscription;
import space.minecraftstl.xyml.observable.ValueChangeListener;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;
import space.minecraftstl.xyml.util.io.DeletionMode;

import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JList;
import javax.swing.ListSelectionModel;
import java.awt.Component;
import java.awt.Container;
import java.nio.file.Path;
import java.util.List;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/// Verifies the shader-pack panel's single-enable and multi-delete interaction surface.
@NotNullByDefault
final class ShaderPackCatalogPanelTest {
    /// Verifies the list is multi-select only for deletion and has no batch enable controls.
    @Test
    void exposesSingleEnableAndBatchDeleteWithoutSorting() {
        EdtDispatcher.executeAndWait(() -> {
            ShaderPackCatalogPanel panel = new ShaderPackCatalogPanel(
                    new FakeModel(),
                    new ShaderPackCatalogStrings(
                            "Shader Packs",
                            "Refresh",
                            "Refreshing",
                            "Refresh",
                            "Retry",
                            "Retry",
                            "Details",
                            "No selection",
                            "File",
                            "Path",
                            "Enabled",
                            "Enabled",
                            "Disabled",
                            "Invalid",
                            "Backends",
                            "No backend"),
                    new ShaderPackCatalogStatusStrings(
                            "Idle",
                            "Loading",
                            "Ready",
                            "Empty",
                            "Failed",
                            "Writing",
                            "Write failed"),
                    new ShaderPackCatalogActionStrings(
                            "Import",
                            "Import",
                            "Import",
                            "ZIP",
                            "Enable",
                            "Enable",
                            "Disable",
                            "Disable",
                            "Delete",
                            "Delete",
                            "Delete %s?",
                            "Delete %s items?",
                            "Reveal",
                            "Reveal",
                            "Open",
                            "Open",
                            "Operation failed",
                            "Reveal failed",
                            "Open failed",
                            "Backend",
                            "Choose backends"),
                    new FakeInteractions(),
                    Path.of("shaderpacks"));
            try {
                JList<?> list = findNamed(panel, "shaderPacksList", JList.class);
                assertNotNull(list);
                assertEquals(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION, list.getSelectionMode());
                assertNotNull(findNamed(panel, "shaderPacksEnabledToggle", JCheckBox.class));
                assertNull(findNamed(panel, "shaderPacksEnableSelected", JComponent.class));
                assertNull(findNamed(panel, "shaderPacksDisableSelected", JComponent.class));
                assertNull(findNamed(panel, "shaderPacksSort", JComponent.class));
            } finally {
                panel.close();
            }
        });
    }

    /// Finds one named descendant.
    ///
    /// @param root component root
    /// @param name stable component name
    /// @param type required component type
    /// @param <T> component type
    /// @return matching component, or null
    private static <T extends JComponent> @Nullable T findNamed(
            Container root,
            String name,
            Class<T> type) {
        for (Component component : root.getComponents()) {
            if (type.isInstance(component) && name.equals(component.getName())) {
                return type.cast(component);
            }
            if (component instanceof Container child) {
                @Nullable T nested = findNamed(child, name, type);
                if (nested != null) {
                    return nested;
                }
            }
        }
        return null;
    }

    /// Minimal ready model for panel construction tests.
    @NotNullByDefault
    private static final class FakeModel implements ShaderPackCatalogModel {
        /// Snapshot returned by the fixture.
        private final ShaderPackCatalogSnapshot snapshot = new ShaderPackCatalogSnapshot(
                OptionalInt.empty(),
                1,
                1L,
                ShaderPackCatalogStatus.READY,
                "Ready",
                ShaderPackCatalogWriteStatus.IDLE,
                "",
                List.of(new ShaderPackCatalogItem(
                        Path.of("A").toAbsolutePath().normalize(),
                        "A",
                        "A",
                        true,
                        Set.of(ShaderPackBackend.IRIS_OCULUS))),
                Set.of(ShaderPackBackend.IRIS_OCULUS));

        /// Returns the fixture snapshot.
        @Override
        public ShaderPackCatalogSnapshot snapshot() {
            return snapshot;
        }

        /// Returns a no-op registration.
        @Override
        public Subscription subscribe(ValueChangeListener<ShaderPackCatalogSnapshot> listener) {
            return Subscription.create(() -> { });
        }

        /// Ignores lazy loading.
        @Override
        public void loadIfNeeded() {
        }

        /// Ignores refresh.
        @Override
        public void refresh() {
        }

        /// Ignores selection.
        @Override
        public void selectShaderPack(Path path) {
        }

        /// Ignores selection clearing.
        @Override
        public void clearSelection() {
        }

        /// Returns the current snapshot after an import.
        @Override
        public CompletionStage<ShaderPackCatalogSnapshot> importShaderPacks(List<Path> sources) {
            return CompletableFuture.completedFuture(snapshot);
        }

        /// Returns the current snapshot after an enable change.
        @Override
        public CompletionStage<ShaderPackCatalogSnapshot> setShaderPackEnabled(
                Path path,
                Set<ShaderPackBackend> backends,
                boolean enabled) {
            return CompletableFuture.completedFuture(snapshot);
        }

        /// Returns the current snapshot after a deletion.
        @Override
        public CompletionStage<ShaderPackCatalogSnapshot> deleteShaderPack(Path path, DeletionMode mode) {
            return CompletableFuture.completedFuture(snapshot);
        }

        /// Returns the current snapshot after a batch deletion.
        @Override
        public CompletionStage<ShaderPackCatalogSnapshot> deleteShaderPacks(
                List<Path> paths,
                DeletionMode mode) {
            return CompletableFuture.completedFuture(snapshot);
        }

        /// Ignores close.
        @Override
        public void close() {
        }
    }

    /// Minimal interaction boundary for panel construction tests.
    @NotNullByDefault
    private static final class FakeInteractions implements ShaderPackCatalogInteractions {
        /// Returns no selected sources.
        @Override
        public List<Path> chooseImportFiles(Component owner, Path currentDirectory) {
            return List.of();
        }

        /// Returns permanent deletion.
        @Override
        public DeletionMode chooseDeleteMode(Component owner, ShaderPackCatalogItem target) {
            return DeletionMode.PERMANENT;
        }

        /// Returns permanent deletion.
        @Override
        public DeletionMode chooseDeleteModeSelected(Component owner, int selectedCount) {
            return DeletionMode.PERMANENT;
        }

        /// Returns the sole backend.
        @Override
        public Set<ShaderPackBackend> chooseBackends(
                Component owner,
                Set<ShaderPackBackend> availableBackends) {
            return availableBackends;
        }

        /// Returns a completed reveal.
        @Override
        public CompletionStage<@Nullable Void> reveal(ShaderPackCatalogItem target) {
            return CompletableFuture.completedFuture(null);
        }

        /// Returns a completed open.
        @Override
        public CompletionStage<@Nullable Void> openDirectory(Path directory) {
            return CompletableFuture.completedFuture(null);
        }

        /// Ignores failure display.
        @Override
        public void showFailure(Component owner, String title, String detail) {
        }
    }
}
