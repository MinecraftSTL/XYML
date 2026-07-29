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
package space.minecraftstl.xyml.ui.swing.log;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.game.Log;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;
import space.minecraftstl.xyml.util.CircularArrayList;
import space.minecraftstl.xyml.util.platform.ManagedProcess;

import javax.swing.JFrame;
import javax.swing.WindowConstants;
import java.awt.Dimension;
import java.awt.GraphicsEnvironment;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.lang.ref.WeakReference;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntConsumer;

import static space.minecraftstl.xyml.util.i18n.I18n.i18n;

/// Owns a native Swing game-log frame and accepts log updates safely from arbitrary process threads.
///
/// Input batches are copied before queuing to the EDT, so the launch listener can immediately reuse its mutable batch.
/// Closing the frame releases native components and the export executor while continuing to retain later log entries in
/// the shared bounded history for crash diagnostics.
@NotNullByDefault
public final class SwingGameLogWindow implements AutoCloseable {
    /// Minimum frame size that keeps toolbars and the log viewport usable.
    private static final Dimension MINIMUM_SIZE = new Dimension(620, 360);

    /// Sequence used to name daemon export threads.
    private static final AtomicInteger WORKER_SEQUENCE = new AtomicInteger();

    /// Bounded shared history retained independently of the visible frame.
    private final BoundedGameLogBuffer buffer;

    /// Process and desktop actions for this game process.
    private final GameLogWindowActions actions;

    /// Single daemon executor serializing log and dump export operations.
    private final ExecutorService exportExecutor;

    /// Persists line-limit changes outside this window.
    private final IntConsumer maxLinesChanged;

    /// Prevents frame recreation and repeated resource shutdown after close.
    private final AtomicBoolean closed = new AtomicBoolean();

    /// Lazily created visible frame, accessed only on the EDT.
    private @Nullable JFrame frame;

    /// Lazily created log panel, accessed only on the EDT.
    private @Nullable SwingGameLogPanel panel;

    /// Creates a window using the current launcher log limit without a persistence callback.
    ///
    /// This constructor accepts the process-and-history call shape. Integrations that persist a changed
    /// line limit should use {@link #SwingGameLogWindow(ManagedProcess, CircularArrayList, int, IntConsumer)}.
    ///
    /// @param process managed game process
    /// @param logs shared mutable bounded history used by crash diagnostics
    public SwingGameLogWindow(ManagedProcess process, CircularArrayList<Log> logs) {
        this(process, logs, Log.getLogLines(), ignored -> {
        });
    }

    /// Creates a window with an explicit initial retention limit and persistence callback.
    ///
    /// @param process managed game process
    /// @param logs shared mutable bounded history used by crash diagnostics
    /// @param maxLines positive initial retention limit
    /// @param maxLinesChanged callback invoked on the EDT when the user selects another limit
    public SwingGameLogWindow(
            ManagedProcess process,
            CircularArrayList<Log> logs,
            int maxLines,
            IntConsumer maxLinesChanged) {
        this(
                new BoundedGameLogBuffer(logs, maxLines),
                new ManagedProcessGameLogWindowActions(process),
                newExportExecutor(),
                maxLinesChanged);
    }

    /// Creates a window from testable model, side-effect, and executor boundaries.
    ///
    /// @param buffer bounded shared history
    /// @param actions process and desktop actions
    /// @param exportExecutor executor for blocking exports
    /// @param maxLinesChanged persistence callback for line-limit changes
    SwingGameLogWindow(
            BoundedGameLogBuffer buffer,
            GameLogWindowActions actions,
            ExecutorService exportExecutor,
            IntConsumer maxLinesChanged) {
        this.buffer = Objects.requireNonNull(buffer, "buffer");
        this.actions = Objects.requireNonNull(actions, "actions");
        this.exportExecutor = Objects.requireNonNull(exportExecutor, "exportExecutor");
        this.maxLinesChanged = Objects.requireNonNull(maxLinesChanged, "maxLinesChanged");

        WeakReference<SwingGameLogWindow> windowReference = new WeakReference<>(this);
        actions.registerProcessExit(() -> notifyProcessExit(windowReference));
    }

    /// Opens or raises the non-modal log frame on the EDT.
    ///
    /// Headless environments retain and bound incoming logs without attempting to construct a native frame.
    public void show() {
        EdtDispatcher.execute(this::showOnEdt);
    }

    /// Queues one process log entry on the EDT.
    ///
    /// @param log entry to retain and display
    public void logLine(Log log) {
        Log copiedReference = Objects.requireNonNull(log, "log");
        EdtDispatcher.execute(() -> appendOnEdt(List.of(copiedReference)));
    }

    /// Copies and queues one process log batch on the EDT.
    ///
    /// @param logs process log entries in source order
    public void logLines(List<Log> logs) {
        @Unmodifiable List<Log> copiedLogs = List.copyOf(Objects.requireNonNull(logs, "logs"));
        EdtDispatcher.execute(() -> appendOnEdt(copiedLogs));
    }

    /// Closes the visible frame and releases its export executor and component listeners.
    ///
    /// Later log updates remain bounded in the shared history but do not recreate the closed frame.
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        exportExecutor.shutdownNow();
        EdtDispatcher.execute(this::disposeOnEdt);
    }

    /// Reports whether close has permanently disabled frame recreation.
    ///
    /// @return true after the first close request
    boolean isClosed() {
        return closed.get();
    }

    /// Returns the retained log count for deterministic lifecycle tests.
    ///
    /// @return bounded shared history size
    int retainedLogCount() {
        EdtDispatcher.requireEventDispatchThread();
        return buffer.size();
    }

    /// Creates or raises the native frame on the EDT.
    private void showOnEdt() {
        EdtDispatcher.requireEventDispatchThread();
        if (closed.get() || GraphicsEnvironment.isHeadless()) {
            return;
        }
        if (frame == null) {
            JFrame createdFrame = new JFrame(i18n("logwindow.title"));
            createdFrame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
            createdFrame.setMinimumSize(new Dimension(MINIMUM_SIZE));
            createdFrame.setContentPane(panelOnEdt());
            createdFrame.pack();
            createdFrame.setSize(800, 480);
            createdFrame.setLocationByPlatform(true);
            createdFrame.addWindowListener(new CloseWindowListener(this::close));
            frame = createdFrame;
        }
        JFrame currentFrame = Objects.requireNonNull(frame, "frame");
        currentFrame.setVisible(true);
        currentFrame.toFront();
    }

    /// Appends a copied batch to the visible panel or directly to the retained history after close.
    ///
    /// @param logs immutable copied batch
    private void appendOnEdt(@Unmodifiable List<Log> logs) {
        EdtDispatcher.requireEventDispatchThread();
        @Nullable SwingGameLogPanel currentPanel = panel;
        if (currentPanel != null && !closed.get()) {
            currentPanel.appendAll(logs);
        } else {
            buffer.appendAll(logs, SwingGameLogWindow::ignoreVisibleLog);
        }
    }

    /// Lazily creates the panel on the EDT.
    ///
    /// @return live panel owned by this window
    private SwingGameLogPanel panelOnEdt() {
        EdtDispatcher.requireEventDispatchThread();
        @Nullable SwingGameLogPanel currentPanel = panel;
        if (currentPanel == null) {
            currentPanel = new SwingGameLogPanel(
                    buffer,
                    actions,
                    exportExecutor,
                    maxLinesChanged,
                    this::setAlwaysOnTopOnEdt);
            panel = currentPanel;
        }
        return currentPanel;
    }

    /// Applies the panel's always-on-top selection to the current frame.
    ///
    /// @param alwaysOnTop whether the frame should remain above other windows
    private void setAlwaysOnTopOnEdt(boolean alwaysOnTop) {
        EdtDispatcher.requireEventDispatchThread();
        @Nullable JFrame currentFrame = frame;
        if (currentFrame != null) {
            currentFrame.setAlwaysOnTop(alwaysOnTop);
        }
    }

    /// Disposes the panel and native frame on the EDT.
    private void disposeOnEdt() {
        EdtDispatcher.requireEventDispatchThread();
        @Nullable SwingGameLogPanel currentPanel = panel;
        panel = null;
        if (currentPanel != null) {
            currentPanel.disposePanel();
        }
        @Nullable JFrame currentFrame = frame;
        frame = null;
        if (currentFrame != null) {
            currentFrame.dispose();
        }
    }

    /// Disables process controls after termination without retaining the window from the process exit stage.
    ///
    /// @param windowReference weak reference captured by the process callback
    private static void notifyProcessExit(WeakReference<SwingGameLogWindow> windowReference) {
        @Nullable SwingGameLogWindow window = windowReference.get();
        if (window != null) {
            EdtDispatcher.execute(window::processExitedOnEdt);
        }
    }

    /// Disables process controls if the panel still exists.
    private void processExitedOnEdt() {
        EdtDispatcher.requireEventDispatchThread();
        @Nullable SwingGameLogPanel currentPanel = panel;
        if (currentPanel != null && !closed.get()) {
            currentPanel.processExited();
        }
    }

    /// Accepts visible append callbacks after the frame has closed while intentionally doing no UI work.
    ///
    /// @param ignored retained visible entry
    private static void ignoreVisibleLog(Log ignored) {
    }

    /// Creates one daemon executor dedicated to serialized export operations.
    ///
    /// @return new single-thread export executor
    private static ExecutorService newExportExecutor() {
        ThreadFactory threadFactory = runnable -> {
            Thread thread = new Thread(
                    runnable,
                    "Game Log Exporter-" + WORKER_SEQUENCE.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        return Executors.newSingleThreadExecutor(threadFactory);
    }

    /// Adapts a native frame-close event to the window's idempotent resource shutdown.
    @NotNullByDefault
    private static final class CloseWindowListener extends WindowAdapter {
        /// Idempotent close action for the owning log window.
        private final Runnable closeAction;

        /// Creates a frame-close adapter.
        ///
        /// @param closeAction idempotent owning-window close action
        private CloseWindowListener(Runnable closeAction) {
            this.closeAction = Objects.requireNonNull(closeAction, "closeAction");
        }

        /// Releases window-owned resources when the user closes the native frame.
        ///
        /// @param event native closing event
        @Override
        public void windowClosing(WindowEvent event) {
            closeAction.run();
        }
    }
}
