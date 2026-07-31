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

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import space.minecraftstl.xyml.observable.Subscription;
import space.minecraftstl.xyml.observable.ValueChangeListener;
import space.minecraftstl.xyml.observable.ValueChangeSupport;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;
import space.minecraftstl.xyml.ui.swing.choice.ChoicePage;
import space.minecraftstl.xyml.ui.swing.choice.IndexRange;
import space.minecraftstl.xyml.ui.swing.choice.LoadCancellation;

import javax.swing.AbstractButton;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
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
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Headless tests for resource-pack commands, write-state gating, and asynchronous feedback.
@NotNullByDefault
public final class ResourcePackCatalogPanelActionsTest {
    /// Localized catalog presentation used by the action-focused panel tests.
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

    /// Localized action presentation used by the action-focused panel tests.
    private static final ResourcePackCatalogActionStrings ACTION_STRINGS =
            new ResourcePackCatalogActionStrings(
                    "Import",
                    "Import resource packs",
                    "Choose resource packs",
                    "Resource pack ZIP archives",
                    "Enable",
                    "Enable selected resource pack",
                    "Disable",
                    "Disable selected resource pack",
                    "Enable incompatible resource pack",
                    "Enable incompatible resource pack %s?",
                    "Delete",
                    "Permanently delete selected resource pack",
                    "Permanently delete resource pack %s?",
                    "Reveal",
                    "Reveal selected resource pack",
                    "Open directory",
                    "Open the resource-pack directory",
                    "Resource-pack operation failed",
                    "Unable to reveal resource pack",
                    "Unable to open resource-pack directory");

    /// An exact empty ready catalog accepts imports while chooser cancellation remains side-effect free.
    @Test
    public void emptyReadyCatalogImportsAndCancelledChooserDoesNotWrite() {
        Path resourcePackDirectory = testPath("resourcepacks");
        FakeResourcePackCatalogModel model = new FakeResourcePackCatalogModel(
                List.of(),
                snapshot(
                        OptionalInt.empty(),
                        OptionalInt.of(0),
                        1L,
                        ResourcePackCatalogStatus.READY,
                        "No resource packs",
                        ResourcePackCatalogWriteStatus.IDLE,
                        "",
                        false,
                        true));
        FakeResourcePackCatalogInteractions interactions = new FakeResourcePackCatalogInteractions();
        @Unmodifiable List<Path> selectedSources = List.of(
                testPath("incoming/first.zip"),
                testPath("incoming/second.zip"));
        interactions.importSelection = selectedSources;
        ResourcePackCatalogPanel panel = onEventDispatchThread(() -> new ResourcePackCatalogPanel(
                model,
                STRINGS,
                ACTION_STRINGS,
                interactions,
                resourcePackDirectory));

        onEventDispatchThread(() -> {
            AbstractButton importButton = findButton(panel, "resourcePacksImport");
            assertAll(
                    () -> assertTrue(importButton.isEnabled()),
                    () -> assertTrue(findButton(panel, "resourcePacksOpenDirectory").isEnabled()));
            importButton.doClick();
            assertAll(
                    () -> assertEquals(1, interactions.importChooserCalls),
                    () -> assertEquals(resourcePackDirectory, interactions.importDirectory),
                    () -> assertEquals(List.of(selectedSources), model.importedSources()),
                    () -> assertTrue(interactions.dialogCallsOnEventDispatchThread.get()));

            interactions.importSelection = List.of();
            importButton.doClick();
            assertAll(
                    () -> assertEquals(2, interactions.importChooserCalls),
                    () -> assertEquals(List.of(selectedSources), model.importedSources()));
            panel.close();
        });
    }

    /// Selection commands apply compatibility confirmation and preserve each captured target path.
    @Test
    public void selectionCommandsConfirmWhenRequiredAndUseExactPaths() {
        Path resourcePackDirectory = testPath("resourcepacks");
        ResourcePackCatalogItem compatibleDisabled = item(
                resourcePackDirectory.resolve("compatible.zip"),
                ResourcePackCompatibility.COMPATIBLE,
                false);
        ResourcePackCatalogItem incompatibleDisabled = item(
                resourcePackDirectory.resolve("too-new.zip"),
                ResourcePackCompatibility.TOO_NEW,
                false);
        ResourcePackCatalogItem compatibleEnabled = item(
                resourcePackDirectory.resolve("enabled.zip"),
                ResourcePackCompatibility.COMPATIBLE,
                true);
        @Unmodifiable List<ResourcePackCatalogItem> rows =
                List.of(compatibleDisabled, incompatibleDisabled, compatibleEnabled);
        FakeResourcePackCatalogModel model = readyModel(rows, 1L);
        FakeResourcePackCatalogInteractions interactions = new FakeResourcePackCatalogInteractions();
        ResourcePackCatalogPanel panel = onEventDispatchThread(() -> new ResourcePackCatalogPanel(
                model,
                STRINGS,
                ACTION_STRINGS,
                interactions,
                resourcePackDirectory));

        onEventDispatchThread(() -> {
            prepareLoadedList(panel);
            AbstractButton toggle = findButton(panel, "resourcePacksEnabledToggle");
            AbstractButton delete = findButton(panel, "resourcePacksDelete");

            panel.choiceList().getList().setSelectedIndex(0);
            assertAll(
                    () -> assertFalse(toggle.isSelected()),
                    () -> assertEquals(
                            ACTION_STRINGS.enableAction(),
                            toggle.getAccessibleContext().getAccessibleName()));
            toggle.doClick();
            assertAll(
                    () -> assertEquals(List.of(compatibleDisabled.path()), model.enabledPaths()),
                    () -> assertEquals(List.of(), interactions.incompatibleTargets));

            panel.choiceList().getList().setSelectedIndex(1);
            interactions.incompatibleEnableConfirmed = false;
            toggle.doClick();
            assertEquals(List.of(compatibleDisabled.path()), model.enabledPaths());
            interactions.incompatibleEnableConfirmed = true;
            toggle.doClick();
            assertAll(
                    () -> assertEquals(
                            List.of(compatibleDisabled.path(), incompatibleDisabled.path()),
                            model.enabledPaths()),
                    () -> assertEquals(
                            List.of(incompatibleDisabled, incompatibleDisabled),
                            interactions.incompatibleTargets));

            panel.choiceList().getList().setSelectedIndex(2);
            assertAll(
                    () -> assertTrue(toggle.isSelected()),
                    () -> assertEquals(
                            ACTION_STRINGS.disableAction(),
                            toggle.getAccessibleContext().getAccessibleName()));
            toggle.doClick();
            assertEquals(List.of(compatibleEnabled.path()), model.disabledPaths());

            interactions.deleteConfirmed = false;
            delete.doClick();
            assertEquals(List.of(), model.deletedPaths());
            interactions.deleteConfirmed = true;
            delete.doClick();
            assertAll(
                    () -> assertEquals(List.of(compatibleEnabled.path()), model.deletedPaths()),
                    () -> assertEquals(
                            List.of(compatibleEnabled, compatibleEnabled),
                            interactions.deletedTargets),
                    () -> assertTrue(interactions.dialogCallsOnEventDispatchThread.get()));
            panel.close();
        });
    }

    /// Busy writes retain selection while disabling writes and exposing the exact write status.
    @Test
    public void busyAndErrorStatesRetainSelectionAndGateActions() {
        Path resourcePackDirectory = testPath("resourcepacks");
        ResourcePackCatalogItem row = item(
                resourcePackDirectory.resolve("selected.zip"),
                ResourcePackCompatibility.COMPATIBLE,
                false);
        @Unmodifiable List<ResourcePackCatalogItem> rows = List.of(row);
        FakeResourcePackCatalogModel model = readyModel(rows, 1L);
        FakeResourcePackCatalogInteractions interactions = new FakeResourcePackCatalogInteractions();
        ResourcePackCatalogPanel panel = onEventDispatchThread(() -> new ResourcePackCatalogPanel(
                model,
                STRINGS,
                ACTION_STRINGS,
                interactions,
                resourcePackDirectory));

        onEventDispatchThread(() -> {
            prepareLoadedList(panel);
            panel.choiceList().getList().setSelectedIndex(0);
            model.publish(rows, snapshot(
                    OptionalInt.of(0),
                    OptionalInt.of(1),
                    1L,
                    ResourcePackCatalogStatus.READY,
                    "1 resource pack",
                    ResourcePackCatalogWriteStatus.BUSY,
                    "Writing resource packs",
                    false,
                    false));

            JTextArea status = findComponent(panel, "resourcePacksStatus", JTextArea.class);
            assertAll(
                    () -> assertEquals(0, panel.choiceList().getList().getSelectedIndex()),
                    () -> assertFalse(panel.choiceList().getList().isEnabled()),
                    () -> assertFalse(findButton(panel, "resourcePacksImport").isEnabled()),
                    () -> assertFalse(findButton(panel, "resourcePacksEnabledToggle").isEnabled()),
                    () -> assertFalse(findButton(panel, "resourcePacksReveal").isEnabled()),
                    () -> assertFalse(findButton(panel, "resourcePacksDelete").isEnabled()),
                    () -> assertFalse(findButton(panel, "resourcePacksOpenDirectory").isEnabled()),
                    () -> assertEquals("Writing resource packs", status.getText()));

            model.publish(rows, snapshot(
                    OptionalInt.of(0),
                    OptionalInt.of(1),
                    1L,
                    ResourcePackCatalogStatus.READY,
                    "1 resource pack",
                    ResourcePackCatalogWriteStatus.ERROR,
                    "Resource-pack write failed: disk full",
                    true,
                    true));
            assertAll(
                    () -> assertEquals(0, panel.choiceList().getList().getSelectedIndex()),
                    () -> assertTrue(panel.choiceList().getList().isEnabled()),
                    () -> assertTrue(findButton(panel, "resourcePacksImport").isEnabled()),
                    () -> assertTrue(findButton(panel, "resourcePacksEnabledToggle").isEnabled()),
                    () -> assertTrue(findButton(panel, "resourcePacksReveal").isEnabled()),
                    () -> assertTrue(findButton(panel, "resourcePacksDelete").isEnabled()),
                    () -> assertEquals("Resource-pack write failed: disk full", status.getText()));
            panel.close();
        });
    }

    /// Modal results are discarded after indexed content or the selected target changes.
    @Test
    public void modalResultsAreRevalidatedAgainstRevisionAndSelection() {
        Path resourcePackDirectory = testPath("resourcepacks");
        ResourcePackCatalogItem first = item(
                resourcePackDirectory.resolve("first.zip"),
                ResourcePackCompatibility.TOO_OLD,
                false);
        ResourcePackCatalogItem second = item(
                resourcePackDirectory.resolve("second.zip"),
                ResourcePackCompatibility.COMPATIBLE,
                false);
        @Unmodifiable List<ResourcePackCatalogItem> rows = List.of(first, second);
        FakeResourcePackCatalogModel model = readyModel(rows, 1L);
        FakeResourcePackCatalogInteractions interactions = new FakeResourcePackCatalogInteractions();
        interactions.importSelection = List.of(testPath("incoming/source.zip"));
        ResourcePackCatalogPanel panel = onEventDispatchThread(() -> new ResourcePackCatalogPanel(
                model,
                STRINGS,
                ACTION_STRINGS,
                interactions,
                resourcePackDirectory));

        onEventDispatchThread(() -> {
            interactions.importHook = () -> model.publish(rows, snapshot(
                    OptionalInt.empty(),
                    OptionalInt.of(2),
                    2L,
                    ResourcePackCatalogStatus.READY,
                    "Refreshed",
                    ResourcePackCatalogWriteStatus.IDLE,
                    "",
                    true,
                    true));
            findButton(panel, "resourcePacksImport").doClick();
            assertEquals(List.of(), model.importedSources());

            interactions.importHook = null;
            prepareLoadedList(panel);
            panel.choiceList().getList().setSelectedIndex(0);
            interactions.incompatibleEnableConfirmed = true;
            interactions.incompatibleEnableHook =
                    () -> panel.choiceList().getList().setSelectedIndex(1);
            findButton(panel, "resourcePacksEnabledToggle").doClick();
            assertAll(
                    () -> assertEquals(List.of(), model.enabledPaths()),
                    () -> assertEquals(1, panel.choiceList().getList().getSelectedIndex()),
                    () -> assertSame(first, interactions.incompatibleTargets.get(0)));
            panel.close();
        });
    }

    /// Reveal and directory-open commands use separate pending gates and drop feedback after close.
    @Test
    public void revealAndOpenDirectoryAreIndependentAndDropLateFeedback() {
        Path resourcePackDirectory = testPath("resourcepacks");
        ResourcePackCatalogItem row = item(
                resourcePackDirectory.resolve("selected.zip"),
                ResourcePackCompatibility.COMPATIBLE,
                false);
        FakeResourcePackCatalogModel model = readyModel(List.of(row), 1L);
        FakeResourcePackCatalogInteractions interactions = new FakeResourcePackCatalogInteractions();
        CompletableFuture<@Nullable Void> reveal = new CompletableFuture<>();
        CompletableFuture<@Nullable Void> open = new CompletableFuture<>();
        interactions.revealCompletion = reveal;
        interactions.openCompletion = open;
        ResourcePackCatalogPanel panel = onEventDispatchThread(() -> new ResourcePackCatalogPanel(
                model,
                STRINGS,
                ACTION_STRINGS,
                interactions,
                resourcePackDirectory));

        onEventDispatchThread(() -> {
            prepareLoadedList(panel);
            panel.choiceList().getList().setSelectedIndex(0);
            AbstractButton revealButton = findButton(panel, "resourcePacksReveal");
            AbstractButton openButton = findButton(panel, "resourcePacksOpenDirectory");
            revealButton.doClick();
            assertAll(
                    () -> assertEquals(List.of(row), interactions.revealedTargets),
                    () -> assertFalse(revealButton.isEnabled()),
                    () -> assertTrue(openButton.isEnabled()),
                    () -> assertTrue(findButton(panel, "resourcePacksImport").isEnabled()),
                    () -> assertTrue(findButton(panel, "resourcePacksDelete").isEnabled()));
            openButton.doClick();
            revealButton.doClick();
            openButton.doClick();
            assertAll(
                    () -> assertEquals(1, interactions.revealedTargets.size()),
                    () -> assertEquals(List.of(resourcePackDirectory), interactions.openedDirectories),
                    () -> assertFalse(revealButton.isEnabled()),
                    () -> assertFalse(openButton.isEnabled()));
        });

        reveal.completeExceptionally(new CompletionException(
                new ExecutionException(new IllegalStateException("desktop unavailable"))));
        open.completeExceptionally(new IllegalArgumentException("directory unavailable"));
        flushEventDispatchThread();

        onEventDispatchThread(() -> {
            assertAll(
                    () -> assertTrue(findButton(panel, "resourcePacksReveal").isEnabled()),
                    () -> assertTrue(findButton(panel, "resourcePacksOpenDirectory").isEnabled()),
                    () -> assertEquals(
                            List.of(
                                    new FailurePresentation(
                                            ACTION_STRINGS.revealFailedTitle(),
                                            "desktop unavailable",
                                            true),
                                    new FailurePresentation(
                                            ACTION_STRINGS.openDirectoryFailedTitle(),
                                            "directory unavailable",
                                            true)),
                            interactions.failures()));

            interactions.revealCompletion = new CompletableFuture<>();
            interactions.openCompletion = new CompletableFuture<>();
            findButton(panel, "resourcePacksReveal").doClick();
            findButton(panel, "resourcePacksOpenDirectory").doClick();
            panel.close();
        });

        interactions.revealCompletion.completeExceptionally(new IllegalStateException("late reveal"));
        interactions.openCompletion.completeExceptionally(new IllegalStateException("late open"));
        flushEventDispatchThread();
        assertEquals(2, interactions.failures().size());
    }

    /// A retained old write error cannot suppress a new synchronous model-command failure.
    @Test
    public void retainedWriteErrorDoesNotSuppressNewSynchronousFailure() {
        Path resourcePackDirectory = testPath("resourcepacks");
        ResourcePackCatalogItem row = item(
                resourcePackDirectory.resolve("selected.zip"),
                ResourcePackCompatibility.COMPATIBLE,
                false);
        @Unmodifiable List<ResourcePackCatalogItem> rows = List.of(row);
        FakeResourcePackCatalogModel model = readyModel(rows, 1L);
        FakeResourcePackCatalogInteractions interactions = new FakeResourcePackCatalogInteractions();
        ResourcePackCatalogPanel panel = onEventDispatchThread(() -> new ResourcePackCatalogPanel(
                model,
                STRINGS,
                ACTION_STRINGS,
                interactions,
                resourcePackDirectory));

        onEventDispatchThread(() -> {
            prepareLoadedList(panel);
            panel.choiceList().getList().setSelectedIndex(0);
            model.publish(rows, snapshot(
                    OptionalInt.of(0),
                    OptionalInt.of(1),
                    1L,
                    ResourcePackCatalogStatus.READY,
                    "1 resource pack",
                    ResourcePackCatalogWriteStatus.ERROR,
                    "Resource-pack write failed: old failure",
                    true,
                    true));
            model.enableFailure = new IllegalStateException("new synchronous failure");
            findButton(panel, "resourcePacksEnabledToggle").doClick();

            assertEquals(
                    List.of(new FailurePresentation(
                            ACTION_STRINGS.operationFailedTitle(),
                            "new synchronous failure",
                            true)),
                    interactions.failures());
            panel.close();
        });
    }

    /// Fatal submission errors release each local pending gate before propagating unchanged.
    @Test
    public void fatalSubmissionErrorsReleasePendingGatesBeforeRethrow() {
        Path resourcePackDirectory = testPath("resourcepacks");
        ResourcePackCatalogItem row = item(
                resourcePackDirectory.resolve("selected.zip"),
                ResourcePackCompatibility.COMPATIBLE,
                false);
        FakeResourcePackCatalogModel model = readyModel(List.of(row), 1L);
        FakeResourcePackCatalogInteractions interactions = new FakeResourcePackCatalogInteractions();
        ResourcePackCatalogPanel panel = onEventDispatchThread(() -> new ResourcePackCatalogPanel(
                model,
                STRINGS,
                ACTION_STRINGS,
                interactions,
                resourcePackDirectory));
        onEventDispatchThread(() -> {
            prepareLoadedList(panel);
            panel.choiceList().getList().setSelectedIndex(0);
        });

        AssertionError writeError = new AssertionError("fatal write submission");
        model.enableError = writeError;
        assertSame(writeError, assertThrows(
                AssertionError.class,
                () -> onEventDispatchThread(
                        () -> findButton(panel, "resourcePacksEnabledToggle").doClick())));
        assertTrue(onEventDispatchThread(
                () -> findButton(panel, "resourcePacksEnabledToggle").isEnabled()));

        AssertionError revealError = new AssertionError("fatal reveal submission");
        interactions.revealError = revealError;
        assertSame(revealError, assertThrows(
                AssertionError.class,
                () -> onEventDispatchThread(
                        () -> findButton(panel, "resourcePacksReveal").doClick())));
        assertTrue(onEventDispatchThread(
                () -> findButton(panel, "resourcePacksReveal").isEnabled()));

        AssertionError openError = new AssertionError("fatal open submission");
        interactions.openError = openError;
        assertSame(openError, assertThrows(
                AssertionError.class,
                () -> onEventDispatchThread(
                        () -> findButton(panel, "resourcePacksOpenDirectory").doClick())));
        assertTrue(onEventDispatchThread(
                () -> findButton(panel, "resourcePacksOpenDirectory").isEnabled()));
        panel.close();
    }

    /// Creates a ready fake model with no initial selection.
    ///
    /// @param rows exact immutable catalog rows
    /// @param revision indexed-content revision
    /// @return ready fake model
    private static FakeResourcePackCatalogModel readyModel(
            @Unmodifiable List<ResourcePackCatalogItem> rows,
            long revision) {
        return new FakeResourcePackCatalogModel(rows, snapshot(
                OptionalInt.empty(),
                OptionalInt.of(rows.size()),
                revision,
                ResourcePackCatalogStatus.READY,
                rows.size() + " resource packs",
                ResourcePackCatalogWriteStatus.IDLE,
                "",
                !rows.isEmpty(),
                true));
    }

    /// Creates one normalized resource-pack row.
    ///
    /// @param path installed path
    /// @param compatibility compatibility state
    /// @param enabled whether Minecraft enables the pack
    /// @return immutable presentation row
    private static ResourcePackCatalogItem item(
            Path path,
            ResourcePackCompatibility compatibility,
            boolean enabled) {
        String fileName = Objects.requireNonNull(path.getFileName(), "test path requires a file name").toString();
        return new ResourcePackCatalogItem(
                path,
                fileName.substring(0, fileName.length() - ".zip".length()),
                fileName,
                "Description for " + fileName,
                compatibility,
                enabled);
    }

    /// Creates one validated catalog snapshot.
    ///
    /// @param selectedIndex selected logical index
    /// @param itemCount exact count when known
    /// @param contentRevision indexed-content revision
    /// @param status scan lifecycle
    /// @param statusText localized scan status
    /// @param writeStatus write lifecycle
    /// @param writeStatusText localized write status
    /// @param listEnabled whether row selection is enabled
    /// @param refreshEnabled whether refresh is enabled
    /// @return validated catalog snapshot
    private static ResourcePackCatalogSnapshot snapshot(
            OptionalInt selectedIndex,
            OptionalInt itemCount,
            long contentRevision,
            ResourcePackCatalogStatus status,
            String statusText,
            ResourcePackCatalogWriteStatus writeStatus,
            String writeStatusText,
            boolean listEnabled,
            boolean refreshEnabled) {
        return new ResourcePackCatalogSnapshot(
                selectedIndex,
                itemCount,
                contentRevision,
                status,
                statusText,
                writeStatus,
                writeStatusText,
                listEnabled,
                refreshEnabled);
    }

    /// Resolves one workspace-relative test path to its normalized absolute form.
    ///
    /// @param path relative test path
    /// @return normalized absolute path
    private static Path testPath(String path) {
        return Path.of(path).toAbsolutePath().normalize();
    }

    /// Assigns viewport geometry and synchronously materializes its demanded rows.
    ///
    /// @param panel panel whose viewport should be loaded
    private static void prepareLoadedList(ResourcePackCatalogPanel panel) {
        panel.setSize(new Dimension(900, 420));
        layoutRecursively(panel);
        panel.choiceList().refreshLoadPlan();
    }

    /// Recursively lays out one test component tree using its assigned bounds.
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

    /// Finds and type-checks one named component below a component tree.
    ///
    /// @param root component tree root
    /// @param name stable component name
    /// @param type required component type
    /// @param <T> component type
    /// @return matching component
    private static <T extends Component> T findComponent(
            Container root,
            String name,
            Class<T> type) {
        return type.cast(findComponent(root, name));
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

    /// Runs one value-producing action on the Swing event-dispatch thread.
    ///
    /// @param action action to run
    /// @param <T> result type
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

    /// Runs one action on the Swing event-dispatch thread.
    ///
    /// @param action action to run
    private static void onEventDispatchThread(Runnable action) {
        EdtDispatcher.executeAndWait(action);
    }

    /// Waits until every action already queued on the Swing event-dispatch thread has run.
    private static void flushEventDispatchThread() {
        EdtDispatcher.executeAndWait(() -> { });
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

    /// Captures one error dialog without depending on a graphical desktop.
    ///
    /// @param title visible dialog title
    /// @param detail visible failure detail
    /// @param onEventDispatchThread whether presentation occurred on the EDT
    @NotNullByDefault
    private record FailurePresentation(
            String title,
            String detail,
            boolean onEventDispatchThread) {
    }

    /// In-memory exact-range model that records every action argument.
    @NotNullByDefault
    private static final class FakeResourcePackCatalogModel implements ResourcePackCatalogModel {
        /// Observable current catalog snapshot.
        private final AtomicReference<ResourcePackCatalogSnapshot> current;

        /// Thread-safe snapshot listener support.
        private final ValueChangeSupport<ResourcePackCatalogSnapshot> changes =
                new ValueChangeSupport<>(this);

        /// Captured immutable source lists in invocation order.
        private final List<List<Path>> importedSources = new ArrayList<>();

        /// Captured enable targets in invocation order.
        private final List<Path> enabledPaths = new ArrayList<>();

        /// Captured disable targets in invocation order.
        private final List<Path> disabledPaths = new ArrayList<>();

        /// Captured delete targets in invocation order.
        private final List<Path> deletedPaths = new ArrayList<>();

        /// Number of first-load requests.
        private final AtomicInteger lazyLoads = new AtomicInteger();

        /// Number of close transitions.
        private final AtomicInteger closeCalls = new AtomicInteger();

        /// Whether this fake crossed its close gate.
        private final AtomicBoolean closed = new AtomicBoolean();

        /// Current immutable exact indexed rows.
        private volatile @Unmodifiable List<ResourcePackCatalogItem> rows;

        /// Optional synchronous enable failure.
        private @Nullable RuntimeException enableFailure;

        /// Optional fatal enable submission error.
        private @Nullable Error enableError;

        /// Creates one exact in-memory catalog.
        ///
        /// @param rows initial immutable rows
        /// @param snapshot matching initial snapshot
        private FakeResourcePackCatalogModel(
                @Unmodifiable List<ResourcePackCatalogItem> rows,
                ResourcePackCatalogSnapshot snapshot) {
            this.rows = List.copyOf(rows);
            current = new AtomicReference<>(Objects.requireNonNull(snapshot, "snapshot"));
        }

        /// Returns the latest fake snapshot.
        ///
        /// @return current snapshot
        @Override
        public ResourcePackCatalogSnapshot snapshot() {
            return current.get();
        }

        /// Registers one snapshot listener.
        ///
        /// @param listener transition listener
        /// @return cancellable registration
        @Override
        public Subscription subscribe(ValueChangeListener<ResourcePackCatalogSnapshot> listener) {
            return changes.subscribe(listener);
        }

        /// Returns the current exact count.
        ///
        /// @return exact count when indexed
        @Override
        public OptionalInt exactItemCount() {
            return current.get().itemCount();
        }

        /// Returns the current content revision.
        ///
        /// @return source revision
        @Override
        public OptionalLong sourceRevision() {
            return OptionalLong.of(current.get().contentRevision());
        }

        /// Immediately returns the exact requested range.
        ///
        /// @param desiredRange desired viewport range
        /// @param cancellation caller cancellation signal
        /// @return completed exact page
        @Override
        public CompletionStage<ChoicePage<ResourcePackCatalogItem>> load(
                IndexRange desiredRange,
                LoadCancellation cancellation) {
            Objects.requireNonNull(cancellation, "cancellation");
            @Unmodifiable List<ResourcePackCatalogItem> capturedRows = rows;
            IndexRange actualRange = desiredRange.clampToItemCount(capturedRows.size());
            @Unmodifiable List<ResourcePackCatalogItem> values = List.copyOf(capturedRows.subList(
                    actualRange.startInclusive(),
                    actualRange.endExclusive()));
            return CompletableFuture.completedFuture(new ChoicePage<>(
                    actualRange,
                    values,
                    OptionalInt.of(capturedRows.size()),
                    actualRange.endExclusive() == capturedRows.size()));
        }

        /// Records one first-load request.
        @Override
        public void loadIfNeeded() {
            lazyLoads.incrementAndGet();
        }

        /// Leaves the controlled fake state unchanged.
        @Override
        public void refresh() {
            // Tests publish exact replacement states explicitly.
        }

        /// Selects one exact current row and publishes its index.
        ///
        /// @param path selected stable path
        @Override
        public void selectResourcePack(Path path) {
            Path normalized = path.toAbsolutePath().normalize();
            int selectedIndex = -1;
            for (int index = 0; index < rows.size(); index++) {
                if (rows.get(index).path().equals(normalized)) {
                    selectedIndex = index;
                    break;
                }
            }
            if (selectedIndex < 0) {
                throw new IllegalArgumentException("Unknown fake resource pack: " + normalized);
            }
            ResourcePackCatalogSnapshot snapshot = current.get();
            publish(rows, copyWithSelection(snapshot, OptionalInt.of(selectedIndex)));
        }

        /// Clears the current fake selection.
        @Override
        public void clearSelection() {
            ResourcePackCatalogSnapshot snapshot = current.get();
            publish(rows, copyWithSelection(snapshot, OptionalInt.empty()));
        }

        /// Records one defensive import source snapshot.
        ///
        /// @param sources exact chosen sources
        /// @return completed current snapshot
        @Override
        public CompletionStage<ResourcePackCatalogSnapshot> importResourcePacks(List<Path> sources) {
            importedSources.add(List.copyOf(sources));
            return CompletableFuture.completedFuture(current.get());
        }

        /// Records one exact enable path.
        ///
        /// @param path selected pack path
        /// @return completed current snapshot
        @Override
        public CompletionStage<ResourcePackCatalogSnapshot> enableResourcePack(Path path) {
            if (enableError != null) {
                throw enableError;
            }
            if (enableFailure != null) {
                throw enableFailure;
            }
            enabledPaths.add(path.toAbsolutePath().normalize());
            return CompletableFuture.completedFuture(current.get());
        }

        /// Records one exact disable path.
        ///
        /// @param path selected pack path
        /// @return completed current snapshot
        @Override
        public CompletionStage<ResourcePackCatalogSnapshot> disableResourcePack(Path path) {
            disabledPaths.add(path.toAbsolutePath().normalize());
            return CompletableFuture.completedFuture(current.get());
        }

        /// Records one exact delete path.
        ///
        /// @param path selected pack path
        /// @return completed current snapshot
        @Override
        public CompletionStage<ResourcePackCatalogSnapshot> deleteResourcePack(Path path) {
            deletedPaths.add(path.toAbsolutePath().normalize());
            return CompletableFuture.completedFuture(current.get());
        }

        /// Closes this fake once.
        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                closeCalls.incrementAndGet();
            }
        }

        /// Publishes exact rows and their matching snapshot.
        ///
        /// @param replacement immutable replacement rows
        /// @param snapshot matching replacement snapshot
        private void publish(
                @Unmodifiable List<ResourcePackCatalogItem> replacement,
                ResourcePackCatalogSnapshot snapshot) {
            rows = List.copyOf(replacement);
            ResourcePackCatalogSnapshot previous = current.getAndSet(snapshot);
            changes.fireChange(previous, snapshot);
        }

        /// Returns immutable captured import calls.
        ///
        /// @return import calls
        private @Unmodifiable List<List<Path>> importedSources() {
            return importedSources.stream().map(List::copyOf).toList();
        }

        /// Returns immutable captured enable paths.
        ///
        /// @return enable paths
        private @Unmodifiable List<Path> enabledPaths() {
            return List.copyOf(enabledPaths);
        }

        /// Returns immutable captured disable paths.
        ///
        /// @return disable paths
        private @Unmodifiable List<Path> disabledPaths() {
            return List.copyOf(disabledPaths);
        }

        /// Returns immutable captured delete paths.
        ///
        /// @return delete paths
        private @Unmodifiable List<Path> deletedPaths() {
            return List.copyOf(deletedPaths);
        }

        /// Copies a snapshot while replacing only its stable selection.
        ///
        /// @param source source snapshot
        /// @param selectedIndex replacement selected index
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
    }

    /// Headless interaction boundary with controllable dialogs and asynchronous completions.
    @NotNullByDefault
    private static final class FakeResourcePackCatalogInteractions
            implements ResourcePackCatalogInteractions {
        /// Selected import paths returned by the chooser.
        private @Unmodifiable List<Path> importSelection = List.of();

        /// Optional hook run while the import chooser is logically open.
        private @Nullable Runnable importHook;

        /// Optional hook run while incompatible enable confirmation is logically open.
        private @Nullable Runnable incompatibleEnableHook;

        /// Whether incompatible enabling is confirmed.
        private boolean incompatibleEnableConfirmed;

        /// Whether permanent deletion is confirmed.
        private boolean deleteConfirmed;

        /// Number of import chooser invocations.
        private int importChooserCalls;

        /// Last chooser directory, or null before the first chooser call.
        private @Nullable Path importDirectory;

        /// Incompatible targets presented for confirmation.
        private final List<ResourcePackCatalogItem> incompatibleTargets = new ArrayList<>();

        /// Delete targets presented for confirmation.
        private final List<ResourcePackCatalogItem> deletedTargets = new ArrayList<>();

        /// Targets submitted to reveal.
        private final List<ResourcePackCatalogItem> revealedTargets = new ArrayList<>();

        /// Resource-pack directories submitted to open.
        private final List<Path> openedDirectories = new ArrayList<>();

        /// Captured headless failure presentations.
        private final List<FailurePresentation> failurePresentations = new ArrayList<>();

        /// Whether every dialog-like call occurred on the EDT.
        private final AtomicBoolean dialogCallsOnEventDispatchThread = new AtomicBoolean(true);

        /// Completion returned by the next reveal calls.
        private CompletableFuture<@Nullable Void> revealCompletion =
                CompletableFuture.completedFuture(null);

        /// Completion returned by the next directory-open calls.
        private CompletableFuture<@Nullable Void> openCompletion =
                CompletableFuture.completedFuture(null);

        /// Optional fatal reveal submission error.
        private @Nullable Error revealError;

        /// Optional fatal directory-open submission error.
        private @Nullable Error openError;

        /// Returns controlled chooser selections after running its modal hook.
        ///
        /// @param owner owning panel
        /// @param currentDirectory chooser directory
        /// @return immutable selected paths
        @Override
        public @Unmodifiable List<Path> chooseImportFiles(Component owner, Path currentDirectory) {
            recordDialogThread();
            importChooserCalls++;
            importDirectory = currentDirectory;
            runHook(importHook);
            return List.copyOf(importSelection);
        }

        /// Returns the controlled incompatible-enable decision.
        ///
        /// @param owner owning panel
        /// @param target target pack
        /// @return controlled confirmation
        @Override
        public boolean confirmEnableIncompatible(Component owner, ResourcePackCatalogItem target) {
            recordDialogThread();
            incompatibleTargets.add(target);
            runHook(incompatibleEnableHook);
            return incompatibleEnableConfirmed;
        }

        /// Returns the controlled permanent-delete decision.
        ///
        /// @param owner owning panel
        /// @param target target pack
        /// @return controlled confirmation
        @Override
        public boolean confirmDelete(Component owner, ResourcePackCatalogItem target) {
            recordDialogThread();
            deletedTargets.add(target);
            return deleteConfirmed;
        }

        /// Records a reveal and returns its controlled completion.
        ///
        /// @param target selected pack
        /// @return controlled completion
        @Override
        public CompletionStage<@Nullable Void> reveal(ResourcePackCatalogItem target) {
            if (revealError != null) {
                throw revealError;
            }
            revealedTargets.add(target);
            return revealCompletion;
        }

        /// Records a directory open and returns its controlled completion.
        ///
        /// @param resourcePackDirectory directory to open
        /// @return controlled completion
        @Override
        public CompletionStage<@Nullable Void> openResourcePackDirectory(Path resourcePackDirectory) {
            if (openError != null) {
                throw openError;
            }
            openedDirectories.add(resourcePackDirectory);
            return openCompletion;
        }

        /// Captures one failure presentation and its thread.
        ///
        /// @param owner owning panel
        /// @param title visible title
        /// @param detail visible detail
        @Override
        public void showFailure(Component owner, String title, String detail) {
            recordDialogThread();
            failurePresentations.add(new FailurePresentation(
                    title,
                    detail,
                    SwingUtilities.isEventDispatchThread()));
        }

        /// Returns immutable captured failure presentations.
        ///
        /// @return failure presentations
        private @Unmodifiable List<FailurePresentation> failures() {
            return List.copyOf(failurePresentations);
        }

        /// Records whether one dialog boundary ran on the EDT.
        private void recordDialogThread() {
            dialogCallsOnEventDispatchThread.compareAndSet(
                    true,
                    SwingUtilities.isEventDispatchThread());
        }

        /// Runs one optional modal hook.
        ///
        /// @param hook hook to run, or null
        private static void runHook(@Nullable Runnable hook) {
            if (hook != null) {
                hook.run();
            }
        }
    }
}
