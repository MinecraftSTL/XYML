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
package space.minecraftstl.xyml.ui.swing.page.resourcepacks;

import com.formdev.flatlaf.extras.FlatSVGIcon;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import space.minecraftstl.xyml.observable.Subscription;
import space.minecraftstl.xyml.observable.ValueChangeListener;
import space.minecraftstl.xyml.observable.ValueChangeSupport;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;
import space.minecraftstl.xyml.ui.swing.choice.ChoiceListEntry;
import space.minecraftstl.xyml.ui.swing.choice.ChoicePage;
import space.minecraftstl.xyml.ui.swing.choice.IndexRange;
import space.minecraftstl.xyml.ui.swing.choice.LoadCancellation;

import javax.swing.AbstractButton;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import java.awt.CardLayout;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Headless Swing tests for lazy resource-pack states, viewport demand, details, and closure.
@NotNullByDefault
public final class ResourcePackCatalogPanelTest {
    /// Stable managed directory used by headless panel tests.
    private static final Path RESOURCE_PACK_DIRECTORY = Path.of("resourcepacks")
            .toAbsolutePath()
            .normalize();

    /// Localized action text used by headless panel tests.
    private static final ResourcePackCatalogActionStrings ACTION_STRINGS =
            new ResourcePackCatalogActionStrings(
                    "Import",
                    "Import resource packs",
                    "Import resource packs",
                    "Resource pack ZIP files",
                    "Enable",
                    "Enable selected resource pack",
                    "Disable",
                    "Disable selected resource pack",
                    "Enable incompatible resource pack",
                    "Enable %s?",
                    "Delete",
                    "Permanently delete selected resource pack",
                    "Delete %s?",
                    "Reveal",
                    "Reveal selected resource pack",
                    "Open directory",
                    "Open the resource-pack directory",
                    "Resource-pack operation failed",
                    "Unable to reveal resource pack",
                    "Unable to open resource-pack directory");

    /// Silent application boundary used by read-only panel tests.
    private static final ResourcePackCatalogInteractions INTERACTIONS = new SilentInteractions();

    /// Localized catalog text used by focused panel tests.
    private static final ResourcePackCatalogStrings STRINGS = new ResourcePackCatalogStrings(
            "Resource packs",
            "Refresh",
            "Refreshing",
            "Refresh installed resource packs",
            "Retry",
            "Retry loading installed resource packs",
            "Not loaded",
            "Loading resource packs",
            "No installed resource packs",
            "Unable to load resource packs",
            "Resource packs are unsupported",
            "Details",
            "Select a resource pack",
            "File name",
            "Path",
            "Description",
            "Compatibility",
            "Enabled",
            "Enabled",
            "Disabled",
            "Compatible",
            "Too new",
            "Too old",
            "Invalid",
            "Missing pack metadata",
            "Missing game metadata");

    /// Construction and hidden cards stay I/O-free; first showing loads and close releases once.
    @Test
    public void startsOnlyAfterFirstDisplayAndClosesOwnedResources() {
        FakeResourcePackCatalogModel model = FakeResourcePackCatalogModel.immediate(
                List.of(),
                snapshot(OptionalInt.empty(), OptionalInt.empty(), 0L,
                        ResourcePackCatalogStatus.IDLE, "Waiting", false, true));
        ResourcePackCatalogPanel panel = onEventDispatchThread(
                () -> newPanel(model));

        assertAll(
                () -> assertEquals(0, model.lazyLoads.get()),
                () -> assertEquals(List.of(), model.requestedRanges()),
                () -> assertTrue(model.hasSubscribers()));

        onEventDispatchThread(() -> {
            AbstractButton refresh = findButton(panel, "resourcePacksRefresh");
            CardLayout cardLayout = new CardLayout();
            JPanel host = new JPanel(cardLayout);
            host.add(new JPanel(), "other");
            host.add(panel, "resourcePacks");
            cardLayout.show(host, "other");
            assertAll(
                    () -> assertNull(refresh.getText()),
                    () -> assertTrue(assertInstanceOf(FlatSVGIcon.class, refresh.getIcon()).hasFound()),
                    () -> assertEquals(STRINGS.refreshAction(),
                            refresh.getAccessibleContext().getAccessibleName()));
            host.addNotify();
            assertAll(
                    () -> assertEquals(0, model.lazyLoads.get()),
                    () -> assertEquals(List.of(), model.requestedRanges()));
            cardLayout.show(host, "resourcePacks");
            cardLayout.show(host, "other");
            cardLayout.show(host, "resourcePacks");
            assertEquals(1, model.lazyLoads.get());
            panel.close();
            panel.close();
            host.removeNotify();
        });

        assertAll(
                () -> assertEquals(1, model.closeCalls.get()),
                () -> assertFalse(model.hasSubscribers()));
    }

    /// Constructor failures preserve the original exception and release the already-owned model.
    @Test
    public void constructionFailureClosesModelAndRemovesAcquiredSubscription() {
        RuntimeException subscribeFailure = new IllegalStateException("subscribe failed");
        FakeResourcePackCatalogModel subscribeModel = FakeResourcePackCatalogModel.immediate(
                List.of(),
                snapshot(OptionalInt.empty(), OptionalInt.empty(), 0L,
                        ResourcePackCatalogStatus.IDLE, "Waiting", false, true));
        subscribeModel.failSubscriptionWith(subscribeFailure);

        RuntimeException observedSubscribeFailure = assertThrows(RuntimeException.class,
                () -> onEventDispatchThread(() -> newPanel(subscribeModel)));
        assertAll(
                () -> assertSame(subscribeFailure, observedSubscribeFailure),
                () -> assertEquals(1, subscribeModel.closeCalls.get()),
                () -> assertFalse(subscribeModel.hasSubscribers()));

        RuntimeException snapshotFailure = new IllegalArgumentException("snapshot failed");
        FakeResourcePackCatalogModel snapshotModel = FakeResourcePackCatalogModel.immediate(
                List.of(),
                snapshot(OptionalInt.empty(), OptionalInt.empty(), 0L,
                        ResourcePackCatalogStatus.IDLE, "Waiting", false, true));
        snapshotModel.failSnapshotWith(snapshotFailure);

        RuntimeException observedSnapshotFailure = assertThrows(RuntimeException.class,
                () -> onEventDispatchThread(() -> newPanel(snapshotModel)));
        assertAll(
                () -> assertSame(snapshotFailure, observedSnapshotFailure),
                () -> assertEquals(1, snapshotModel.closeCalls.get()),
                () -> assertFalse(snapshotModel.hasSubscribers()));

        FakeResourcePackCatalogModel threadModel = FakeResourcePackCatalogModel.immediate(
                List.of(),
                snapshot(OptionalInt.empty(), OptionalInt.empty(), 0L,
                        ResourcePackCatalogStatus.IDLE, "Waiting", false, true));
        assertThrows(IllegalStateException.class,
                () -> newPanel(threadModel));
        assertEquals(1, threadModel.closeCalls.get());

        FakeResourcePackCatalogModel nullStringsModel = FakeResourcePackCatalogModel.immediate(
                List.of(),
                snapshot(OptionalInt.empty(), OptionalInt.empty(), 0L,
                        ResourcePackCatalogStatus.IDLE, "Waiting", false, true));
        assertThrows(NullPointerException.class,
                () -> onEventDispatchThread(() -> new ResourcePackCatalogPanel(
                        nullStringsModel,
                        null,
                        ACTION_STRINGS,
                        INTERACTIONS,
                        RESOURCE_PACK_DIRECTORY)));
        assertEquals(1, nullStringsModel.closeCalls.get());
    }

    /// Every lifecycle selects its dedicated card and refresh or retry delegates only when enabled.
    @Test
    public void presentsAllLifecycleCardsAndDelegatesRefreshAndRetry() {
        FakeResourcePackCatalogModel model = FakeResourcePackCatalogModel.immediate(
                List.of(),
                snapshot(OptionalInt.empty(), OptionalInt.empty(), 0L,
                        ResourcePackCatalogStatus.IDLE, "Waiting", false, true));
        ResourcePackCatalogPanel panel = onEventDispatchThread(
                () -> newPanel(model));

        onEventDispatchThread(() -> {
            assertTrue(findComponent(panel, "resourcePacksIdle").isVisible());
            model.publish(List.of(), snapshot(OptionalInt.empty(), OptionalInt.empty(), 0L,
                    ResourcePackCatalogStatus.LOADING, "Scanning", false, false));
            assertAll(
                    () -> assertTrue(findComponent(panel, "resourcePacksLoading").isVisible()),
                    () -> assertFalse(findButton(panel, "resourcePacksRefresh").isEnabled()),
                    () -> assertEquals(STRINGS.refreshingAction(),
                            findButton(panel, "resourcePacksRefresh")
                                    .getAccessibleContext().getAccessibleName()));

            String failureText = "Resource-pack directory could not be indexed at this location"
                    + System.lineSeparator() + "disk unavailable";
            model.publish(List.of(), snapshot(OptionalInt.empty(), OptionalInt.empty(), 0L,
                    ResourcePackCatalogStatus.FAILED, failureText, false, true));
            panel.setSize(new Dimension(420, 360));
            layoutRecursively(panel);
            assertAll(
                    () -> assertTrue(findComponent(panel, "resourcePacksFailedPanel").isVisible()),
                    () -> assertTrue(findButton(panel, "resourcePacksRetry").isEnabled()),
                    () -> assertEquals(failureText,
                            findTextArea(panel, "resourcePacksFailed").getText()),
                    () -> assertTrue(findTextArea(panel, "resourcePacksFailed").getLineWrap()),
                    () -> assertTrue(findComponent(panel, "resourcePacksFailedScroll",
                            JScrollPane.class).getViewport().getExtentSize().width > 0));
            findButton(panel, "resourcePacksRetry").doClick();

            model.publish(List.of(), snapshot(OptionalInt.empty(), OptionalInt.empty(), 0L,
                    ResourcePackCatalogStatus.FAILED, "", false, true));
            assertEquals(STRINGS.failureTitle(),
                    findTextArea(panel, "resourcePacksFailed").getText());

            model.publish(List.of(), snapshot(OptionalInt.empty(), OptionalInt.of(0), 1L,
                    ResourcePackCatalogStatus.UNSUPPORTED, "Unsupported", false, true));
            assertTrue(findComponent(panel, "resourcePacksUnsupported").isVisible());

            model.publish(List.of(), snapshot(OptionalInt.empty(), OptionalInt.of(0), 2L,
                    ResourcePackCatalogStatus.READY, "0 packs", false, true));
            assertTrue(findComponent(panel, "resourcePacksEmpty").isVisible());
            findButton(panel, "resourcePacksRefresh").doClick();

            @Unmodifiable List<ResourcePackCatalogItem> rows = items(2);
            model.publish(rows, snapshot(OptionalInt.empty(), OptionalInt.of(2), 3L,
                    ResourcePackCatalogStatus.READY, "2 packs", true, true));
            assertTrue(findComponent(panel, "resourcePacksCatalogSplit").isVisible());
            panel.close();
        });

        assertEquals(2, model.refreshes.get());
    }

    /// Actual viewport geometry determines demand and loaded selection exposes complete safe details.
    @Test
    public void usesMeasuredViewportAndSynchronizesSelectionAndDetails() {
        @Unmodifiable List<ResourcePackCatalogItem> rows = items(1_000);
        FakeResourcePackCatalogModel model = FakeResourcePackCatalogModel.immediate(
                rows,
                snapshot(OptionalInt.empty(), OptionalInt.of(rows.size()), 1L,
                        ResourcePackCatalogStatus.READY, "Ready", true, true));
        ResourcePackCatalogPanel panel = onEventDispatchThread(
                () -> newPanel(model));

        onEventDispatchThread(() -> {
            panel.setSize(new Dimension(960, 540));
            layoutRecursively(panel);
            panel.choiceList().refreshLoadPlan();

            JList<ChoiceListEntry<ResourcePackCatalogItem>> list = panel.choiceList().getList();
            IndexRange requested = model.requestedRanges().get(0);
            int viewportHeight = panel.choiceList().getViewport().getExtentSize().height;
            int rowHeight = list.getFixedCellHeight();
            int visibleRows = (viewportHeight + rowHeight - 1) / rowHeight;
            list.setSelectedIndex(1);

            ResourcePackCatalogItem selected = rows.get(1);
            assertAll(
                    () -> assertEquals(ListSelectionModelValue.SINGLE, selectionMode(list)),
                    () -> assertEquals(STRINGS.pageTitle(),
                            list.getAccessibleContext().getAccessibleName()),
                    () -> assertEquals(visibleRows * 2, requested.length()),
                    () -> assertTrue(requested.length() < rows.size()),
                    () -> assertEquals(List.of(selected.path()), model.selectedPaths()),
                    () -> assertEquals(selected.fileName(),
                            findComponent(panel, "resourcePacksFileName", javax.swing.JLabel.class).getText()),
                    () -> assertEquals(selected.path().toString(),
                            findTextArea(panel, "resourcePacksPath").getText()),
                    () -> assertEquals(selected.description(),
                            findTextArea(panel, "resourcePacksDescription").getText()),
                    () -> assertEquals(STRINGS.compatibleText(),
                            findComponent(panel, "resourcePacksCompatibility", javax.swing.JLabel.class).getText()),
                    () -> assertEquals(STRINGS.disabledText(),
                            findComponent(panel, "resourcePacksEnabled", javax.swing.JLabel.class).getText()));

            list.clearSelection();
            assertAll(
                    () -> assertEquals(1, model.clearSelections.get()),
                    () -> assertEquals(STRINGS.noSelectionText(),
                            findTextArea(panel, "resourcePacksDescription").getText()));

            int requestsBeforeReplacement = model.requestedRanges().size();
            @Unmodifiable List<ResourcePackCatalogItem> replacement = items(4);
            model.publish(replacement, snapshot(OptionalInt.empty(), OptionalInt.of(4), 2L,
                    ResourcePackCatalogStatus.READY, "Replaced", true, true));
            assertAll(
                    () -> assertEquals(-1, list.getSelectedIndex()),
                    () -> assertTrue(model.requestedRanges().size() > requestsBeforeReplacement),
                    () -> assertEquals(STRINGS.noSelectionText(),
                            findTextArea(panel, "resourcePacksDescription").getText()));
            panel.close();
        });
    }

    /// A placeholder click submits once only after the exact requested range completes.
    @Test
    public void waitsForPlaceholderBeforeSubmittingSelection() {
        @Unmodifiable List<ResourcePackCatalogItem> rows = items(80);
        FakeResourcePackCatalogModel model = FakeResourcePackCatalogModel.controlled(
                rows,
                snapshot(OptionalInt.empty(), OptionalInt.of(rows.size()), 1L,
                        ResourcePackCatalogStatus.READY, "Ready", true, true));
        ResourcePackCatalogPanel panel = onEventDispatchThread(
                () -> newPanel(model));

        onEventDispatchThread(() -> {
            panel.setSize(new Dimension(900, 420));
            layoutRecursively(panel);
            panel.choiceList().refreshLoadPlan();
            panel.choiceList().getList().setSelectedIndex(2);
            assertEquals(List.of(), model.selectedPaths());
        });

        model.completePendingLoads();
        EdtDispatcher.executeAndWait(() -> { });

        onEventDispatchThread(() -> {
            assertEquals(List.of(rows.get(2).path()), model.selectedPaths());
            panel.choiceList().refreshLoadPlan();
            assertEquals(List.of(rows.get(2).path()), model.selectedPaths());
            panel.close();
        });
    }

    /// Width allocation switches the same unframed workspace between horizontal and vertical splits.
    @Test
    public void switchesResponsiveOrientationAtNarrowWidth() {
        @Unmodifiable List<ResourcePackCatalogItem> rows = items(3);
        FakeResourcePackCatalogModel model = FakeResourcePackCatalogModel.immediate(
                rows,
                snapshot(OptionalInt.empty(), OptionalInt.of(rows.size()), 1L,
                        ResourcePackCatalogStatus.READY, "Ready", true, true));
        ResourcePackCatalogPanel panel = onEventDispatchThread(
                () -> newPanel(model));

        onEventDispatchThread(() -> {
            JSplitPane split = findComponent(panel, "resourcePacksCatalogSplit", JSplitPane.class);
            JScrollPane detailsScroll = findComponent(
                    panel,
                    "resourcePacksDetailsScroll",
                    JScrollPane.class);
            panel.setSize(new Dimension(980, 620));
            layoutRecursively(panel);
            assertEquals(JSplitPane.HORIZONTAL_SPLIT, split.getOrientation());
            assertEquals(new Dimension(0, 0), split.getMinimumSize());
            assertTrue(
                    detailsScroll.getVerticalScrollBar().getMaximum()
                            <= detailsScroll.getVerticalScrollBar().getVisibleAmount());

            panel.setSize(new Dimension(980, 260));
            panel.invalidate();
            layoutRecursively(panel);
            assertAll(
                    () -> assertEquals(JSplitPane.HORIZONTAL_SPLIT, split.getOrientation()),
                    () -> assertTrue(panel.choiceList().getViewport().getExtentSize().height > 0),
                    () -> assertTrue(detailsScroll.getVerticalScrollBar().getMaximum()
                            > detailsScroll.getVerticalScrollBar().getVisibleAmount()));

            panel.setSize(new Dimension(980, 620));
            panel.invalidate();
            layoutRecursively(panel);
            assertTrue(
                    detailsScroll.getVerticalScrollBar().getMaximum()
                            <= detailsScroll.getVerticalScrollBar().getVisibleAmount());

            panel.setSize(new Dimension(600, 420));
            panel.invalidate();
            layoutRecursively(panel);
            assertAll(
                    () -> assertEquals(
                            JSplitPane.VERTICAL_SPLIT,
                            split.getOrientation(),
                            () -> "panel=" + panel.getSize() + ", split=" + split.getSize()),
                    () -> assertTrue(split.getTopComponent().getHeight() > 0),
                    () -> assertTrue(split.getBottomComponent().getHeight() > 0),
                    () -> assertTrue(detailsScroll.getViewport().getExtentSize().height > 0),
                    () -> assertTrue(detailsScroll.getVerticalScrollBar().getMaximum()
                            > detailsScroll.getVerticalScrollBar().getVisibleAmount()));
            panel.close();
        });
    }

    /// Worker-thread transitions are coalesced to the newest snapshot and ignored after close.
    @Test
    public void appliesLatestWorkerSnapshotAndStopsAfterClose() throws InterruptedException {
        FakeResourcePackCatalogModel model = FakeResourcePackCatalogModel.immediate(
                List.of(),
                snapshot(OptionalInt.empty(), OptionalInt.empty(), 0L,
                        ResourcePackCatalogStatus.IDLE, "Waiting", false, true));
        ResourcePackCatalogPanel panel = onEventDispatchThread(
                () -> newPanel(model));
        @Unmodifiable List<ResourcePackCatalogItem> rows = items(2);
        ResourcePackCatalogSnapshot ready = snapshot(OptionalInt.empty(), OptionalInt.of(2), 2L,
                ResourcePackCatalogStatus.READY, "Newest", true, true);

        Thread publisher = new Thread(() -> {
            model.publish(List.of(), snapshot(OptionalInt.empty(), OptionalInt.empty(), 1L,
                    ResourcePackCatalogStatus.LOADING, "Scanning", false, false));
            model.publish(rows, ready);
        }, "resource-pack-panel-publisher");
        publisher.start();
        publisher.join();
        EdtDispatcher.executeAndWait(() -> { });

        onEventDispatchThread(() -> assertEquals(ready, panel.displayedSnapshot()));
        panel.close();
        model.publish(List.of(), snapshot(OptionalInt.empty(), OptionalInt.of(0), 3L,
                ResourcePackCatalogStatus.READY, "Late", false, true));
        EdtDispatcher.executeAndWait(() -> { });

        onEventDispatchThread(() -> assertAll(
                () -> assertEquals(ready, panel.displayedSnapshot()),
                () -> assertFalse(findButton(panel, "resourcePacksRefresh").isEnabled())));
    }

    /// Creates one immutable test row list with multiline descriptions and alternating enabled state.
    ///
    /// @param count row count
    /// @return immutable resource-pack rows
    private static @Unmodifiable List<ResourcePackCatalogItem> items(int count) {
        List<ResourcePackCatalogItem> rows = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            rows.add(new ResourcePackCatalogItem(
                    Path.of("resourcepacks", "pack-" + index + ".zip"),
                    "Pack " + index,
                    "pack-" + index + ".zip",
                    "First line " + index + System.lineSeparator() + "Second line " + index,
                    ResourcePackCompatibility.COMPATIBLE,
                    index % 2 == 0));
        }
        return List.copyOf(rows);
    }

    /// Creates one validated resource-pack snapshot.
    ///
    /// @param selectedIndex selected logical index
    /// @param itemCount exact count when known
    /// @param contentRevision indexed-content revision
    /// @param status lifecycle status
    /// @param statusText visible status text
    /// @param listEnabled whether selection is enabled
    /// @param refreshEnabled whether refresh is enabled
    /// @return validated snapshot
    private static ResourcePackCatalogSnapshot snapshot(
            OptionalInt selectedIndex,
            OptionalInt itemCount,
            long contentRevision,
            ResourcePackCatalogStatus status,
            String statusText,
            boolean listEnabled,
            boolean refreshEnabled) {
        return new ResourcePackCatalogSnapshot(
                selectedIndex,
                itemCount,
                contentRevision,
                status,
                statusText,
                ResourcePackCatalogWriteStatus.IDLE,
                "",
                listEnabled,
                refreshEnabled);
    }

    /// Converts the Swing selection mode to a small assertion value.
    ///
    /// @param list list to inspect
    /// @return stable test selection value
    private static ListSelectionModelValue selectionMode(
            JList<ChoiceListEntry<ResourcePackCatalogItem>> list) {
        return list.getSelectionMode() == javax.swing.ListSelectionModel.SINGLE_SELECTION
                ? ListSelectionModelValue.SINGLE
                : ListSelectionModelValue.OTHER;
    }

    /// Creates one panel with the shared silent interaction boundary.
    ///
    /// @param model owned fake model
    /// @return configured panel
    private static ResourcePackCatalogPanel newPanel(ResourcePackCatalogModel model) {
        return new ResourcePackCatalogPanel(
                model,
                STRINGS,
                ACTION_STRINGS,
                INTERACTIONS,
                RESOURCE_PACK_DIRECTORY);
    }

    /// Runs one value-producing action on the EDT.
    ///
    /// @param action action to run
    /// @param <T> returned value type
    /// @return action result
    private static <T extends Object> T onEventDispatchThread(Supplier<T> action) {
        AtomicReference<@Nullable T> result = new AtomicReference<>();
        AtomicReference<@Nullable Throwable> failure = new AtomicReference<>();
        EdtDispatcher.executeAndWait(() -> {
            try {
                result.set(action.get());
            } catch (RuntimeException | Error thrown) {
                failure.set(thrown);
            }
        });
        rethrow(failure.get());
        return Objects.requireNonNull(result.get(), "EDT action did not produce a result");
    }

    /// Runs one action on the EDT.
    ///
    /// @param action action to run
    private static void onEventDispatchThread(Runnable action) {
        EdtDispatcher.executeAndWait(action);
    }

    /// Recursively lays out one test component tree using assigned bounds.
    ///
    /// @param component component tree root
    private static void layoutRecursively(Component component) {
        if (component instanceof Container container) {
            container.doLayout();
            for (Component child : container.getComponents()) {
                layoutRecursively(child);
            }
        }
    }

    /// Finds one named button below a component tree.
    ///
    /// @param root component tree root
    /// @param name stable component name
    /// @return matching button
    private static AbstractButton findButton(Container root, String name) {
        return findComponent(root, name, AbstractButton.class);
    }

    /// Finds one named text area below a component tree.
    ///
    /// @param root component tree root
    /// @param name stable component name
    /// @return matching text area
    private static JTextArea findTextArea(Container root, String name) {
        return findComponent(root, name, JTextArea.class);
    }

    /// Finds one named component below a component tree.
    ///
    /// @param root component tree root
    /// @param name stable component name
    /// @return matching component
    private static Component findComponent(Container root, String name) {
        for (Component child : root.getComponents()) {
            if (name.equals(child.getName())) {
                return child;
            }
            if (child instanceof Container nested) {
                @Nullable Component match = findOptionalComponent(nested, name);
                if (match != null) {
                    return match;
                }
            }
        }
        throw new AssertionError("Component not found: " + name);
    }

    /// Finds and type-checks one named component below a component tree.
    ///
    /// @param root component tree root
    /// @param name stable component name
    /// @param type required component type
    /// @param <T> component type
    /// @return matching typed component
    private static <T extends Component> T findComponent(
            Container root,
            String name,
            Class<T> type) {
        return type.cast(findComponent(root, name));
    }

    /// Searches recursively without throwing when one subtree has no match.
    ///
    /// @param root subtree root
    /// @param name stable component name
    /// @return matching component, or null
    private static @Nullable Component findOptionalComponent(Container root, String name) {
        for (Component child : root.getComponents()) {
            if (name.equals(child.getName())) {
                return child;
            }
            if (child instanceof Container nested) {
                @Nullable Component match = findOptionalComponent(nested, name);
                if (match != null) {
                    return match;
                }
            }
        }
        return null;
    }

    /// Rethrows one captured unchecked EDT failure.
    ///
    /// @param failure captured failure, or null
    private static void rethrow(@Nullable Throwable failure) {
        if (failure instanceof RuntimeException runtimeException) {
            throw runtimeException;
        }
        if (failure instanceof Error error) {
            throw error;
        }
    }

    /// Test-only normalized list selection modes.
    @NotNullByDefault
    private enum ListSelectionModelValue {
        /// Single selection is configured.
        SINGLE,

        /// Any unexpected selection mode.
        OTHER
    }

    /// Interaction boundary that never opens a dialog or performs a desktop operation.
    @NotNullByDefault
    private static final class SilentInteractions implements ResourcePackCatalogInteractions {
        /// Returns no selected import files.
        @Override
        public @Unmodifiable List<Path> chooseImportFiles(
                Component owner,
                Path currentDirectory) {
            Objects.requireNonNull(owner, "owner");
            Objects.requireNonNull(currentDirectory, "currentDirectory");
            return List.of();
        }

        /// Rejects the unused incompatible-pack confirmation.
        @Override
        public boolean confirmEnableIncompatible(
                Component owner,
                ResourcePackCatalogItem target) {
            Objects.requireNonNull(owner, "owner");
            Objects.requireNonNull(target, "target");
            return false;
        }

        /// Rejects the unused permanent-delete confirmation.
        @Override
        public boolean confirmDelete(Component owner, ResourcePackCatalogItem target) {
            Objects.requireNonNull(owner, "owner");
            Objects.requireNonNull(target, "target");
            return false;
        }

        /// Completes the unused reveal command immediately.
        @Override
        public CompletionStage<@Nullable Void> reveal(ResourcePackCatalogItem target) {
            Objects.requireNonNull(target, "target");
            return CompletableFuture.completedFuture(null);
        }

        /// Completes the unused open-directory command immediately.
        @Override
        public CompletionStage<@Nullable Void> openResourcePackDirectory(Path resourcePackDirectory) {
            Objects.requireNonNull(resourcePackDirectory, "resourcePackDirectory");
            return CompletableFuture.completedFuture(null);
        }

        /// Ignores unused failure feedback.
        @Override
        public void showFailure(Component owner, String title, String detail) {
            Objects.requireNonNull(owner, "owner");
            Objects.requireNonNull(title, "title");
            Objects.requireNonNull(detail, "detail");
        }
    }

    /// Captures one exact viewport request for controlled completion.
    ///
    /// @param range requested viewport range
    /// @param rows immutable source rows captured at request time
    /// @param future controlled result
    @NotNullByDefault
    private record PendingLoad(
            IndexRange range,
            @Unmodifiable List<ResourcePackCatalogItem> rows,
            CompletableFuture<ChoicePage<ResourcePackCatalogItem>> future) {
        /// Validates one pending load fixture.
        private PendingLoad {
            Objects.requireNonNull(range, "range");
            rows = List.copyOf(rows);
            Objects.requireNonNull(future, "future");
        }
    }

    /// In-memory catalog fake exposing exact viewport and command observations.
    @NotNullByDefault
    private static final class FakeResourcePackCatalogModel implements ResourcePackCatalogModel {
        /// Observable current snapshot.
        private final AtomicReference<ResourcePackCatalogSnapshot> current;

        /// Thread-safe model listeners.
        private final ValueChangeSupport<ResourcePackCatalogSnapshot> changes =
                new ValueChangeSupport<>(this);

        /// Whether viewport requests complete synchronously.
        private final boolean immediateLoads;

        /// Exact requested viewport ranges.
        private final List<IndexRange> ranges = new ArrayList<>();

        /// Controlled viewport requests awaiting test completion.
        private final List<PendingLoad> pendingLoads = new ArrayList<>();

        /// Stable selected paths delegated by the panel.
        private final List<Path> paths = new ArrayList<>();

        /// Number of first-display lazy-load requests.
        private final AtomicInteger lazyLoads = new AtomicInteger();

        /// Number of accepted refresh or retry commands.
        private final AtomicInteger refreshes = new AtomicInteger();

        /// Number of explicit clear-selection commands.
        private final AtomicInteger clearSelections = new AtomicInteger();

        /// Number of first close transitions.
        private final AtomicInteger closeCalls = new AtomicInteger();

        /// Whether this fake has crossed its close gate.
        private final AtomicBoolean closed = new AtomicBoolean();

        /// Configured subscription failure, or null for normal listener registration.
        private @Nullable RuntimeException subscriptionFailure;

        /// Configured snapshot failure, or null for normal state access.
        private @Nullable RuntimeException snapshotFailure;

        /// Current immutable indexed rows.
        private volatile @Unmodifiable List<ResourcePackCatalogItem> rows;

        /// Creates one in-memory catalog fake.
        ///
        /// @param rows initial immutable rows
        /// @param snapshot matching initial state
        /// @param immediateLoads whether viewport ranges complete immediately
        private FakeResourcePackCatalogModel(
                @Unmodifiable List<ResourcePackCatalogItem> rows,
                ResourcePackCatalogSnapshot snapshot,
                boolean immediateLoads) {
            this.rows = List.copyOf(rows);
            current = new AtomicReference<>(Objects.requireNonNull(snapshot, "snapshot"));
            this.immediateLoads = immediateLoads;
        }

        /// Creates a fake whose viewport requests complete immediately.
        ///
        /// @param rows initial immutable rows
        /// @param snapshot matching initial state
        /// @return immediate fake
        private static FakeResourcePackCatalogModel immediate(
                @Unmodifiable List<ResourcePackCatalogItem> rows,
                ResourcePackCatalogSnapshot snapshot) {
            return new FakeResourcePackCatalogModel(rows, snapshot, true);
        }

        /// Creates a fake whose viewport requests require explicit completion.
        ///
        /// @param rows initial immutable rows
        /// @param snapshot matching initial state
        /// @return controlled fake
        private static FakeResourcePackCatalogModel controlled(
                @Unmodifiable List<ResourcePackCatalogItem> rows,
                ResourcePackCatalogSnapshot snapshot) {
            return new FakeResourcePackCatalogModel(rows, snapshot, false);
        }

        /// Returns the latest fake snapshot.
        @Override
        public ResourcePackCatalogSnapshot snapshot() {
            @Nullable RuntimeException failure = snapshotFailure;
            if (failure != null) {
                throw failure;
            }
            return current.get();
        }

        /// Registers one fake snapshot listener.
        @Override
        public Subscription subscribe(ValueChangeListener<ResourcePackCatalogSnapshot> listener) {
            @Nullable RuntimeException failure = subscriptionFailure;
            if (failure != null) {
                throw failure;
            }
            return changes.subscribe(listener);
        }

        /// Returns the exact current item count when indexed.
        @Override
        public OptionalInt exactItemCount() {
            return current.get().itemCount();
        }

        /// Returns the current content revision for stale-page rejection.
        @Override
        public OptionalLong sourceRevision() {
            return OptionalLong.of(current.get().contentRevision());
        }

        /// Captures and optionally completes one viewport request.
        @Override
        public synchronized CompletionStage<ChoicePage<ResourcePackCatalogItem>> load(
                IndexRange desiredRange,
                LoadCancellation cancellation) {
            Objects.requireNonNull(cancellation, "cancellation");
            ranges.add(desiredRange);
            @Unmodifiable List<ResourcePackCatalogItem> rowSnapshot = rows;
            if (immediateLoads) {
                return CompletableFuture.completedFuture(page(desiredRange, rowSnapshot));
            }
            CompletableFuture<ChoicePage<ResourcePackCatalogItem>> future = new CompletableFuture<>();
            pendingLoads.add(new PendingLoad(desiredRange, rowSnapshot, future));
            return future;
        }

        /// Records one initial lazy-load request.
        @Override
        public void loadIfNeeded() {
            lazyLoads.incrementAndGet();
        }

        /// Records one refresh or retry request.
        @Override
        public void refresh() {
            refreshes.incrementAndGet();
        }

        /// Records and publishes one stable selected path.
        @Override
        public synchronized void selectResourcePack(Path path) {
            Path normalized = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
            int selectedIndex = indexOf(normalized);
            if (selectedIndex < 0) {
                throw new IllegalArgumentException("Unknown fake resource pack: " + normalized);
            }
            paths.add(normalized);
            ResourcePackCatalogSnapshot previous = current.get();
            publish(rows, copyWithSelection(previous, OptionalInt.of(selectedIndex)));
        }

        /// Records and publishes one explicit selection clear.
        @Override
        public synchronized void clearSelection() {
            clearSelections.incrementAndGet();
            ResourcePackCatalogSnapshot previous = current.get();
            publish(rows, copyWithSelection(previous, OptionalInt.empty()));
        }

        /// Returns the unchanged fake snapshot for an unused import command.
        ///
        /// @param sources ignored source paths
        /// @return already completed current snapshot
        @Override
        public CompletionStage<ResourcePackCatalogSnapshot> importResourcePacks(List<Path> sources) {
            Objects.requireNonNull(sources, "sources");
            return CompletableFuture.completedFuture(current.get());
        }

        /// Returns the unchanged fake snapshot for an unused enable command.
        ///
        /// @param path ignored current pack path
        /// @return already completed current snapshot
        @Override
        public CompletionStage<ResourcePackCatalogSnapshot> enableResourcePack(Path path) {
            Objects.requireNonNull(path, "path");
            return CompletableFuture.completedFuture(current.get());
        }

        /// Returns the unchanged fake snapshot for an unused disable command.
        ///
        /// @param path ignored current pack path
        /// @return already completed current snapshot
        @Override
        public CompletionStage<ResourcePackCatalogSnapshot> disableResourcePack(Path path) {
            Objects.requireNonNull(path, "path");
            return CompletableFuture.completedFuture(current.get());
        }

        /// Returns the unchanged fake snapshot for an unused delete command.
        ///
        /// @param path ignored current pack path
        /// @return already completed current snapshot
        @Override
        public CompletionStage<ResourcePackCatalogSnapshot> deleteResourcePack(Path path) {
            Objects.requireNonNull(path, "path");
            return CompletableFuture.completedFuture(current.get());
        }

        /// Closes this test-owned model once.
        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                closeCalls.incrementAndGet();
            }
        }

        /// Publishes rows and their exactly matching snapshot.
        ///
        /// @param replacement immutable replacement rows
        /// @param snapshot matching replacement state
        private void publish(
                @Unmodifiable List<ResourcePackCatalogItem> replacement,
                ResourcePackCatalogSnapshot snapshot) {
            rows = List.copyOf(replacement);
            ResourcePackCatalogSnapshot previous = current.getAndSet(snapshot);
            changes.fireChange(previous, snapshot);
        }

        /// Returns exact requested ranges in invocation order.
        ///
        /// @return immutable range snapshot
        private synchronized @Unmodifiable List<IndexRange> requestedRanges() {
            return List.copyOf(ranges);
        }

        /// Returns stable selected paths in command order.
        ///
        /// @return immutable selected-path snapshot
        private synchronized @Unmodifiable List<Path> selectedPaths() {
            return List.copyOf(paths);
        }

        /// Completes every currently pending viewport request.
        private void completePendingLoads() {
            @Unmodifiable List<PendingLoad> loads;
            synchronized (this) {
                loads = List.copyOf(pendingLoads);
                pendingLoads.clear();
            }
            for (PendingLoad load : loads) {
                load.future().complete(page(load.range(), load.rows()));
            }
        }

        /// Returns whether at least one panel listener remains registered.
        ///
        /// @return whether this fake has subscribers
        private boolean hasSubscribers() {
            return changes.hasSubscribers();
        }

        /// Configures one deterministic subscription-acquisition failure.
        ///
        /// @param failure exact failure
        private void failSubscriptionWith(RuntimeException failure) {
            subscriptionFailure = Objects.requireNonNull(failure, "failure");
        }

        /// Configures one deterministic snapshot-read failure.
        ///
        /// @param failure exact failure
        private void failSnapshotWith(RuntimeException failure) {
            snapshotFailure = Objects.requireNonNull(failure, "failure");
        }

        /// Finds one normalized path in current indexed order.
        ///
        /// @param path normalized path
        /// @return logical index, or -1
        private int indexOf(Path path) {
            for (int index = 0; index < rows.size(); index++) {
                if (rows.get(index).path().equals(path)) {
                    return index;
                }
            }
            return -1;
        }

        /// Copies one snapshot while replacing only its selected index.
        ///
        /// @param source source snapshot
        /// @param selectedIndex replacement selection
        /// @return copied snapshot
        private static ResourcePackCatalogSnapshot copyWithSelection(
                ResourcePackCatalogSnapshot source,
                OptionalInt selectedIndex) {
            return new ResourcePackCatalogSnapshot(
                    selectedIndex,
                    source.itemCount(),
                    source.contentRevision(),
                    source.status(),
                    source.statusText(),
                    source.writeStatus(),
                    source.writeStatusText(),
                    source.listEnabled(),
                    source.refreshEnabled());
        }

        /// Creates one exact source-aligned page.
        ///
        /// @param desiredRange requested range
        /// @param rowSnapshot immutable captured rows
        /// @return exact choice page
        private static ChoicePage<ResourcePackCatalogItem> page(
                IndexRange desiredRange,
                @Unmodifiable List<ResourcePackCatalogItem> rowSnapshot) {
            IndexRange actualRange = desiredRange.clampToItemCount(rowSnapshot.size());
            @Unmodifiable List<ResourcePackCatalogItem> values = List.copyOf(rowSnapshot.subList(
                    actualRange.startInclusive(),
                    actualRange.endExclusive()));
            return new ChoicePage<>(
                    actualRange,
                    values,
                    OptionalInt.of(rowSnapshot.size()),
                    actualRange.endExclusive() == rowSnapshot.size());
        }
    }
}
