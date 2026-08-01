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
import org.junit.jupiter.api.io.TempDir;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;
import space.minecraftstl.xyml.ui.swing.dialog.EditablePathChooser;

import javax.swing.JFileChooser;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.Component;
import java.awt.Desktop;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static space.minecraftstl.xyml.util.i18n.I18n.i18n;

/// Headless tests for resource-pack dialogs, immutable selections, and off-EDT local integration.
@NotNullByDefault
public final class DefaultResourcePackCatalogInteractionsTest {
    /// Localized action presentation used by every focused interaction test.
    private static final ResourcePackCatalogActionStrings STRINGS =
            new ResourcePackCatalogActionStrings(
                    "Import",
                    "Import ZIP files",
                    "Choose resource packs",
                    "ZIP resource packs",
                    "Enable",
                    "Enable selected pack",
                    "Disable",
                    "Disable selected pack",
                    "Incompatible resource pack",
                    "Enable incompatible '%s'?",
                    "Delete",
                    "Delete selected pack permanently",
                    "Delete '%s' permanently?",
                    "Reveal",
                    "Reveal in file manager",
                    "Open directory",
                    "Open installed resource-pack directory",
                    "Operation failed",
                    "Reveal failed",
                    "Open directory failed");

    /// Cancellation is empty, approval is immutable, and chooser configuration is exact.
    ///
    /// @param temporaryDirectory JUnit-owned chooser directory
    /// @throws IOException if test files cannot be created
    @Test
    public void configuresChooserAndReturnsImmutableSelections(
            @TempDir Path temporaryDirectory) throws IOException {
        Path first = Files.createFile(temporaryDirectory.resolve("first.zip"));
        Path second = Files.createFile(temporaryDirectory.resolve("second.zip"));
        FakeDialogActions dialogs = new FakeDialogActions();
        DefaultResourcePackCatalogInteractions interactions = interactions(
                Runnable::run, dialogs, new FakeDesktopActions(), new FakeFileActions());
        JPanel owner = valueOnEventDispatchThread(JPanel::new);

        @Unmodifiable List<Path> cancelled = valueOnEventDispatchThread(
                () -> interactions.chooseImportFiles(owner, temporaryDirectory));
        assertAll(
                () -> assertEquals(List.of(), cancelled),
                () -> assertThrows(UnsupportedOperationException.class,
                        () -> cancelled.add(first)));

        dialogs.openResult = JFileChooser.APPROVE_OPTION;
        dialogs.selectedFiles = List.of(first, second);
        @Unmodifiable List<Path> selected = valueOnEventDispatchThread(
                () -> interactions.chooseImportFiles(owner, temporaryDirectory));
        OpenDialogCall call = Objects.requireNonNull(dialogs.openCall);
        JFileChooser chooser = call.chooser();
        FileNameExtensionFilter filter = assertInstanceOf(
                FileNameExtensionFilter.class, chooser.getFileFilter());

        assertAll(
                () -> assertEquals(List.of(first, second), selected),
                () -> assertThrows(UnsupportedOperationException.class,
                        () -> selected.add(temporaryDirectory.resolve("third.zip"))),
                () -> assertSame(owner, call.owner()),
                () -> assertInstanceOf(EditablePathChooser.class, chooser),
                () -> assertTrue(call.onEventDispatchThread()),
                () -> assertEquals(temporaryDirectory.toFile(), chooser.getCurrentDirectory()),
                () -> assertEquals(STRINGS.importDialogTitle(), chooser.getDialogTitle()),
                () -> assertEquals(JFileChooser.FILES_ONLY, chooser.getFileSelectionMode()),
                () -> assertTrue(chooser.isMultiSelectionEnabled()),
                () -> assertFalse(chooser.isAcceptAllFileFilterUsed()),
                () -> assertEquals(STRINGS.zipFileDescription(), filter.getDescription()),
                () -> assertArrayEquals(new String[]{"zip"}, filter.getExtensions()));
    }

    /// Incompatible enablement and permanent deletion require explicit warning confirmation.
    @Test
    public void configuresSinglePackConfirmations() {
        FakeDialogActions dialogs = new FakeDialogActions();
        DefaultResourcePackCatalogInteractions interactions = interactions(
                Runnable::run, dialogs, new FakeDesktopActions(), new FakeFileActions());
        JPanel owner = valueOnEventDispatchThread(JPanel::new);
        ResourcePackCatalogItem incompatible = item("newer.zip", ResourcePackCompatibility.TOO_NEW);

        dialogs.confirmResult = JOptionPane.NO_OPTION;
        boolean enableRejected = valueOnEventDispatchThread(
                () -> interactions.confirmEnableIncompatible(owner, incompatible));
        dialogs.confirmResult = JOptionPane.YES_OPTION;
        boolean enableAccepted = valueOnEventDispatchThread(
                () -> interactions.confirmEnableIncompatible(owner, incompatible));
        boolean deleteAccepted = valueOnEventDispatchThread(
                () -> interactions.confirmDelete(owner, incompatible));

        ConfirmDialogCall enableCall = dialogs.confirmCalls.get(1);
        ConfirmDialogCall deleteCall = dialogs.confirmCalls.get(2);
        assertAll(
                () -> assertFalse(enableRejected),
                () -> assertTrue(enableAccepted),
                () -> assertTrue(deleteAccepted),
                () -> assertEquals("Enable incompatible 'newer.zip'?", enableCall.message()),
                () -> assertEquals(STRINGS.incompatibleEnableTitle(), enableCall.title()),
                () -> assertEquals(JOptionPane.YES_NO_OPTION, enableCall.optionType()),
                () -> assertEquals(JOptionPane.WARNING_MESSAGE, enableCall.messageType()),
                () -> assertTrue(enableCall.onEventDispatchThread()),
                () -> assertEquals("Delete 'newer.zip' permanently?", deleteCall.message()),
                () -> assertEquals(STRINGS.deleteAction(), deleteCall.title()),
                () -> assertEquals(JOptionPane.YES_NO_OPTION, deleteCall.optionType()),
                () -> assertEquals(JOptionPane.WARNING_MESSAGE, deleteCall.messageType()),
                () -> assertTrue(deleteCall.onEventDispatchThread()));
    }

    /// Selected-path batches use the legacy localized warnings without requiring row metadata.
    @Test
    public void configuresBatchConfirmations() {
        FakeDialogActions dialogs = new FakeDialogActions();
        dialogs.confirmResult = JOptionPane.YES_OPTION;
        DefaultResourcePackCatalogInteractions interactions = interactions(
                Runnable::run, dialogs, new FakeDesktopActions(), new FakeFileActions());
        JPanel owner = valueOnEventDispatchThread(JPanel::new);

        boolean enableAccepted = valueOnEventDispatchThread(
                () -> interactions.confirmEnableSelected(owner, 3));
        boolean deleteAccepted = valueOnEventDispatchThread(
                () -> interactions.confirmDeleteSelected(owner, 2));
        ConfirmDialogCall enableCall = dialogs.confirmCalls.get(0);
        ConfirmDialogCall deleteCall = dialogs.confirmCalls.get(1);

        assertAll(
                () -> assertTrue(enableAccepted),
                () -> assertTrue(deleteAccepted),
                () -> assertSame(owner, enableCall.owner()),
                () -> assertEquals(i18n("resourcepack.warning.manipulate"), enableCall.message()),
                () -> assertEquals(i18n("message.warning"), enableCall.title()),
                () -> assertEquals(JOptionPane.YES_NO_OPTION, enableCall.optionType()),
                () -> assertEquals(JOptionPane.WARNING_MESSAGE, enableCall.messageType()),
                () -> assertTrue(enableCall.onEventDispatchThread()),
                () -> assertSame(owner, deleteCall.owner()),
                () -> assertEquals(i18n("button.remove.confirm"), deleteCall.message()),
                () -> assertEquals(i18n("button.remove"), deleteCall.title()),
                () -> assertEquals(JOptionPane.YES_NO_OPTION, deleteCall.optionType()),
                () -> assertEquals(JOptionPane.WARNING_MESSAGE, deleteCall.messageType()),
                () -> assertTrue(deleteCall.onEventDispatchThread()));

        onEventDispatchThread(() -> assertAll(
                () -> assertThrows(IllegalArgumentException.class,
                        () -> interactions.confirmEnableSelected(owner, 0)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> interactions.confirmDeleteSelected(owner, -1))));
        assertEquals(2, dialogs.confirmCalls.size());
    }

    /// A compatible target is rejected before an incompatible-enable dialog can be shown.
    @Test
    public void rejectsCompatibleTargetFromIncompatibleConfirmation() {
        FakeDialogActions dialogs = new FakeDialogActions();
        DefaultResourcePackCatalogInteractions interactions = interactions(
                Runnable::run, dialogs, new FakeDesktopActions(), new FakeFileActions());
        JPanel owner = valueOnEventDispatchThread(JPanel::new);
        ResourcePackCatalogItem compatible = item("valid.zip", ResourcePackCompatibility.COMPATIBLE);

        AtomicReference<@Nullable Throwable> failure = new AtomicReference<>();
        onEventDispatchThread(() -> {
            try {
                interactions.confirmEnableIncompatible(owner, compatible);
            } catch (Throwable thrown) {
                failure.set(thrown);
            }
        });

        assertAll(
                () -> assertInstanceOf(IllegalArgumentException.class, failure.get()),
                () -> assertEquals(List.of(), dialogs.confirmCalls));
    }

    /// Every dialog boundary rejects non-EDT invocation before displaying UI.
    ///
    /// @param temporaryDirectory JUnit-owned chooser directory
    @Test
    public void rejectsDialogCallsOutsideEventDispatchThread(@TempDir Path temporaryDirectory) {
        FakeDialogActions dialogs = new FakeDialogActions();
        DefaultResourcePackCatalogInteractions interactions = interactions(
                Runnable::run, dialogs, new FakeDesktopActions(), new FakeFileActions());
        JPanel owner = valueOnEventDispatchThread(JPanel::new);
        ResourcePackCatalogItem target = item("pack.zip", ResourcePackCompatibility.INVALID);

        assertAll(
                () -> assertThrows(IllegalStateException.class,
                        () -> interactions.chooseImportFiles(owner, temporaryDirectory)),
                () -> assertThrows(IllegalStateException.class,
                        () -> interactions.confirmEnableIncompatible(owner, target)),
                () -> assertThrows(IllegalStateException.class,
                        () -> interactions.confirmEnableSelected(owner, 1)),
                () -> assertThrows(IllegalStateException.class,
                        () -> interactions.confirmDelete(owner, target)),
                () -> assertThrows(IllegalStateException.class,
                        () -> interactions.confirmDeleteSelected(owner, 1)),
                () -> assertThrows(IllegalStateException.class,
                        () -> interactions.showFailure(owner, "title", "detail")),
                () -> assertEquals(0, dialogs.totalCalls()));
    }

    /// Failure feedback is an exact error dialog on the event-dispatch thread.
    @Test
    public void showsFailureOnEventDispatchThread() {
        FakeDialogActions dialogs = new FakeDialogActions();
        DefaultResourcePackCatalogInteractions interactions = interactions(
                Runnable::run, dialogs, new FakeDesktopActions(), new FakeFileActions());
        JPanel owner = valueOnEventDispatchThread(JPanel::new);

        onEventDispatchThread(() -> interactions.showFailure(owner, "Import failed", "broken.zip"));

        MessageDialogCall call = Objects.requireNonNull(dialogs.messageCall);
        assertAll(
                () -> assertSame(owner, call.owner()),
                () -> assertEquals("broken.zip", call.message()),
                () -> assertEquals("Import failed", call.title()),
                () -> assertEquals(JOptionPane.ERROR_MESSAGE, call.messageType()),
                () -> assertTrue(call.onEventDispatchThread()));
    }

    /// Dedicated browse-file-directory support wins and runs only when the executor runs it.
    @Test
    public void revealPrefersBrowseFileDirectoryOnInjectedExecutor() {
        QueuedExecutor executor = new QueuedExecutor();
        FakeDesktopActions desktop = new FakeDesktopActions();
        desktop.browseSupported = true;
        DefaultResourcePackCatalogInteractions interactions = interactions(
                executor, new FakeDialogActions(), desktop, new FakeFileActions());
        ResourcePackCatalogItem target = item("pack.zip", ResourcePackCompatibility.COMPATIBLE);

        CompletionStage<@Nullable Void> completion = interactions.reveal(target);
        assertAll(
                () -> assertFalse(completion.toCompletableFuture().isDone()),
                () -> assertEquals(List.of(), desktop.supportQueries));

        executor.runNext();

        assertAll(
                () -> assertTrue(completion.toCompletableFuture().isDone()),
                () -> assertEquals(List.of(Desktop.Action.BROWSE_FILE_DIR), desktop.supportQueries),
                () -> assertEquals(List.of(target.path()), desktop.browsedTargets),
                () -> assertEquals(List.of(), desktop.openedDirectories),
                () -> assertFalse(desktop.anyCallOnEventDispatchThread()));
        completion.toCompletableFuture().join();
    }

    /// Unsupported dedicated reveal falls back to opening the pack's containing directory.
    @Test
    public void revealFallsBackToOpeningParentDirectory() {
        FakeDesktopActions desktop = new FakeDesktopActions();
        desktop.openSupported = true;
        DefaultResourcePackCatalogInteractions interactions = interactions(
                Runnable::run, new FakeDialogActions(), desktop, new FakeFileActions());
        ResourcePackCatalogItem target = item("pack.zip", ResourcePackCompatibility.COMPATIBLE);

        interactions.reveal(target).toCompletableFuture().join();

        assertAll(
                () -> assertEquals(
                        List.of(Desktop.Action.BROWSE_FILE_DIR, Desktop.Action.OPEN),
                        desktop.supportQueries),
                () -> assertEquals(List.of(target.path().getParent()), desktop.openedDirectories),
                () -> assertEquals(List.of(), desktop.browsedTargets),
                () -> assertFalse(desktop.anyCallOnEventDispatchThread()));
    }

    /// Missing reveal and open support fails without invoking either desktop operation.
    @Test
    public void revealFailsWhenDesktopCannotRevealOrOpen() {
        FakeDesktopActions desktop = new FakeDesktopActions();
        DefaultResourcePackCatalogInteractions interactions = interactions(
                Runnable::run, new FakeDialogActions(), desktop, new FakeFileActions());

        CompletionException failure = assertThrows(
                CompletionException.class,
                () -> interactions.reveal(item("pack.zip", ResourcePackCompatibility.COMPATIBLE))
                        .toCompletableFuture()
                        .join());

        assertAll(
                () -> assertInstanceOf(UnsupportedOperationException.class, failure.getCause()),
                () -> assertEquals(
                        List.of(Desktop.Action.BROWSE_FILE_DIR, Desktop.Action.OPEN),
                        desktop.supportQueries),
                () -> assertEquals(List.of(), desktop.browsedTargets),
                () -> assertEquals(List.of(), desktop.openedDirectories));
    }

    /// Desktop reveal failures complete the stage with the original exception object.
    @Test
    public void revealPreservesDesktopFailureIdentity() {
        IOException expected = new IOException("browse failed");
        FakeDesktopActions desktop = new FakeDesktopActions();
        desktop.browseSupported = true;
        desktop.browseFailure = expected;
        DefaultResourcePackCatalogInteractions interactions = interactions(
                Runnable::run, new FakeDialogActions(), desktop, new FakeFileActions());

        CompletionStage<@Nullable Void> completion = interactions.reveal(
                item("pack.zip", ResourcePackCompatibility.COMPATIBLE));

        assertFailureIdentity(completion, expected);
        assertEquals(List.of(), desktop.openedDirectories);
    }

    /// Worker errors complete their stages before propagating out of the executor command.
    ///
    /// @param temporaryDirectory JUnit-owned resource-pack parent
    @Test
    public void workerErrorsCompleteStagesBeforeRethrow(@TempDir Path temporaryDirectory) {
        QueuedExecutor executor = new QueuedExecutor();
        AssertionError revealError = new AssertionError("browse error");
        FakeDesktopActions desktop = new FakeDesktopActions();
        desktop.browseSupported = true;
        desktop.browseError = revealError;
        AssertionError openError = new AssertionError("create error");
        FakeFileActions files = new FakeFileActions();
        files.createError = openError;
        DefaultResourcePackCatalogInteractions interactions = interactions(
                executor, new FakeDialogActions(), desktop, files);

        CompletionStage<@Nullable Void> reveal = interactions.reveal(
                item("pack.zip", ResourcePackCompatibility.COMPATIBLE));
        CompletionStage<@Nullable Void> open = interactions.openResourcePackDirectory(
                temporaryDirectory.resolve("resourcepacks"));

        assertSame(revealError, assertThrows(AssertionError.class, executor::runNext));
        assertFailureIdentity(reveal, revealError);
        assertSame(openError, assertThrows(AssertionError.class, executor::runNext));
        assertFailureIdentity(open, openError);
    }

    /// Submission errors propagate synchronously before any desktop or file-system boundary runs.
    ///
    /// @param temporaryDirectory JUnit-owned resource-pack directory
    @Test
    public void submissionErrorsAreRethrownBeforeBoundaryCalls(@TempDir Path temporaryDirectory) {
        AssertionError expected = new AssertionError("executor error");
        Executor failingExecutor = command -> {
            throw expected;
        };
        FakeDesktopActions desktop = new FakeDesktopActions();
        FakeFileActions files = new FakeFileActions();
        DefaultResourcePackCatalogInteractions interactions = interactions(
                failingExecutor, new FakeDialogActions(), desktop, files);

        assertAll(
                () -> assertSame(expected, assertThrows(
                        AssertionError.class,
                        () -> interactions.reveal(
                                item("pack.zip", ResourcePackCompatibility.COMPATIBLE)))),
                () -> assertSame(expected, assertThrows(
                        AssertionError.class,
                        () -> interactions.openResourcePackDirectory(temporaryDirectory))),
                () -> assertEquals(List.of(), desktop.supportQueries),
                () -> assertEquals(List.of(), files.createdDirectories));
    }

    /// Opening the catalog ensures its normalized directory before using the desktop handler.
    ///
    /// @param temporaryDirectory JUnit-owned parent directory
    @Test
    public void openResourcePackDirectoryCreatesThenOpensOffEdt(@TempDir Path temporaryDirectory) {
        List<String> events = new ArrayList<>();
        FakeDesktopActions desktop = new FakeDesktopActions(events);
        desktop.openSupported = true;
        FakeFileActions files = new FakeFileActions(events);
        DefaultResourcePackCatalogInteractions interactions = interactions(
                Runnable::run, new FakeDialogActions(), desktop, files);
        Path requested = temporaryDirectory.resolve("nested").resolve("..").resolve("resourcepacks");
        Path expected = requested.toAbsolutePath().normalize();

        interactions.openResourcePackDirectory(requested).toCompletableFuture().join();

        assertAll(
                () -> assertEquals(List.of(expected), files.createdDirectories),
                () -> assertEquals(List.of(expected), desktop.openedDirectories),
                () -> assertEquals(
                        List.of("files:create", "desktop:support:OPEN", "desktop:open"),
                        events),
                () -> assertFalse(files.anyCallOnEventDispatchThread()),
                () -> assertFalse(desktop.anyCallOnEventDispatchThread()));
    }

    /// File-system failures retain identity and stop before any desktop integration.
    ///
    /// @param temporaryDirectory JUnit-owned parent directory
    @Test
    public void openResourcePackDirectoryPreservesFileFailureIdentity(
            @TempDir Path temporaryDirectory) {
        IOException expected = new IOException("create failed");
        FakeDesktopActions desktop = new FakeDesktopActions();
        desktop.openSupported = true;
        FakeFileActions files = new FakeFileActions();
        files.createFailure = expected;
        DefaultResourcePackCatalogInteractions interactions = interactions(
                Runnable::run, new FakeDialogActions(), desktop, files);

        CompletionStage<@Nullable Void> completion = interactions.openResourcePackDirectory(
                temporaryDirectory.resolve("resourcepacks"));

        assertAll(
                () -> assertFailureIdentity(completion, expected),
                () -> assertEquals(List.of(), desktop.supportQueries),
                () -> assertEquals(List.of(), desktop.openedDirectories));
    }

    /// Desktop open failures retain identity after the directory has been ensured.
    ///
    /// @param temporaryDirectory JUnit-owned parent directory
    @Test
    public void openResourcePackDirectoryPreservesDesktopFailureIdentity(
            @TempDir Path temporaryDirectory) {
        IOException expected = new IOException("open failed");
        FakeDesktopActions desktop = new FakeDesktopActions();
        desktop.openSupported = true;
        desktop.openFailure = expected;
        FakeFileActions files = new FakeFileActions();
        DefaultResourcePackCatalogInteractions interactions = interactions(
                Runnable::run, new FakeDialogActions(), desktop, files);

        CompletionStage<@Nullable Void> completion = interactions.openResourcePackDirectory(
                temporaryDirectory.resolve("resourcepacks"));

        assertAll(
                () -> assertFailureIdentity(completion, expected),
                () -> assertEquals(1, files.createdDirectories.size()),
                () -> assertEquals(1, desktop.openedDirectories.size()));
    }

    /// Unsupported directory opening still ensures the directory, then fails deterministically.
    ///
    /// @param temporaryDirectory JUnit-owned parent directory
    @Test
    public void openResourcePackDirectoryFailsWhenDesktopCannotOpen(
            @TempDir Path temporaryDirectory) {
        FakeDesktopActions desktop = new FakeDesktopActions();
        FakeFileActions files = new FakeFileActions();
        DefaultResourcePackCatalogInteractions interactions = interactions(
                Runnable::run, new FakeDialogActions(), desktop, files);
        Path expected = temporaryDirectory.resolve("resourcepacks").toAbsolutePath().normalize();

        CompletionException failure = assertThrows(
                CompletionException.class,
                () -> interactions.openResourcePackDirectory(expected).toCompletableFuture().join());

        assertAll(
                () -> assertInstanceOf(UnsupportedOperationException.class, failure.getCause()),
                () -> assertEquals(List.of(expected), files.createdDirectories),
                () -> assertEquals(List.of(Desktop.Action.OPEN), desktop.supportQueries),
                () -> assertEquals(List.of(), desktop.openedDirectories));
    }

    /// Executor rejection is returned as failed stages and retains the original rejection object.
    ///
    /// @param temporaryDirectory JUnit-owned catalog directory
    @Test
    public void preservesExecutorRejectionIdentity(@TempDir Path temporaryDirectory) {
        RejectedExecutionException expected = new RejectedExecutionException("rejected");
        Executor rejectingExecutor = command -> {
            throw expected;
        };
        FakeDesktopActions desktop = new FakeDesktopActions();
        FakeFileActions files = new FakeFileActions();
        DefaultResourcePackCatalogInteractions interactions = interactions(
                rejectingExecutor, new FakeDialogActions(), desktop, files);

        CompletionStage<@Nullable Void> reveal = interactions.reveal(
                item("pack.zip", ResourcePackCompatibility.COMPATIBLE));
        CompletionStage<@Nullable Void> open = interactions.openResourcePackDirectory(temporaryDirectory);

        assertAll(
                () -> assertFailureIdentity(reveal, expected),
                () -> assertFailureIdentity(open, expected),
                () -> assertEquals(List.of(), desktop.supportQueries),
                () -> assertEquals(List.of(), files.createdDirectories));
    }

    /// A direct executor invoked from the EDT fails before any Desktop or Files boundary call.
    ///
    /// @param temporaryDirectory JUnit-owned catalog directory
    @Test
    public void preventsIoWhenExecutorRunsWorkOnEventDispatchThread(
            @TempDir Path temporaryDirectory) {
        FakeDesktopActions desktop = new FakeDesktopActions();
        FakeFileActions files = new FakeFileActions();
        DefaultResourcePackCatalogInteractions interactions = interactions(
                Runnable::run, new FakeDialogActions(), desktop, files);

        CompletionStage<@Nullable Void> reveal = valueOnEventDispatchThread(() ->
                interactions.reveal(item("pack.zip", ResourcePackCompatibility.COMPATIBLE)));
        CompletionStage<@Nullable Void> open = valueOnEventDispatchThread(() ->
                interactions.openResourcePackDirectory(temporaryDirectory));

        assertAll(
                () -> assertFailureType(reveal, IllegalStateException.class),
                () -> assertFailureType(open, IllegalStateException.class),
                () -> assertEquals(List.of(), desktop.supportQueries),
                () -> assertEquals(List.of(), files.createdDirectories));
    }

    /// Constructor and command boundaries reject null collaborators and values synchronously.
    @Test
    public void rejectsNullDependenciesAndArguments() {
        FakeDialogActions dialogs = new FakeDialogActions();
        FakeDesktopActions desktop = new FakeDesktopActions();
        FakeFileActions files = new FakeFileActions();
        DefaultResourcePackCatalogInteractions interactions = interactions(
                Runnable::run, dialogs, desktop, files);

        assertAll(
                () -> assertThrows(NullPointerException.class,
                        () -> new DefaultResourcePackCatalogInteractions(null, Runnable::run)),
                () -> assertThrows(NullPointerException.class,
                        () -> new DefaultResourcePackCatalogInteractions(STRINGS, null)),
                () -> assertThrows(NullPointerException.class,
                        () -> new DefaultResourcePackCatalogInteractions(
                                STRINGS, Runnable::run, null, desktop, files)),
                () -> assertThrows(NullPointerException.class,
                        () -> new DefaultResourcePackCatalogInteractions(
                                STRINGS, Runnable::run, dialogs, null, files)),
                () -> assertThrows(NullPointerException.class,
                        () -> new DefaultResourcePackCatalogInteractions(
                                STRINGS, Runnable::run, dialogs, desktop, null)),
                () -> assertThrows(NullPointerException.class, () -> interactions.reveal(null)),
                () -> assertThrows(NullPointerException.class,
                        () -> interactions.openResourcePackDirectory(null)));
    }

    /// Creates one normalized resource-pack catalog item for interaction tests.
    ///
    /// @param fileName exact pack file name
    /// @param compatibility compatibility state
    /// @return immutable test item
    private static ResourcePackCatalogItem item(
            String fileName,
            ResourcePackCompatibility compatibility) {
        return new ResourcePackCatalogItem(
                Path.of("resourcepacks").resolve(fileName),
                fileName,
                fileName,
                "Description",
                compatibility,
                false);
    }

    /// Creates interactions with the shared presentation and injected test boundaries.
    ///
    /// @param executor caller-owned executor
    /// @param dialogs dialog fake
    /// @param desktop desktop fake
    /// @param files file-system fake
    /// @return test interactions
    private static DefaultResourcePackCatalogInteractions interactions(
            Executor executor,
            FakeDialogActions dialogs,
            FakeDesktopActions desktop,
            FakeFileActions files) {
        return new DefaultResourcePackCatalogInteractions(
                STRINGS, executor, dialogs, desktop, files);
    }

    /// Verifies one failed completion retains the exact expected cause object.
    ///
    /// @param completion failed stage
    /// @param expected expected cause identity
    private static void assertFailureIdentity(
            CompletionStage<@Nullable Void> completion,
            Throwable expected) {
        CompletionException failure = assertThrows(
                CompletionException.class,
                () -> completion.toCompletableFuture().join());
        assertSame(expected, failure.getCause());
    }

    /// Verifies one failed completion has the expected cause type.
    ///
    /// @param completion failed stage
    /// @param expectedType expected cause type
    private static void assertFailureType(
            CompletionStage<@Nullable Void> completion,
            Class<? extends Throwable> expectedType) {
        CompletionException failure = assertThrows(
                CompletionException.class,
                () -> completion.toCompletableFuture().join());
        assertInstanceOf(expectedType, failure.getCause());
    }

    /// Runs a non-null value-producing operation synchronously on the EDT.
    ///
    /// @param operation operation to run
    /// @param <T> non-null result type
    /// @return operation result
    private static <T extends Object> T valueOnEventDispatchThread(Supplier<T> operation) {
        AtomicReference<@Nullable T> result = new AtomicReference<>();
        EdtDispatcher.executeAndWait(() -> result.set(operation.get()));
        return Objects.requireNonNull(result.get(), "EDT operation returned null");
    }

    /// Runs one operation synchronously on the EDT.
    ///
    /// @param operation operation to run
    private static void onEventDispatchThread(Runnable operation) {
        EdtDispatcher.executeAndWait(operation);
    }

    /// One captured chooser invocation.
    ///
    /// @param chooser configured chooser
    /// @param owner dialog owner
    /// @param onEventDispatchThread whether invocation occurred on the EDT
    @NotNullByDefault
    private record OpenDialogCall(
            JFileChooser chooser,
            Component owner,
            boolean onEventDispatchThread) {
    }

    /// One captured confirmation invocation.
    ///
    /// @param owner dialog owner
    /// @param message confirmation content
    /// @param title dialog title
    /// @param optionType dialog option type
    /// @param messageType dialog message type
    /// @param onEventDispatchThread whether invocation occurred on the EDT
    @NotNullByDefault
    private record ConfirmDialogCall(
            Component owner,
            Object message,
            String title,
            int optionType,
            int messageType,
            boolean onEventDispatchThread) {
    }

    /// One captured message dialog invocation.
    ///
    /// @param owner dialog owner
    /// @param message displayed content
    /// @param title dialog title
    /// @param messageType dialog message type
    /// @param onEventDispatchThread whether invocation occurred on the EDT
    @NotNullByDefault
    private record MessageDialogCall(
            Component owner,
            Object message,
            String title,
            int messageType,
            boolean onEventDispatchThread) {
    }

    /// Dialog fake that captures chooser, confirmation, and error-message calls.
    @NotNullByDefault
    private static final class FakeDialogActions implements ResourcePackDialogActions {
        /// Result returned by the next chooser call.
        private int openResult = JFileChooser.CANCEL_OPTION;

        /// Files installed into the chooser before an approved result.
        private @Unmodifiable List<Path> selectedFiles = List.of();

        /// Result returned by confirmation calls.
        private int confirmResult = JOptionPane.NO_OPTION;

        /// Most recent chooser invocation.
        private @Nullable OpenDialogCall openCall;

        /// Confirmation invocations in order.
        private final List<ConfirmDialogCall> confirmCalls = new ArrayList<>();

        /// Most recent message invocation.
        private @Nullable MessageDialogCall messageCall;

        /// Creates an idle dialog fake.
        private FakeDialogActions() {
        }

        /// Captures the chooser and installs configured selected files.
        @Override
        public int showOpenDialog(JFileChooser chooser, Component owner) {
            openCall = new OpenDialogCall(
                    chooser, owner, SwingUtilities.isEventDispatchThread());
            if (openResult == JFileChooser.APPROVE_OPTION) {
                chooser.setSelectedFiles(selectedFiles.stream()
                        .map(Path::toFile)
                        .toArray(File[]::new));
            }
            return openResult;
        }

        /// Captures and returns the configured confirmation result.
        @Override
        public int showConfirmDialog(
                Component owner,
                Object message,
                String title,
                int optionType,
                int messageType) {
            confirmCalls.add(new ConfirmDialogCall(
                    owner,
                    message,
                    title,
                    optionType,
                    messageType,
                    SwingUtilities.isEventDispatchThread()));
            return confirmResult;
        }

        /// Captures one message invocation.
        @Override
        public void showMessageDialog(
                Component owner,
                Object message,
                String title,
                int messageType) {
            messageCall = new MessageDialogCall(
                    owner, message, title, messageType, SwingUtilities.isEventDispatchThread());
        }

        /// Counts all dialog invocations captured so far.
        ///
        /// @return number of captured calls
        private int totalCalls() {
            return (openCall == null ? 0 : 1)
                    + confirmCalls.size()
                    + (messageCall == null ? 0 : 1);
        }
    }

    /// Desktop fake with independently configurable action support and failures.
    @NotNullByDefault
    private static final class FakeDesktopActions implements ResourcePackDesktopActions {
        /// Shared operation trace.
        private final List<String> events;

        /// Whether dedicated reveal is supported.
        private boolean browseSupported;

        /// Whether directory opening is supported.
        private boolean openSupported;

        /// Optional dedicated-reveal failure.
        private @Nullable IOException browseFailure;

        /// Optional fatal dedicated-reveal error.
        private @Nullable Error browseError;

        /// Optional directory-open failure.
        private @Nullable IOException openFailure;

        /// Optional fatal directory-open error.
        private @Nullable Error openError;

        /// Action support queries in invocation order.
        private final List<Desktop.Action> supportQueries = new ArrayList<>();

        /// Dedicated reveal targets in invocation order.
        private final List<Path> browsedTargets = new ArrayList<>();

        /// Opened directories in invocation order.
        private final List<Path> openedDirectories = new ArrayList<>();

        /// EDT state captured for every boundary call.
        private final List<Boolean> eventDispatchThreadCalls = new ArrayList<>();

        /// Creates an unsupported desktop fake with an independent trace.
        private FakeDesktopActions() {
            this(new ArrayList<>());
        }

        /// Creates an unsupported desktop fake using a shared operation trace.
        ///
        /// @param events shared operation trace
        private FakeDesktopActions(List<String> events) {
            this.events = events;
        }

        /// Captures and answers one support query.
        @Override
        public boolean isSupported(Desktop.Action action) {
            eventDispatchThreadCalls.add(SwingUtilities.isEventDispatchThread());
            supportQueries.add(action);
            events.add("desktop:support:" + action.name());
            if (action == Desktop.Action.BROWSE_FILE_DIR) {
                return browseSupported;
            }
            if (action == Desktop.Action.OPEN) {
                return openSupported;
            }
            return false;
        }

        /// Captures or fails one dedicated reveal call.
        @Override
        public void browseFileDirectory(Path target) throws IOException {
            eventDispatchThreadCalls.add(SwingUtilities.isEventDispatchThread());
            browsedTargets.add(target);
            events.add("desktop:browse");
            if (browseError != null) {
                throw browseError;
            }
            if (browseFailure != null) {
                throw browseFailure;
            }
        }

        /// Captures one fallback or catalog directory open call.
        @Override
        public void open(Path directory) throws IOException {
            eventDispatchThreadCalls.add(SwingUtilities.isEventDispatchThread());
            openedDirectories.add(directory);
            events.add("desktop:open");
            if (openError != null) {
                throw openError;
            }
            if (openFailure != null) {
                throw openFailure;
            }
        }

        /// Reports whether any boundary call occurred on the EDT.
        ///
        /// @return whether any call occurred on the EDT
        private boolean anyCallOnEventDispatchThread() {
            return eventDispatchThreadCalls.stream().anyMatch(Boolean::booleanValue);
        }
    }

    /// File-system fake that captures directory creation and its executing thread.
    @NotNullByDefault
    private static final class FakeFileActions implements ResourcePackFileActions {
        /// Shared operation trace.
        private final List<String> events;

        /// Optional directory-creation failure.
        private @Nullable IOException createFailure;

        /// Optional fatal directory-creation error.
        private @Nullable Error createError;

        /// Ensured directories in invocation order.
        private final List<Path> createdDirectories = new ArrayList<>();

        /// EDT state captured for every boundary call.
        private final List<Boolean> eventDispatchThreadCalls = new ArrayList<>();

        /// Creates a successful fake with an independent trace.
        private FakeFileActions() {
            this(new ArrayList<>());
        }

        /// Creates a successful fake using a shared operation trace.
        ///
        /// @param events shared operation trace
        private FakeFileActions(List<String> events) {
            this.events = events;
        }

        /// Captures or fails one directory-creation request.
        @Override
        public void createDirectories(Path directory) throws IOException {
            eventDispatchThreadCalls.add(SwingUtilities.isEventDispatchThread());
            createdDirectories.add(directory);
            events.add("files:create");
            if (createError != null) {
                throw createError;
            }
            if (createFailure != null) {
                throw createFailure;
            }
        }

        /// Reports whether any boundary call occurred on the EDT.
        ///
        /// @return whether any call occurred on the EDT
        private boolean anyCallOnEventDispatchThread() {
            return eventDispatchThreadCalls.stream().anyMatch(Boolean::booleanValue);
        }
    }

    /// Executor that exposes each submitted command for deterministic scheduling assertions.
    @NotNullByDefault
    private static final class QueuedExecutor implements Executor {
        /// Commands awaiting explicit test execution.
        private final Deque<Runnable> commands = new ArrayDeque<>();

        /// Creates an empty executor queue.
        private QueuedExecutor() {
        }

        /// Queues one command without running it.
        @Override
        public void execute(Runnable command) {
            commands.addLast(command);
        }

        /// Runs the next submitted command.
        private void runNext() {
            Objects.requireNonNull(commands.pollFirst(), "No queued command").run();
        }
    }
}
