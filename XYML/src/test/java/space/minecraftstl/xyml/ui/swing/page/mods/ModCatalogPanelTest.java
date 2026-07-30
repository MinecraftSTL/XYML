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
package space.minecraftstl.xyml.ui.swing.page.mods;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import space.minecraftstl.xyml.addon.mod.ModLoaderType;
import space.minecraftstl.xyml.game.GameRepository;
import space.minecraftstl.xyml.observable.Subscription;
import space.minecraftstl.xyml.observable.ValueChangeListener;
import space.minecraftstl.xyml.observable.ValueChangeSupport;
import space.minecraftstl.xyml.ui.swing.choice.ChoicePage;
import space.minecraftstl.xyml.ui.swing.choice.IndexRange;
import space.minecraftstl.xyml.ui.swing.choice.LoadCancellation;

import javax.swing.AbstractButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Rectangle;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Headless tests for the independent Swing Mod page and production constructor boundary.
@NotNullByDefault
public final class ModCatalogPanelTest {
    /// Deterministic page labels.
    private static final ModCatalogStrings STRINGS = new ModCatalogStrings(
            "Mods",
            "Search",
            "State",
            "All",
            "Enabled",
            "Disabled",
            "Select a Mod",
            "Identifier",
            "Version",
            "Game version",
            "Loader",
            "Authors",
            "File",
            "Description",
            "Enabled");

    /// Deterministic model statuses.
    private static final ModCatalogStatusStrings STATUS_STRINGS = new ModCatalogStatusStrings(
            "Loading Mods",
            "No Mods",
            "%d Mods",
            "Load failed: %s",
            "Importing Mods",
            "Enabling Mod",
            "Disabling Mod",
            "Deleting Mod",
            "Write failed: %s");

    /// Deterministic action presentation.
    private static final ModCatalogActionStrings ACTION_STRINGS = new ModCatalogActionStrings(
            "Refresh",
            "Refresh Mods",
            "Import",
            "Import Mods",
            "Open directory",
            "Open the Mods directory",
            "Reveal",
            "Reveal this Mod",
            "Delete",
            "Delete this Mod",
            "Import Mods",
            "Mod archives",
            "Delete %s?",
            "Mod operation failed");

    /// Verifies viewport range loading, single selection, details, and every command boundary.
    @Test
    public void delegatesSingleSelectionAndAllLocalCommands() throws Exception {
        RecordingModel model = new RecordingModel(items(100));
        RecordingInteractions interactions = new RecordingInteractions();
        AtomicReference<@Nullable ModCatalogPanel> panelReference = new AtomicReference<>();

        SwingUtilities.invokeAndWait(() -> {
            ModCatalogPanel panel = new ModCatalogPanel(
                    model, STRINGS, ACTION_STRINGS, interactions);
            panelReference.set(panel);
            panel.setSize(new Dimension(900, 620));
            layoutRecursively(panel);
            panel.choiceList().refreshLoadPlan();

            JList<?> list = panel.choiceList().getList();
            assertEquals(ListSelectionModel.SINGLE_SELECTION, list.getSelectionMode());
            assertFalse(model.requestedRanges().isEmpty());
            assertTrue(model.requestedRanges().get(0).length() < model.items().size());

            list.setSelectedIndex(1);
            assertEquals("mod-1", model.selectedKeys().get(0));
            assertEquals("Mod 1", findLabel(panel, "modsDetailTitle").getText());

            findButton(panel, "modsEnabled").doClick();
            assertEquals("mod-1:false", model.enabledCommands().get(0));
            findButton(panel, "modsImport").doClick();
            assertEquals(List.of(Path.of("incoming.jar")), model.imports().get(0));
            findButton(panel, "modsOpenDirectory").doClick();
            assertEquals(1, interactions.openCount());
            findButton(panel, "modsReveal").doClick();
            assertEquals(model.items().get(1).path(), interactions.revealedPath());
            findButton(panel, "modsDelete").doClick();
            assertEquals(List.of("mod-1"), model.deletedKeys());

            JTextField search = findTextField(panel, "modsSearch");
            assertEquals(Boolean.TRUE, search.getClientProperty("JTextField.showClearButton"));
            search.setText("shader");
            assertEquals("shader", model.queries().get(model.queries().size() - 1));
            findComboBox(panel, "modsFilter").setSelectedItem(ModCatalogFilter.DISABLED);
            assertEquals(ModCatalogFilter.DISABLED,
                    model.filters().get(model.filters().size() - 1));
            panel.close();
        });

        assertTrue(model.closed());
        assertNotNull(panelReference.get());
    }

    /// Verifies that production construction schedules Core refresh without synchronous disk work.
    @Test
    public void productionConstructorQueuesRealIndexWorkInHeadlessMode() throws Exception {
        ManualExecutor executor = new ManualExecutor();
        Path modsDirectory = Path.of("build", "test-mods").toAbsolutePath().normalize();
        GameRepository repository = repositoryWithModsDirectory(modsDirectory);

        SwingUtilities.invokeAndWait(() -> {
            ModCatalogPanel panel = new ModCatalogPanel(
                    repository,
                    "test-instance",
                    executor,
                    STRINGS,
                    STATUS_STRINGS,
                    ACTION_STRINGS);
            assertEquals(ModCatalogStatus.LOADING, panel.displayedSnapshot().status());
            assertEquals(1, executor.pendingCount());
            panel.close();
        });

        assertEquals(1, executor.pendingCount());
    }

    /// Constrained and restored page heights hand overflow to the existing list and complete details scroll panes.
    @Test
    public void dynamicallyScrollsListAndCompleteDetailsAtConstrainedHeight() throws Exception {
        RecordingModel model = new RecordingModel(items(100));
        RecordingInteractions interactions = new RecordingInteractions();

        SwingUtilities.invokeAndWait(() -> {
            ModCatalogPanel panel = new ModCatalogPanel(model, STRINGS, ACTION_STRINGS, interactions);
            JScrollPane detailsScroll = findComponent(panel, "modsDetailsScroll", JScrollPane.class);
            JPanel details = findComponent(panel, "modsDetails", JPanel.class);
            AbstractButton deleteButton = findButton(panel, "modsDelete");

            panel.setSize(new Dimension(900, 620));
            layoutRecursively(panel);
            assertTrue(
                    detailsScroll.getVerticalScrollBar().getMaximum()
                            <= detailsScroll.getVerticalScrollBar().getVisibleAmount());
            assertTrue(
                    panel.choiceList().getVerticalScrollBar().getMaximum()
                            > panel.choiceList().getVerticalScrollBar().getVisibleAmount());
            panel.choiceList().getVerticalScrollBar().setValue(
                    panel.choiceList().getVerticalScrollBar().getUnitIncrement() * 2);
            assertTrue(panel.choiceList().getVerticalScrollBar().getValue() > 0);

            panel.setSize(new Dimension(900, 280));
            panel.invalidate();
            layoutRecursively(panel);
            assertTrue(panel.choiceList().getViewport().getExtentSize().height > 0);
            assertTrue(
                    detailsScroll.getVerticalScrollBar().getMaximum()
                            > detailsScroll.getVerticalScrollBar().getVisibleAmount());
            int detailsBottom = detailsScroll.getVerticalScrollBar().getMaximum()
                    - detailsScroll.getVerticalScrollBar().getVisibleAmount();
            detailsScroll.getVerticalScrollBar().setValue(detailsBottom);
            Rectangle deleteBounds = SwingUtilities.convertRectangle(
                    deleteButton.getParent(),
                    deleteButton.getBounds(),
                    details);
            assertTrue(detailsScroll.getViewport().getViewRect().intersects(deleteBounds));

            panel.setSize(new Dimension(900, 620));
            panel.invalidate();
            layoutRecursively(panel);
            assertTrue(
                    detailsScroll.getVerticalScrollBar().getMaximum()
                            <= detailsScroll.getVerticalScrollBar().getVisibleAmount());
            panel.close();
        });

        assertTrue(model.closed());
    }

    /// Creates immutable deterministic public rows.
    ///
    /// @param count row count
    /// @return immutable rows
    private static @Unmodifiable List<ModCatalogItem> items(int count) {
        List<ModCatalogItem> items = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            String key = "mod-" + index;
            items.add(new ModCatalogItem(
                    key,
                    Path.of("mods", key + ".jar"),
                    key,
                    "Mod " + index,
                    "Description " + index,
                    "Author",
                    "1.0",
                    "1.21.1",
                    ModLoaderType.FABRIC,
                    key + ".jar",
                    true));
        }
        return List.copyOf(items);
    }

    /// Creates a dynamic real repository boundary that exposes only the production constructor path.
    ///
    /// @param modsDirectory deterministic Mod directory
    /// @return GameRepository proxy
    private static GameRepository repositoryWithModsDirectory(Path modsDirectory) {
        return (GameRepository) Proxy.newProxyInstance(
                GameRepository.class.getClassLoader(),
                new Class<?>[]{GameRepository.class},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("getModsDirectory")) {
                        return modsDirectory;
                    }
                    if (method.getName().equals("toString")) {
                        return "HeadlessModRepository";
                    }
                    if (method.getName().equals("hashCode")) {
                        return System.identityHashCode(proxy);
                    }
                    if (method.getName().equals("equals")) {
                        return proxy == Objects.requireNonNull(arguments)[0];
                    }
                    throw new AssertionError("Unexpected synchronous repository call: " + method.getName());
                });
    }

    /// Recursively lays out a Swing component tree for real viewport measurement.
    ///
    /// @param component root component
    private static void layoutRecursively(Component component) {
        if (component instanceof Container container) {
            container.doLayout();
            for (Component child : container.getComponents()) {
                layoutRecursively(child);
            }
        }
    }

    /// Finds one named abstract button.
    ///
    /// @param root component root
    /// @param name deterministic component name
    /// @return matching button
    private static AbstractButton findButton(Component root, String name) {
        return findComponent(root, name, AbstractButton.class);
    }

    /// Finds one named text field.
    ///
    /// @param root component root
    /// @param name deterministic component name
    /// @return matching field
    private static JTextField findTextField(Component root, String name) {
        return findComponent(root, name, JTextField.class);
    }

    /// Finds one named combo box.
    ///
    /// @param root component root
    /// @param name deterministic component name
    /// @return matching combo box
    private static JComboBox<?> findComboBox(Component root, String name) {
        return findComponent(root, name, JComboBox.class);
    }

    /// Finds one named label.
    ///
    /// @param root component root
    /// @param name deterministic component name
    /// @return matching label
    private static JLabel findLabel(Component root, String name) {
        return findComponent(root, name, JLabel.class);
    }

    /// Recursively finds one named component of the requested type.
    ///
    /// @param root component root
    /// @param name deterministic component name
    /// @param type required component type
    /// @param <T> component type
    /// @return matching component
    private static <T extends Component> T findComponent(
            Component root,
            String name,
            Class<T> type) {
        if (name.equals(root.getName()) && type.isInstance(root)) {
            return type.cast(root);
        }
        if (root instanceof Container container) {
            for (Component child : container.getComponents()) {
                try {
                    return findComponent(child, name, type);
                } catch (IllegalArgumentException ignored) {
                    // Continue through siblings until the named component is found.
                }
            }
        }
        throw new IllegalArgumentException("Component not found: " + name);
    }

    /// Executor that records production work without executing real Core I/O.
    @NotNullByDefault
    private static final class ManualExecutor implements Executor {
        /// Pending production tasks.
        private final ArrayDeque<Runnable> tasks = new ArrayDeque<>();

        /// Enqueues one task.
        @Override
        public void execute(Runnable command) {
            tasks.add(Objects.requireNonNull(command));
        }

        /// Returns queued task count.
        ///
        /// @return pending task count
        private int pendingCount() {
            return tasks.size();
        }
    }

    /// Immediate model that records all panel commands while preserving real viewport slicing.
    @NotNullByDefault
    private static final class RecordingModel implements ModCatalogModel {
        /// Immutable public source rows.
        private final @Unmodifiable List<ModCatalogItem> items;

        /// Snapshot transition support.
        private final ValueChangeSupport<ModCatalogSnapshot> changes = new ValueChangeSupport<>(this);

        /// Requested exact viewport ranges.
        private final List<IndexRange> requestedRanges = new ArrayList<>();

        /// Selected stable keys.
        private final List<String> selectedKeys = new ArrayList<>();

        /// Search query commands.
        private final List<String> queries = new ArrayList<>();

        /// Filter commands.
        private final List<ModCatalogFilter> filters = new ArrayList<>();

        /// Enabled-state commands encoded as key and value.
        private final List<String> enabledCommands = new ArrayList<>();

        /// Import commands.
        private final List<@Unmodifiable List<Path>> imports = new ArrayList<>();

        /// Deleted stable keys.
        private final List<String> deletedKeys = new ArrayList<>();

        /// Current immutable snapshot.
        private ModCatalogSnapshot snapshot;

        /// Whether close was called.
        private boolean closed;

        /// Creates one ready immediate model.
        ///
        /// @param items immutable source rows
        private RecordingModel(@Unmodifiable List<ModCatalogItem> items) {
            this.items = List.copyOf(items);
            snapshot = new ModCatalogSnapshot(
                    OptionalInt.empty(),
                    OptionalInt.of(items.size()),
                    0L,
                    ModCatalogStatus.READY,
                    items.size() + " Mods",
                    ModCatalogWriteStatus.IDLE,
                    "",
                    "",
                    ModCatalogFilter.ALL,
                    !items.isEmpty(),
                    true);
        }

        /// Returns current snapshot.
        @Override
        public ModCatalogSnapshot snapshot() {
            return snapshot;
        }

        /// Registers one transition listener.
        @Override
        public Subscription subscribe(ValueChangeListener<ModCatalogSnapshot> listener) {
            return changes.subscribe(listener);
        }

        /// Returns deterministic Mod directory.
        @Override
        public Path modsDirectory() {
            return Path.of("mods").toAbsolutePath().normalize();
        }

        /// Keeps the already-ready fake source unchanged.
        @Override
        public void loadIfNeeded() {
        }

        /// Records no-op refresh for this immediate source.
        @Override
        public void refresh() {
        }

        /// Records one query.
        @Override
        public void setSearchQuery(String query) {
            queries.add(query);
        }

        /// Records one filter.
        @Override
        public void setFilter(ModCatalogFilter filter) {
            filters.add(filter);
        }

        /// Records one stable selection.
        @Override
        public void selectMod(String localKey) {
            selectedKeys.add(localKey);
        }

        /// Clears selection in the immediate snapshot.
        @Override
        public void clearSelection() {
            snapshot = new ModCatalogSnapshot(
                    OptionalInt.empty(),
                    snapshot.itemCount(),
                    snapshot.contentRevision(),
                    snapshot.status(),
                    snapshot.statusText(),
                    snapshot.writeStatus(),
                    snapshot.writeStatusText(),
                    snapshot.searchQuery(),
                    snapshot.filter(),
                    snapshot.listEnabled(),
                    snapshot.refreshEnabled());
        }

        /// Records one enabled-state command.
        @Override
        public CompletionStage<ModCatalogSnapshot> setModEnabled(String localKey, boolean enabled) {
            enabledCommands.add(localKey + ":" + enabled);
            return CompletableFuture.completedFuture(snapshot);
        }

        /// Records one import command.
        @Override
        public CompletionStage<ModCatalogSnapshot> importMods(@Unmodifiable List<Path> sources) {
            imports.add(List.copyOf(sources));
            return CompletableFuture.completedFuture(snapshot);
        }

        /// Records one deletion command.
        @Override
        public CompletionStage<ModCatalogSnapshot> deleteMod(String localKey) {
            deletedKeys.add(localKey);
            return CompletableFuture.completedFuture(snapshot);
        }

        /// Returns exact source size.
        @Override
        public OptionalInt exactItemCount() {
            return OptionalInt.of(items.size());
        }

        /// Returns stable fake revision.
        @Override
        public OptionalLong sourceRevision() {
            return OptionalLong.of(0L);
        }

        /// Returns one completed exact viewport slice.
        @Override
        public CompletionStage<ChoicePage<ModCatalogItem>> load(
                IndexRange desiredRange,
                LoadCancellation cancellation) {
            IndexRange effective = desiredRange.clampToItemCount(items.size());
            requestedRanges.add(effective);
            return CompletableFuture.completedFuture(new ChoicePage<>(
                    effective,
                    items.subList(effective.startInclusive(), effective.endExclusive()),
                    OptionalInt.of(items.size()),
                    effective.endExclusive() == items.size()));
        }

        /// Records closure.
        @Override
        public void close() {
            closed = true;
        }

        /// Returns immutable source rows.
        ///
        /// @return rows
        private @Unmodifiable List<ModCatalogItem> items() {
            return items;
        }

        /// Returns requested viewport ranges.
        ///
        /// @return immutable ranges
        private @Unmodifiable List<IndexRange> requestedRanges() {
            return List.copyOf(requestedRanges);
        }

        /// Returns selected keys.
        ///
        /// @return immutable keys
        private @Unmodifiable List<String> selectedKeys() {
            return List.copyOf(selectedKeys);
        }

        /// Returns recorded queries.
        ///
        /// @return immutable queries
        private @Unmodifiable List<String> queries() {
            return List.copyOf(queries);
        }

        /// Returns recorded filters.
        ///
        /// @return immutable filters
        private @Unmodifiable List<ModCatalogFilter> filters() {
            return List.copyOf(filters);
        }

        /// Returns enabled-state commands.
        ///
        /// @return immutable commands
        private @Unmodifiable List<String> enabledCommands() {
            return List.copyOf(enabledCommands);
        }

        /// Returns import commands.
        ///
        /// @return immutable source lists
        private @Unmodifiable List<@Unmodifiable List<Path>> imports() {
            return List.copyOf(imports);
        }

        /// Returns deleted keys.
        ///
        /// @return immutable keys
        private @Unmodifiable List<String> deletedKeys() {
            return List.copyOf(deletedKeys);
        }

        /// Returns whether close was called.
        ///
        /// @return closure state
        private boolean closed() {
            return closed;
        }
    }

    /// Headless interaction boundary recording every desktop and confirmation command.
    @NotNullByDefault
    private static final class RecordingInteractions implements ModCatalogInteractions {
        /// Latest revealed exact path.
        private @Nullable Path revealedPath;

        /// Directory-open command count.
        private int openCount;

        /// Returns one deterministic import choice.
        @Override
        public @Unmodifiable List<Path> chooseImportFiles(Component owner, Path currentDirectory) {
            return List.of(Path.of("incoming.jar"));
        }

        /// Confirms every deterministic deletion.
        @Override
        public boolean confirmDelete(Component owner, ModCatalogItem target) {
            return true;
        }

        /// Records one exact reveal path.
        @Override
        public CompletionStage<@Nullable Void> reveal(Path target) {
            revealedPath = target;
            return CompletableFuture.completedFuture(null);
        }

        /// Records one directory-open command.
        @Override
        public CompletionStage<@Nullable Void> openDirectory(Path directory) {
            openCount++;
            return CompletableFuture.completedFuture(null);
        }

        /// Fails the test if an unexpected asynchronous error is presented.
        @Override
        public void showFailure(Component owner, String title, String detail) {
            throw new AssertionError(title + ": " + detail);
        }

        /// Returns latest revealed path.
        ///
        /// @return revealed path, or `null`
        private @Nullable Path revealedPath() {
            return revealedPath;
        }

        /// Returns directory-open count.
        ///
        /// @return open count
        private int openCount() {
            return openCount;
        }
    }
}
