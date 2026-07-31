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
package space.minecraftstl.xyml.ui.swing.page.schematics;

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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Headless tests for schematic dialogs, immutable selection, and desktop reveal arbitration.
@NotNullByDefault
public final class DefaultSchematicBrowserInteractionsTest {
    /// Localized action presentation used by every focused interaction test.
    private static final SchematicBrowserActionStrings STRINGS = new SchematicBrowserActionStrings(
            "Import",
            "Import Litematics",
            "Choose Litematic files",
            "Litematic schematic files",
            "Create directory",
            "Create a child directory",
            "Directory name",
            "Delete",
            "Delete selected item",
            "Delete '%s' permanently?",
            "Reveal",
            "Reveal in file manager",
            "Writing",
            "Write failed",
            "Operation failed",
            "Reveal failed");

    /// Cancellation is empty, approval is immutable, and chooser configuration is exact.
    ///
    /// @param temporaryDirectory JUnit-owned chooser directory
    /// @throws IOException if test files cannot be created
    @Test
    public void configuresChooserAndReturnsImmutableSelections(
            @TempDir Path temporaryDirectory) throws IOException {
        Path first = Files.createFile(temporaryDirectory.resolve("first.litematic"));
        Path second = Files.createFile(temporaryDirectory.resolve("second.litematic"));
        FakeDialogActions dialogs = new FakeDialogActions();
        DefaultSchematicBrowserInteractions interactions = interactions(
                Runnable::run, dialogs, new FakeDesktopActions());
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
                        () -> selected.add(temporaryDirectory.resolve("third.litematic"))),
                () -> assertSame(owner, call.owner()),
                () -> assertInstanceOf(EditablePathChooser.class, chooser),
                () -> assertTrue(call.onEventDispatchThread()),
                () -> assertEquals(temporaryDirectory.toFile(), chooser.getCurrentDirectory()),
                () -> assertEquals(STRINGS.importDialogTitle(), chooser.getDialogTitle()),
                () -> assertEquals(JFileChooser.FILES_ONLY, chooser.getFileSelectionMode()),
                () -> assertTrue(chooser.isMultiSelectionEnabled()),
                () -> assertFalse(chooser.isAcceptAllFileFilterUsed()),
                () -> assertEquals(STRINGS.litematicFileDescription(), filter.getDescription()),
                () -> assertArrayEquals(new String[]{"litematic"}, filter.getExtensions()),
                () -> assertTrue(filter.accept(first.toFile())),
                () -> assertFalse(filter.accept(temporaryDirectory.resolve("notes.txt").toFile())));
    }

    /// Prompt, confirmation, and failure dialogs receive exact localized parameters on the EDT.
    @Test
    public void delegatesExactDialogParametersOnEventDispatchThread() {
        FakeDialogActions dialogs = new FakeDialogActions();
        dialogs.inputResult = "child";
        dialogs.confirmResult = JOptionPane.YES_OPTION;
        DefaultSchematicBrowserInteractions interactions = interactions(
                Runnable::run, dialogs, new FakeDesktopActions());
        JPanel owner = valueOnEventDispatchThread(JPanel::new);
        SchematicDirectoryItem target = new SchematicDirectoryItem(
                Path.of("schematics", "target"), "target");

        String input = valueOnEventDispatchThread(() -> interactions.promptDirectoryName(owner));
        boolean confirmed = valueOnEventDispatchThread(() -> interactions.confirmDelete(owner, target));
        onEventDispatchThread(() -> interactions.showFailure(owner, "Failure title", "Failure detail"));

        InputDialogCall inputCall = Objects.requireNonNull(dialogs.inputCall);
        ConfirmDialogCall confirmCall = Objects.requireNonNull(dialogs.confirmCall);
        MessageDialogCall messageCall = Objects.requireNonNull(dialogs.messageCall);
        assertAll(
                () -> assertEquals("child", input),
                () -> assertSame(owner, inputCall.owner()),
                () -> assertEquals(STRINGS.createDirectoryPrompt(), inputCall.message()),
                () -> assertEquals(STRINGS.createDirectoryAction(), inputCall.title()),
                () -> assertEquals(JOptionPane.QUESTION_MESSAGE, inputCall.messageType()),
                () -> assertTrue(inputCall.onEventDispatchThread()),
                () -> assertTrue(confirmed),
                () -> assertSame(owner, confirmCall.owner()),
                () -> assertEquals("Delete 'target' permanently?", confirmCall.message()),
                () -> assertEquals(STRINGS.deleteAction(), confirmCall.title()),
                () -> assertEquals(JOptionPane.YES_NO_OPTION, confirmCall.optionType()),
                () -> assertEquals(JOptionPane.WARNING_MESSAGE, confirmCall.messageType()),
                () -> assertTrue(confirmCall.onEventDispatchThread()),
                () -> assertSame(owner, messageCall.owner()),
                () -> assertEquals("Failure detail", messageCall.message()),
                () -> assertEquals("Failure title", messageCall.title()),
                () -> assertEquals(JOptionPane.ERROR_MESSAGE, messageCall.messageType()),
                () -> assertTrue(messageCall.onEventDispatchThread()));

        dialogs.inputResult = null;
        dialogs.confirmResult = JOptionPane.NO_OPTION;
        AtomicReference<@Nullable String> cancelledInput = new AtomicReference<>("pending");
        onEventDispatchThread(() -> cancelledInput.set(interactions.promptDirectoryName(owner)));
        assertAll(
                () -> assertNull(cancelledInput.get()),
                () -> assertFalse(valueOnEventDispatchThread(
                        () -> interactions.confirmDelete(owner, target))));
    }

    /// Every blocking dialog operation rejects calls outside the Swing event-dispatch thread.
    ///
    /// @param temporaryDirectory JUnit-owned chooser directory
    @Test
    public void rejectsDialogCallsOutsideEventDispatchThread(@TempDir Path temporaryDirectory) {
        FakeDialogActions dialogs = new FakeDialogActions();
        DefaultSchematicBrowserInteractions interactions = interactions(
                Runnable::run, dialogs, new FakeDesktopActions());
        JPanel owner = new JPanel();
        SchematicDirectoryItem target = new SchematicDirectoryItem(
                temporaryDirectory.resolve("target"), "target");

        assertAll(
                () -> assertThrows(IllegalStateException.class,
                        () -> interactions.chooseImportFiles(owner, temporaryDirectory)),
                () -> assertThrows(IllegalStateException.class,
                        () -> interactions.promptDirectoryName(owner)),
                () -> assertThrows(IllegalStateException.class,
                        () -> interactions.confirmDelete(owner, target)),
                () -> assertThrows(IllegalStateException.class,
                        () -> interactions.showFailure(owner, "title", "detail")),
                () -> assertEquals(0, dialogs.totalCalls()));
    }

    /// Dedicated browse-file-directory support wins and executes only when the executor runs it.
    @Test
    public void revealPrefersBrowseFileDirectoryOnInjectedExecutor() {
        QueuedExecutor executor = new QueuedExecutor();
        FakeDesktopActions desktop = new FakeDesktopActions();
        desktop.browseSupported = true;
        DefaultSchematicBrowserInteractions interactions = interactions(
                executor, new FakeDialogActions(), desktop);
        SchematicDirectoryItem target = new SchematicDirectoryItem(
                Path.of("schematics", "directory"), "directory");

        CompletionStage<@Nullable Void> completion = interactions.reveal(target);
        assertAll(
                () -> assertFalse(completion.toCompletableFuture().isDone()),
                () -> assertEquals(List.of(), desktop.supportQueries));

        executor.runNext();

        assertAll(
                () -> assertTrue(completion.toCompletableFuture().isDone()),
                () -> assertEquals(List.of(Desktop.Action.BROWSE_FILE_DIR), desktop.supportQueries),
                () -> assertEquals(List.of(target.path()), desktop.browsedTargets),
                () -> assertEquals(List.of(), desktop.openedDirectories));
        completion.toCompletableFuture().join();
    }

    /// Unsupported dedicated reveal falls back to opening a directory row itself or a file parent.
    @Test
    public void revealFallsBackToOpeningDirectoryOrFileParent() {
        Path directory = Path.of("schematics", "directory");
        Path file = directory.resolve("example.litematic");
        FakeDesktopActions directoryDesktop = openOnlyDesktop();
        FakeDesktopActions fileDesktop = openOnlyDesktop();
        DefaultSchematicBrowserInteractions directoryInteractions = interactions(
                Runnable::run, new FakeDialogActions(), directoryDesktop);
        DefaultSchematicBrowserInteractions fileInteractions = interactions(
                Runnable::run, new FakeDialogActions(), fileDesktop);

        directoryInteractions.reveal(new SchematicDirectoryItem(directory, "directory"))
                .toCompletableFuture().join();
        fileInteractions.reveal(new SchematicFileItem(
                file, "example.litematic", null, "unreadable"))
                .toCompletableFuture().join();

        assertAll(
                () -> assertEquals(
                        List.of(Desktop.Action.BROWSE_FILE_DIR, Desktop.Action.OPEN),
                        directoryDesktop.supportQueries),
                () -> assertEquals(List.of(directory), directoryDesktop.openedDirectories),
                () -> assertEquals(
                        List.of(Desktop.Action.BROWSE_FILE_DIR, Desktop.Action.OPEN),
                        fileDesktop.supportQueries),
                () -> assertEquals(List.of(directory), fileDesktop.openedDirectories));
    }

    /// Missing reveal and open support fails deterministically without invoking either desktop action.
    @Test
    public void revealFailsWhenDesktopCannotRevealOrOpen() {
        FakeDesktopActions desktop = new FakeDesktopActions();
        DefaultSchematicBrowserInteractions interactions = interactions(
                Runnable::run, new FakeDialogActions(), desktop);
        SchematicDirectoryItem target = new SchematicDirectoryItem(
                Path.of("schematics", "directory"), "directory");

        CompletionException failure = assertThrows(
                CompletionException.class,
                () -> interactions.reveal(target).toCompletableFuture().join());

        assertAll(
                () -> assertInstanceOf(UnsupportedOperationException.class, failure.getCause()),
                () -> assertEquals(
                        List.of(Desktop.Action.BROWSE_FILE_DIR, Desktop.Action.OPEN),
                        desktop.supportQueries),
                () -> assertEquals(List.of(), desktop.browsedTargets),
                () -> assertEquals(List.of(), desktop.openedDirectories));
    }

    /// Desktop failures complete the returned stage with the original exception object.
    @Test
    public void revealPreservesDesktopFailureIdentity() {
        IOException expected = new IOException("browse failed");
        FakeDesktopActions desktop = new FakeDesktopActions();
        desktop.browseSupported = true;
        desktop.browseFailure = expected;
        DefaultSchematicBrowserInteractions interactions = interactions(
                Runnable::run, new FakeDialogActions(), desktop);

        CompletionStage<@Nullable Void> completion = interactions.reveal(new SchematicDirectoryItem(
                Path.of("schematics", "directory"), "directory"));

        assertFailureIdentity(completion, expected);
        assertEquals(List.of(), desktop.openedDirectories);
    }

    /// Executor rejection is returned as a failed stage and retains the original rejection object.
    @Test
    public void revealPreservesExecutorRejectionIdentity() {
        RejectedExecutionException expected = new RejectedExecutionException("rejected");
        FakeDesktopActions desktop = new FakeDesktopActions();
        Executor rejectingExecutor = command -> {
            throw expected;
        };
        DefaultSchematicBrowserInteractions interactions = interactions(
                rejectingExecutor, new FakeDialogActions(), desktop);

        CompletionStage<@Nullable Void> completion = interactions.reveal(new SchematicDirectoryItem(
                Path.of("schematics", "directory"), "directory"));

        assertAll(
                () -> assertFailureIdentity(completion, expected),
                () -> assertEquals(List.of(), desktop.supportQueries));
    }

    /// Creates interactions with the shared presentation and injected test boundaries.
    ///
    /// @param executor caller-owned executor
    /// @param dialogs dialog fake
    /// @param desktop desktop fake
    /// @return test interactions
    private static DefaultSchematicBrowserInteractions interactions(
            Executor executor,
            FakeDialogActions dialogs,
            FakeDesktopActions desktop) {
        return new DefaultSchematicBrowserInteractions(STRINGS, executor, dialogs, desktop);
    }

    /// Creates a desktop fake supporting only the open fallback.
    ///
    /// @return open-only desktop fake
    private static FakeDesktopActions openOnlyDesktop() {
        FakeDesktopActions desktop = new FakeDesktopActions();
        desktop.openSupported = true;
        return desktop;
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

    /// One captured input dialog invocation.
    ///
    /// @param owner dialog owner
    /// @param message prompt content
    /// @param title dialog title
    /// @param messageType dialog message type
    /// @param onEventDispatchThread whether invocation occurred on the EDT
    @NotNullByDefault
    private record InputDialogCall(
            Component owner,
            Object message,
            String title,
            int messageType,
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

    /// Dialog fake that captures configuration and simulates chooser or prompt results.
    @NotNullByDefault
    private static final class FakeDialogActions implements DialogActions {
        /// Result returned by the next chooser call.
        private int openResult = JFileChooser.CANCEL_OPTION;

        /// Files installed into the chooser before an approved result.
        private @Unmodifiable List<Path> selectedFiles = List.of();

        /// Result returned by the next input dialog call.
        private @Nullable String inputResult;

        /// Result returned by the next confirmation call.
        private int confirmResult = JOptionPane.NO_OPTION;

        /// Most recent chooser invocation.
        private @Nullable OpenDialogCall openCall;

        /// Most recent input invocation.
        private @Nullable InputDialogCall inputCall;

        /// Most recent confirmation invocation.
        private @Nullable ConfirmDialogCall confirmCall;

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

        /// Captures and returns the configured input result.
        @Override
        public @Nullable String showInputDialog(
                Component owner,
                Object message,
                String title,
                int messageType) {
            inputCall = new InputDialogCall(
                    owner, message, title, messageType, SwingUtilities.isEventDispatchThread());
            return inputResult;
        }

        /// Captures and returns the configured confirmation result.
        @Override
        public int showConfirmDialog(
                Component owner,
                Object message,
                String title,
                int optionType,
                int messageType) {
            confirmCall = new ConfirmDialogCall(
                    owner,
                    message,
                    title,
                    optionType,
                    messageType,
                    SwingUtilities.isEventDispatchThread());
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
            int calls = 0;
            calls += openCall == null ? 0 : 1;
            calls += inputCall == null ? 0 : 1;
            calls += confirmCall == null ? 0 : 1;
            calls += messageCall == null ? 0 : 1;
            return calls;
        }
    }

    /// Desktop fake with independently configurable action support and failures.
    @NotNullByDefault
    private static final class FakeDesktopActions implements DesktopActions {
        /// Whether dedicated reveal is supported.
        private boolean browseSupported;

        /// Whether the open fallback is supported.
        private boolean openSupported;

        /// Optional dedicated-reveal failure.
        private @Nullable IOException browseFailure;

        /// Optional open failure.
        private @Nullable IOException openFailure;

        /// Action support queries in invocation order.
        private final List<Desktop.Action> supportQueries = new ArrayList<>();

        /// Dedicated reveal targets in invocation order.
        private final List<Path> browsedTargets = new ArrayList<>();

        /// Open fallback directories in invocation order.
        private final List<Path> openedDirectories = new ArrayList<>();

        /// Creates an unsupported desktop fake.
        private FakeDesktopActions() {
        }

        /// Captures and answers one support query.
        @Override
        public boolean isSupported(Desktop.Action action) {
            supportQueries.add(action);
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
            browsedTargets.add(target);
            if (browseFailure != null) {
                throw browseFailure;
            }
        }

        /// Captures or fails one fallback open call.
        @Override
        public void open(Path directory) throws IOException {
            openedDirectories.add(directory);
            if (openFailure != null) {
                throw openFailure;
            }
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
