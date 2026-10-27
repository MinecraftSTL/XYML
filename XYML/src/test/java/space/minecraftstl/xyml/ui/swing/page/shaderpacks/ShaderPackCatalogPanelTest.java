// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.shaderpacks;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import space.minecraftstl.xyml.observable.Subscription;
import space.minecraftstl.xyml.observable.ValueChangeListener;
import space.minecraftstl.xyml.observable.ValueChangeSupport;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;
import space.minecraftstl.xyml.ui.swing.choice.CatalogLayoutAssertions;
import space.minecraftstl.xyml.ui.swing.choice.FileDragAssertions;
import space.minecraftstl.xyml.ui.swing.choice.RichValueListCellRenderer;
import space.minecraftstl.xyml.ui.swing.choice.RowBoundsCheckedList;
import space.minecraftstl.xyml.ui.swing.dialog.DeletionFallbackDecision;
import space.minecraftstl.xyml.util.io.DeletionMode;
import space.minecraftstl.xyml.util.io.TrashMoveException;

import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JTextArea;
import javax.swing.ListSelectionModel;
import java.awt.Component;
import java.awt.Container;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/// Verifies the shader-pack panel's single-enable and multi-delete interaction surface.
@NotNullByDefault
final class ShaderPackCatalogPanelTest {
    /// Covers repeated failures and the three recovery actions for single and batch deletes.
    @Test
    public void retriesOnlyFailedShaderPaths() {
        for (boolean batch : List.of(false, true)) {
            for (DeletionFallbackDecision decision : DeletionFallbackDecision.values()) {
                FakeModel model = new FakeModel(true);
                Path failedPath = Path.of("B").toAbsolutePath().normalize();
                ShaderPackCatalogItem failed = new ShaderPackCatalogItem(failedPath, "B", "B", true,
                        Set.of(), "Failed pack");
                model.snapshot = shaderSnapshot(List.of(model.snapshot.items().get(0), failed), 2L,
                        ShaderPackCatalogStatus.READY, ShaderPackCatalogWriteStatus.IDLE);
                model.deletionFailures.add(new TrashMoveException(List.of(failedPath)));
                model.deletionFailures.add(new TrashMoveException(List.of(failedPath)));
                FakeInteractions interactions = new FakeInteractions();
                interactions.deletionMode = DeletionMode.RECYCLE_BIN_FIRST;
                interactions.decisions.add(DeletionFallbackDecision.RETRY_RECYCLE_BIN);
                interactions.decisions.add(decision);
                java.util.concurrent.atomic.AtomicReference<@Nullable ShaderPackCatalogPanel> reference =
                        new java.util.concurrent.atomic.AtomicReference<>();
                EdtDispatcher.executeAndWait(() -> reference.set(newPanel(model, interactions)));
                ShaderPackCatalogPanel panel = java.util.Objects.requireNonNull(reference.get());
                EdtDispatcher.executeAndWait(() -> {
                    JList<?> list = java.util.Objects.requireNonNull(findNamed(panel, "shaderPacksList", JList.class));
                    list.setSelectionInterval(batch ? 0 : 1, 1);
                    CatalogLayoutAssertions.requireNamed(panel, batch ? "shaderPacksDeleteSelected" : "shaderPacksDelete",
                            JButton.class).doClick();
                });
                for (int turn = 0; turn < 8; turn++) {
                    EdtDispatcher.executeAndWait(() -> { });
                }
                EdtDispatcher.executeAndWait(() -> {
                    try {
                        assertEquals(List.of(1, 1), interactions.fallbackCounts);
                        int attempts = decision == DeletionFallbackDecision.CANCEL ? 2 : 3;
                        assertEquals(attempts, model.deletionModes.size());
                        assertEquals(DeletionMode.RECYCLE_BIN_FIRST, model.deletionModes.get(1));
                        if (attempts == 3) {
                            assertEquals(decision == DeletionFallbackDecision.RETRY_RECYCLE_BIN
                                    ? DeletionMode.RECYCLE_BIN_FIRST : DeletionMode.PERMANENT, model.deletionModes.get(2));
                        }
                        assertEquals(batch ? 2 : 1, model.deletionPaths.get(0).size());
                        for (int attempt = 1; attempt < attempts; attempt++) {
                            assertEquals(List.of(failedPath), model.deletionPaths.get(attempt));
                        }
                    } finally {
                        panel.close();
                    }
                });
            }
        }
    }

    /// ZIP files and directory packs export in selection order while refreshed or busy snapshots reject stale drags.
    @Test
    void dragsActualShaderZipAndDirectoryPathsWithoutSorting() {
        EdtDispatcher.executeAndWait(() -> {
            List<ShaderPackCatalogItem> items = List.of(
                    new ShaderPackCatalogItem(Path.of("shaderpacks", "pack.zip"), "pack.zip", "Pack", true,
                            Set.of(ShaderPackBackend.IRIS_OCULUS), "Archive"),
                    new ShaderPackCatalogItem(Path.of("shaderpacks", "unpacked"), "unpacked", "Directory", true,
                            Set.of(ShaderPackBackend.IRIS_OCULUS), "Directory"));
            FakeModel model = new FakeModel(true);
            model.snapshot = shaderSnapshot(items, 2L, ShaderPackCatalogStatus.READY, ShaderPackCatalogWriteStatus.IDLE);
            ShaderPackCatalogPanel panel = newPanel(model);
            try {
                JList<?> list = java.util.Objects.requireNonNull(findNamed(panel, "shaderPacksList", JList.class));
                list.setFixedCellHeight(68);
                list.setSize(520, 220);
                list.setSelectedIndices(new int[] {0, 1});
                FileDragAssertions.assertFiles(list, items.stream().map(ShaderPackCatalogItem::path).toList());
                model.publish(shaderSnapshot(items, 3L, ShaderPackCatalogStatus.READY, ShaderPackCatalogWriteStatus.IDLE));
                FileDragAssertions.assertPendingUnavailable(list);
                list.setSelectedIndex(1);
                FileDragAssertions.assertFiles(list, List.of(items.get(1).path()));
                model.publish(shaderSnapshot(items, 3L, ShaderPackCatalogStatus.LOADING, ShaderPackCatalogWriteStatus.IDLE));
                list.setSelectedIndex(1);
                FileDragAssertions.assertCannotStart(list);
                model.publish(shaderSnapshot(items, 3L, ShaderPackCatalogStatus.READY, ShaderPackCatalogWriteStatus.BUSY));
                list.setSelectedIndex(0);
                FileDragAssertions.assertCannotStart(list);
                model.publish(shaderSnapshot(items, 4L, ShaderPackCatalogStatus.READY, ShaderPackCatalogWriteStatus.IDLE));
                list.setSelectedIndex(0);
                FileDragAssertions.assertFiles(list, List.of(items.get(0).path()));
                assertEquals(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION, list.getSelectionMode());
                assertNull(findNamed(panel, "shaderPacksSort", JComponent.class));
            } finally {
                panel.close();
            }
        });
    }

    /// Creates one deterministic shader snapshot without accessing the filesystem.
    ///
    /// @param items immutable actual-path rows
    /// @param revision visible content generation
    /// @param status scan status
    /// @param writeStatus mutation status
    /// @return coherent fixture snapshot
    private static ShaderPackCatalogSnapshot shaderSnapshot(
            @org.jetbrains.annotations.Unmodifiable List<ShaderPackCatalogItem> items,
            long revision,
            ShaderPackCatalogStatus status,
            ShaderPackCatalogWriteStatus writeStatus) {
        return new ShaderPackCatalogSnapshot(OptionalInt.empty(), items.size(), revision, status, "Fixture",
                writeStatus, "", items, Set.of(ShaderPackBackend.IRIS_OCULUS));
    }

    /// Verifies the list is multi-select only for deletion and has no batch enable controls.
    @Test
    void exposesSingleEnableAndBatchDeleteWithoutSorting() {
        EdtDispatcher.executeAndWait(() -> {
            ShaderPackCatalogPanel panel = newPanel(new FakeModel(true));
            try {
                JList<?> list = findNamed(panel, "shaderPacksList", JList.class);
                assertNotNull(list);
                assertEquals(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION, list.getSelectionMode());
                assertNotNull(findNamed(panel, "shaderPacksEnabledToggle", JCheckBox.class));
                assertNull(findNamed(panel, "shaderPacksEnableSelected", JComponent.class));
                assertNull(findNamed(panel, "shaderPacksDisableSelected", JComponent.class));
                assertNull(findNamed(panel, "shaderPacksSort", JComponent.class));
                assertInstanceOf(RichValueListCellRenderer.class, list.getCellRenderer());
                assertInstanceOf(RowBoundsCheckedList.class, list);
                list.setFixedCellHeight(68);
                list.setSize(520, 220);
                list.setSelectedIndex(0);
                java.awt.Rectangle lastRow = java.util.Objects.requireNonNull(list.getCellBounds(0, 0));
                java.awt.Point blank = new java.awt.Point(
                        lastRow.x + 8, lastRow.y + lastRow.height + 20);
                dispatchPrimaryClick(list, blank);
                assertEquals(-1, list.getSelectedIndex(),
                        "Shader-pack blank click clears the selected row");
                assertEquals("No selection", CatalogLayoutAssertions.requireNamed(
                        panel, "shaderPacksEnabled", JTextArea.class).getText());

                assertEquals(-1, list.locationToIndex(blank));
                assertEquals("", CatalogLayoutAssertions.requireNamed(
                        panel, "shaderPacksFileName", JTextArea.class).getText());
                assertFalse(CatalogLayoutAssertions.requireNamed(
                        panel, "shaderPacksEnabledToggle", JCheckBox.class).isEnabled());
                dispatchPrimaryClick(list, blank);
                assertEquals(-1, list.getSelectedIndex());
                dispatchPrimaryClick(list, new java.awt.Point(lastRow.x + 20, lastRow.y + 20));
                assertEquals(0, list.getSelectedIndex(), "A real row click still selects the shader pack");
                assertEquals("Shader Packs", CatalogLayoutAssertions.requireNamed(
                        panel, "shaderPacksPageTitle", JLabel.class).getText());
                assertEquals("Local shader description", CatalogLayoutAssertions.requireNamed(
                        panel, "shaderPacksDescription", JTextArea.class).getText());
                assertNull(findNamed(panel, "shaderPacksIcon", JLabel.class));
                @SuppressWarnings("unchecked")
                JList<ShaderPackCatalogItem> rows = (JList<ShaderPackCatalogItem>) list;
                Component row = rows.getCellRenderer().getListCellRendererComponent(
                        rows, rows.getModel().getElementAt(0), 0, false, false);
                assertNull(findNamed((Container) row, "richChoiceListIcon", JLabel.class));
                JComponent labels = CatalogLayoutAssertions.requireNamed((Container) row,
                        "richChoiceListLabels", JComponent.class);
                org.junit.jupiter.api.Assertions.assertTrue(labels.getX() < 40,
                        "An icon-free shader row must not reserve the old icon square");
                for (int width : new int[] {1000, 720, 520}) {
                    CatalogLayoutAssertions.assertHorizontalWorkspace(panel, "shaderPacks", width, 460);
                }
                CatalogLayoutAssertions.assertHorizontalWorkspace(panel, "shaderPacks", 1000, 600);
                try {
                    CatalogLayoutAssertions.writePreview(panel, "shader-pack-management.png");
                } catch (IOException failure) {
                    throw new UncheckedIOException(failure);
                }
            } finally {
                panel.close();
            }
        });
    }

    /// Dispatches a primary click through the real row-bounds list implementation.
    ///
    /// @param list target shader-pack list
    /// @param point list-coordinate click point
    private static void dispatchPrimaryClick(
            javax.swing.JList<?> list,
            java.awt.Point point) {
        long when = System.currentTimeMillis();
        list.dispatchEvent(new java.awt.event.MouseEvent(
                list, java.awt.event.MouseEvent.MOUSE_PRESSED, when, 0,
                point.x, point.y, 1, false, java.awt.event.MouseEvent.BUTTON1));
        list.dispatchEvent(new java.awt.event.MouseEvent(
                list, java.awt.event.MouseEvent.MOUSE_RELEASED, when + 1L, 0,
                point.x, point.y, 1, false, java.awt.event.MouseEvent.BUTTON1));
    }

    /// Verifies invalid packs cannot be activated from the panel.
    @Test
    void disablesEnableForInvalidPack() {
        EdtDispatcher.executeAndWait(() -> {
            ShaderPackCatalogPanel panel = new ShaderPackCatalogPanel(
                    new FakeModel(false),
                    new ShaderPackCatalogStrings(
                            "Shader Packs", "Refresh", "Refreshing", "Refresh", "Retry", "Retry",
                            "Details", "No selection", "File", "Path", "Enabled", "Enabled", "Disabled",
                            "Invalid", "Backends", "No backend"),
                    new ShaderPackCatalogStatusStrings(
                            "Idle", "Loading", "Ready", "Empty", "Failed", "Writing", "Write failed"),
                    new ShaderPackCatalogActionStrings(
                            "Import", "Import", "Import", "ZIP", "Enable", "Enable", "Disable", "Disable",
                            "Delete", "Delete", "Delete %s?", "Delete %s items?", "Reveal", "Reveal",
                            "Open", "Open", "Operation failed", "Reveal failed", "Open failed",
                            "Backend", "Choose backends"),
                    new FakeInteractions(),
                    Path.of("shaderpacks"));
            try {
                JList<?> list = findNamed(panel, "shaderPacksList", JList.class);
                assertNotNull(list);
                list.setSelectedIndex(0);
                JCheckBox toggle = findNamed(panel, "shaderPacksEnabledToggle", JCheckBox.class);
                assertNotNull(toggle);
                assertFalse(toggle.isEnabled());
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

    /// Constructs a page with the existing localized labels and deterministic interactions.
    ///
    /// @param model controlled local shader model
    /// @return initialized page on the EDT
    private static ShaderPackCatalogPanel newPanel(FakeModel model) {
        return newPanel(model, new FakeInteractions());
    }

    /// Constructs a headless panel with a controlled fallback interaction boundary.
    ///
    /// @param model controlled local shader model
    /// @param interactions controlled user choices
    /// @return initialized panel on the EDT
    private static ShaderPackCatalogPanel newPanel(FakeModel model, FakeInteractions interactions) {
        return new ShaderPackCatalogPanel(
                    model,
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
                    interactions,
                    Path.of("shaderpacks"));
    }

    /// Minimal ready model for panel construction tests.
    @NotNullByDefault
    private static final class FakeModel implements ShaderPackCatalogModel {
        /// Whether the fixture pack is valid.
        private final boolean valid;

        /// Snapshot returned by the fixture.
        private ShaderPackCatalogSnapshot snapshot;

        /// Controlled page-state publication for refresh and busy-gesture tests.
        private final ValueChangeSupport<ShaderPackCatalogSnapshot> changes = new ValueChangeSupport<>(this);

        /// Creates one fixture with the requested validity.
        ///
        /// @param valid requested validity
        private FakeModel(boolean valid) {
            this.valid = valid;
            snapshot = new ShaderPackCatalogSnapshot(
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
                            valid,
                            Set.of(ShaderPackBackend.IRIS_OCULUS),
                            "Local shader description")),
                    Set.of(ShaderPackBackend.IRIS_OCULUS));
        }

        /// Publishes one controlled snapshot without reading shader files.
        ///
        /// @param replacement new coherent page state
        private void publish(ShaderPackCatalogSnapshot replacement) {
            ShaderPackCatalogSnapshot previous = snapshot;
            snapshot = replacement;
            changes.fireChange(previous, replacement);
        }

        /// Returns the fixture snapshot.
        @Override
        public ShaderPackCatalogSnapshot snapshot() {
            return snapshot;
        }

        /// Registers one listener for controlled refresh and write-state publications.
        @Override
        public Subscription subscribe(ValueChangeListener<ShaderPackCatalogSnapshot> listener) {
            return changes.subscribe(listener);
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

        /// Queued backend failures, consumed one per deletion attempt.
        private final List<Throwable> deletionFailures = new ArrayList<>();

        /// Exact paths requested in each attempt.
        private final List<@Unmodifiable List<Path>> deletionPaths = new ArrayList<>();

        /// Deletion modes requested in order.
        private final List<DeletionMode> deletionModes = new ArrayList<>();

        /// Returns the current snapshot after a deletion.
        @Override
        public CompletionStage<ShaderPackCatalogSnapshot> deleteShaderPack(Path path, DeletionMode mode) {
            return deleteShaderPacks(List.of(path), mode);
        }

        /// Returns the current snapshot after a batch deletion.
        @Override
        public CompletionStage<ShaderPackCatalogSnapshot> deleteShaderPacks(
                List<Path> paths,
                DeletionMode mode) {
            deletionPaths.add(List.copyOf(paths));
            deletionModes.add(mode);
            return deletionFailures.isEmpty() ? CompletableFuture.completedFuture(snapshot)
                    : CompletableFuture.failedFuture(deletionFailures.remove(0));
        }

        /// Ignores close.
        @Override
        public void close() {
        }
    }

    /// Minimal interaction boundary for panel construction tests.
    @NotNullByDefault
    private static final class FakeInteractions implements ShaderPackCatalogInteractions {
        /// Initial deletion mode.
        private DeletionMode deletionMode = DeletionMode.PERMANENT;

        /// Queued recovery choices.
        private final List<DeletionFallbackDecision> decisions = new ArrayList<>();

        /// Exact failed counts presented by fallback dialogs.
        private final List<Integer> fallbackCounts = new ArrayList<>();

        /// Returns a controlled single-target fallback decision.
        @Override
        public DeletionFallbackDecision choosePermanentFallback(Component owner, ShaderPackCatalogItem target) {
            return choosePermanentFallbackSelected(owner, 1);
        }

        /// Records the failed subset size and returns the next choice.
        @Override
        public DeletionFallbackDecision choosePermanentFallbackSelected(Component owner, int count) {
            fallbackCounts.add(count);
            return decisions.remove(0);
        }

        /// Returns no selected sources.
        @Override
        public List<Path> chooseImportFiles(Component owner, Path currentDirectory) {
            return List.of();
        }

        /// Returns permanent deletion.
        @Override
        public DeletionMode chooseDeleteMode(Component owner, ShaderPackCatalogItem target) {
            return deletionMode;
        }

        /// Returns permanent deletion.
        @Override
        public DeletionMode chooseDeleteModeSelected(Component owner, int selectedCount) {
            return deletionMode;
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
