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
package space.minecraftstl.xyml.ui.swing.crash;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.Metadata;
import space.minecraftstl.xyml.game.DefaultGameRepository;
import space.minecraftstl.xyml.game.LaunchOptions;
import space.minecraftstl.xyml.game.Log;
import space.minecraftstl.xyml.game.Version;
import space.minecraftstl.xyml.launch.ProcessListener;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;
import space.minecraftstl.xyml.util.StringUtils;
import space.minecraftstl.xyml.util.platform.ManagedProcess;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JEditorPane;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingConstants;
import javax.swing.WindowConstants;
import javax.swing.event.HyperlinkEvent;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static space.minecraftstl.xyml.util.i18n.I18n.i18n;
import static space.minecraftstl.xyml.util.logging.Logger.LOG;

/// Owns a responsive native Swing game-crash diagnosis window and its asynchronous work.
///
/// The public `open` method is the intended `LauncherHelper` wiring surface. It accepts a `Runnable` that opens the
/// existing `SwingGameLogWindow`, so this package does not need to own or modify the process-log window.
@NotNullByDefault
public final class SwingGameCrashWindow implements AutoCloseable {
    /// Initial frame size for the game-crash surface.
    private static final Dimension INITIAL_SIZE = new Dimension(800, 480);

    /// Minimum size that keeps both diagnostic columns and actions usable.
    private static final Dimension MINIMUM_SIZE = new Dimension(640, 400);

    /// Sequence used to name daemon analysis and export threads.
    private static final AtomicInteger WORKER_SEQUENCE = new AtomicInteger();

    /// Immutable display and analysis inputs.
    private final GameCrashWindowModel model;

    /// Asynchronous captured-log and latest-log analyzer.
    private final GameCrashAnalysisService analysisService;

    /// Localizes merged analyzer results.
    private final GameCrashReasonFormatter reasonFormatter;

    /// Export, desktop, and log-window side effects.
    private final GameCrashWindowActions actions;

    /// Window-owned daemon executor cancelled during close.
    private final ExecutorService worker;

    /// Whether this instance may create a native frame after composing its testable content.
    private final boolean nativePresentationEnabled;

    /// Prevents frame recreation and all late asynchronous UI updates after close.
    private final AtomicBoolean closed = new AtomicBoolean();

    /// Ensures analysis starts only once even when repeated calls raise the same window.
    private final AtomicBoolean analysisStarted = new AtomicBoolean();

    /// Visible native frame, accessed only on the EDT.
    private @Nullable JFrame frame;

    /// Lazily created content root, accessed only on the EDT.
    private @Nullable JPanel content;

    /// Selectable HTML diagnosis view, accessed only on the EDT.
    private @Nullable JEditorPane reasonPane;

    /// Analysis progress indicator, accessed only on the EDT.
    private @Nullable JProgressBar analysisProgress;

    /// Short operation status displayed beside the action buttons, accessed only on the EDT.
    private @Nullable JLabel operationStatus;

    /// Export action disabled while a bundle is being produced, accessed only on the EDT.
    private @Nullable JButton exportButton;

    /// Current analysis stage retained for best-effort cancellation.
    private @Nullable CompletableFuture<GameCrashAnalysis> analysisFuture;

    /// Current export stage retained for best-effort cancellation.
    private @Nullable CompletableFuture<Path> exportFuture;

    /// Last reason assigned on the EDT, retained independently of native components for headless tests.
    private String displayedReason = i18n("game.crash.reason.analyzing");

    /// Creates, opens, and returns one production game-crash window.
    ///
    /// This is the minimal launcher integration API. The log-window action may simply call `show()` on the already
    /// completed `SwingGameLogWindow` instance associated with the same managed process.
    ///
    /// @param process completed managed game process
    /// @param exitType classified abnormal exit type
    /// @param repository repository owning the launched instance
    /// @param version launched version
    /// @param launchOptions resolved launch configuration
    /// @param logs current captured process-output history, copied before asynchronous work starts
    /// @param showGameLogs action opening or raising the Swing game-log window
    /// @return closeable crash-window handle
    public static SwingGameCrashWindow open(
            ManagedProcess process,
            ProcessListener.ExitType exitType,
            DefaultGameRepository repository,
            Version version,
            LaunchOptions launchOptions,
            List<Log> logs,
            Runnable showGameLogs) {
        @Unmodifiable List<Log> copiedLogs = List.copyOf(Objects.requireNonNull(logs, "logs"));
        GameCrashWindowModel model = GameCrashWindowModel.fromLaunch(
                exitType,
                repository,
                version,
                launchOptions,
                copiedLogs);
        ExecutorService worker = newWorker();
        GameCrashWindowActions actions = new DefaultGameCrashWindowActions(
                process,
                repository,
                version,
                launchOptions,
                copiedLogs,
                showGameLogs,
                worker);
        SwingGameCrashWindow window = new SwingGameCrashWindow(
                model,
                new DefaultGameCrashAnalysisService(worker),
                new GameCrashReasonFormatter(),
                actions,
                worker,
                true);
        window.show();
        return window;
    }

    /// Creates a window from testable immutable, service, side-effect, and executor boundaries.
    ///
    /// @param model immutable display and analysis inputs
    /// @param analysisService asynchronous diagnosis service
    /// @param reasonFormatter analyzer-result localizer
    /// @param actions export and desktop side effects
    /// @param worker window-owned executor
    /// @param nativePresentationEnabled whether this instance may create a native frame
    SwingGameCrashWindow(
            GameCrashWindowModel model,
            GameCrashAnalysisService analysisService,
            GameCrashReasonFormatter reasonFormatter,
            GameCrashWindowActions actions,
            ExecutorService worker,
            boolean nativePresentationEnabled) {
        this.model = Objects.requireNonNull(model, "model");
        this.analysisService = Objects.requireNonNull(analysisService, "analysisService");
        this.reasonFormatter = Objects.requireNonNull(reasonFormatter, "reasonFormatter");
        this.actions = Objects.requireNonNull(actions, "actions");
        this.worker = Objects.requireNonNull(worker, "worker");
        this.nativePresentationEnabled = nativePresentationEnabled;
    }

    /// Opens or raises this non-modal crash window on the EDT.
    ///
    /// Headless environments still build the content and start diagnosis for deterministic validation without a frame.
    public void show() {
        EdtDispatcher.execute(this::showOnEdt);
    }

    /// Cancels pending work, prevents late UI updates, and releases native components.
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        @Nullable CompletableFuture<GameCrashAnalysis> currentAnalysis = analysisFuture;
        if (currentAnalysis != null) {
            currentAnalysis.cancel(true);
        }
        @Nullable CompletableFuture<Path> currentExport = exportFuture;
        if (currentExport != null) {
            currentExport.cancel(true);
        }
        worker.shutdownNow();
        EdtDispatcher.execute(this::disposeOnEdt);
    }

    /// Reports whether this window has permanently closed.
    ///
    /// @return true after the first close request
    boolean isClosed() {
        return closed.get();
    }

    /// Returns the last reason applied on the EDT for deterministic headless tests.
    ///
    /// @return displayed localized reason text
    String displayedReasonOnEdt() {
        EdtDispatcher.requireEventDispatchThread();
        return displayedReason;
    }

    /// Reports whether content was created without requiring a native frame.
    ///
    /// @return true after the first EDT presentation pass
    boolean hasContentOnEdt() {
        EdtDispatcher.requireEventDispatchThread();
        return content != null;
    }

    /// Creates or raises the native frame and starts diagnosis once.
    private void showOnEdt() {
        EdtDispatcher.requireEventDispatchThread();
        if (closed.get()) {
            return;
        }
        JPanel root = contentOnEdt();
        startAnalysisOnEdt();
        if (!nativePresentationEnabled || GraphicsEnvironment.isHeadless()) {
            return;
        }

        if (frame == null) {
            JFrame createdFrame = new JFrame(i18n("game.crash.title"));
            createdFrame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
            createdFrame.setContentPane(root);
            createdFrame.setMinimumSize(MINIMUM_SIZE);
            createdFrame.setSize(INITIAL_SIZE);
            createdFrame.setLocationByPlatform(true);
            createdFrame.addWindowListener(new CloseWindowListener(this::close));
            frame = createdFrame;
        }
        JFrame currentFrame = Objects.requireNonNull(frame, "frame");
        currentFrame.setVisible(true);
        currentFrame.toFront();
    }

    /// Lazily composes the header, selectable environment details, diagnosis viewport, and actions.
    ///
    /// @return live content root
    private JPanel contentOnEdt() {
        EdtDispatcher.requireEventDispatchThread();
        @Nullable JPanel currentContent = content;
        if (currentContent != null) {
            return currentContent;
        }

        JPanel root = new JPanel(new BorderLayout(0, 12));
        root.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        root.add(createHeaderOnEdt(), BorderLayout.NORTH);

        JSplitPane workspace = new JSplitPane(
                JSplitPane.HORIZONTAL_SPLIT,
                createInformationPaneOnEdt(),
                createReasonPaneOnEdt());
        workspace.setBorder(BorderFactory.createEmptyBorder());
        workspace.setContinuousLayout(true);
        workspace.setOneTouchExpandable(true);
        workspace.setResizeWeight(0.34);
        workspace.setDividerLocation(270);
        root.add(workspace, BorderLayout.CENTER);
        root.add(createActionsOnEdt(), BorderLayout.SOUTH);

        content = root;
        return root;
    }

    /// Creates the exit-type-specific headline.
    ///
    /// @return header component
    private Component createHeaderOnEdt() {
        EdtDispatcher.requireEventDispatchThread();
        JLabel headline = new JLabel(titleFor(model.exitType()));
        headline.setFont(headline.getFont().deriveFont(Font.BOLD, headline.getFont().getSize2D() + 2.0F));
        headline.setBorder(BorderFactory.createEmptyBorder(2, 2, 2, 2));
        return headline;
    }

    /// Creates a selectable, wrapped environment-information viewport.
    ///
    /// @return information section
    private Component createInformationPaneOnEdt() {
        EdtDispatcher.requireEventDispatchThread();
        JTextArea information = new JTextArea(environmentText());
        information.setEditable(false);
        information.setLineWrap(true);
        information.setWrapStyleWord(true);
        information.setCaretPosition(0);
        information.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        JScrollPane scroll = new JScrollPane(information);
        scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(BorderFactory.createTitledBorder(i18n("game.crash.info")));
        scroll.setMinimumSize(new Dimension(180, 0));
        return scroll;
    }

    /// Creates the selectable HTML diagnosis viewport and its progress indicator.
    ///
    /// @return diagnosis section
    private Component createReasonPaneOnEdt() {
        EdtDispatcher.requireEventDispatchThread();
        JEditorPane reason = new JEditorPane();
        reason.setContentType("text/html");
        reason.setEditable(false);
        reason.setOpaque(false);
        reason.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, true);
        reason.addHyperlinkListener(event -> {
            if (event.getEventType() == HyperlinkEvent.EventType.ACTIVATED) {
                @Nullable String destination = event.getURL() == null
                        ? event.getDescription()
                        : event.getURL().toExternalForm();
                if (destination != null) {
                    openLink(URI.create(destination));
                }
            }
        });
        reason.setText(htmlDocument(i18n("game.crash.feedback"), displayedReason));
        reason.setCaretPosition(0);
        reasonPane = reason;

        JScrollPane scroll = new JScrollPane(reason);
        scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(BorderFactory.createEmptyBorder());

        JProgressBar progress = new JProgressBar();
        progress.setIndeterminate(true);
        progress.setStringPainted(true);
        progress.setString(i18n("game.crash.reason.analyzing"));
        analysisProgress = progress;

        JPanel section = new JPanel(new BorderLayout(0, 8));
        section.setBorder(BorderFactory.createTitledBorder(i18n("game.crash.reason")));
        section.add(progress, BorderLayout.NORTH);
        section.add(scroll, BorderLayout.CENTER);
        section.setMinimumSize(new Dimension(260, 0));
        return section;
    }

    /// Creates the non-shifting action row and operation status.
    ///
    /// @return action toolbar
    private Component createActionsOnEdt() {
        EdtDispatcher.requireEventDispatchThread();
        JButton export = new JButton(i18n("logwindow.export_game_crash_logs"));
        export.addActionListener(event -> exportCrashLogsOnEdt());
        exportButton = export;

        JButton logs = new JButton(i18n("logwindow.title"));
        logs.addActionListener(event -> showGameLogsOnEdt());

        JButton help = new JButton(i18n("help"));
        help.setToolTipText(i18n("logwindow.help"));
        help.addActionListener(event -> openLink(URI.create(Metadata.CONTACT_URL)));

        JLabel status = new JLabel(" ", SwingConstants.LEADING);
        operationStatus = status;

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.TRAILING, 8, 0));
        buttons.add(export);
        buttons.add(logs);
        buttons.add(help);

        JPanel toolbar = new JPanel();
        toolbar.setLayout(new BoxLayout(toolbar, BoxLayout.X_AXIS));
        toolbar.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, dividerColor()));
        toolbar.add(status);
        toolbar.add(Box.createHorizontalGlue());
        toolbar.add(buttons);
        return toolbar;
    }

    /// Starts asynchronous diagnosis and marshals its terminal result back to the EDT.
    private void startAnalysisOnEdt() {
        EdtDispatcher.requireEventDispatchThread();
        if (!analysisStarted.compareAndSet(false, true)) {
            return;
        }
        CompletionStage<GameCrashAnalysis> stage;
        try {
            stage = analysisService.analyze(model.capturedLogs(), model.latestLog());
        } catch (RuntimeException failure) {
            applyAnalysisOnEdt(null, failure);
            return;
        }
        CompletableFuture<GameCrashAnalysis> future = stage.toCompletableFuture();
        analysisFuture = future;
        future.whenComplete((@Nullable GameCrashAnalysis result, @Nullable Throwable failure) ->
                EdtDispatcher.execute(() -> applyAnalysisOnEdt(result, failure)));
    }

    /// Applies a successful diagnosis or the localized unknown fallback unless the window already closed.
    ///
    /// @param result merged diagnosis, or null after failure
    /// @param failure analysis failure, or null after success
    private void applyAnalysisOnEdt(
            @Nullable GameCrashAnalysis result,
            @Nullable Throwable failure) {
        EdtDispatcher.requireEventDispatchThread();
        if (closed.get()) {
            return;
        }
        @Nullable JProgressBar progress = analysisProgress;
        if (progress != null) {
            progress.setIndeterminate(false);
            progress.setVisible(false);
        }

        if (failure != null || result == null) {
            LOG.warning("Failed to analyze crash report", unwrapFailure(failure));
            displayedReason = reasonFormatter.format(new GameCrashAnalysis(List.of(), Set.of()));
        } else {
            displayedReason = reasonFormatter.format(result);
        }
        @Nullable JEditorPane reason = reasonPane;
        if (reason != null) {
            reason.setText(htmlDocument(i18n("game.crash.feedback"), displayedReason));
            reason.setCaretPosition(0);
        }
    }

    /// Starts one asynchronous crash-bundle export and disables duplicate requests until completion.
    private void exportCrashLogsOnEdt() {
        EdtDispatcher.requireEventDispatchThread();
        if (closed.get()) {
            return;
        }
        JButton export = Objects.requireNonNull(exportButton, "export button");
        export.setEnabled(false);
        setOperationStatusOnEdt(i18n("logwindow.export_game_crash_logs") + "...");

        CompletableFuture<Path> future;
        try {
            future = actions.exportCrashLogs()
                    .thenApplyAsync(path -> {
                        try {
                            actions.revealFile(path);
                            return path;
                        } catch (Exception exception) {
                            throw new CompletionException(exception);
                        }
                    }, worker)
                    .toCompletableFuture();
        } catch (RuntimeException failure) {
            finishExportOnEdt(null, failure);
            return;
        }
        exportFuture = future;
        future.whenComplete((@Nullable Path result, @Nullable Throwable failure) ->
                EdtDispatcher.execute(() -> finishExportOnEdt(result, failure)));
    }

    /// Restores the export action and reports the terminal result unless the window already closed.
    ///
    /// @param result exported path, or null after failure
    /// @param failure export or reveal failure, or null after success
    private void finishExportOnEdt(@Nullable Path result, @Nullable Throwable failure) {
        EdtDispatcher.requireEventDispatchThread();
        if (closed.get()) {
            return;
        }
        JButton export = Objects.requireNonNull(exportButton, "export button");
        export.setEnabled(true);
        if (failure == null && result != null) {
            String message = i18n("settings.launcher.launcher_log.export.success", result);
            setOperationStatusOnEdt(message);
            showMessageOnEdt(message, i18n("message.success"), JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        Throwable exportFailure = unwrapFailure(failure);
        LOG.warning("Failed to export game crash info", exportFailure);
        String message = i18n("settings.launcher.launcher_log.export.failed");
        setOperationStatusOnEdt(message);
        showMessageOnEdt(
                message + "\n" + StringUtils.getStackTrace(exportFailure),
                i18n("message.error"),
                JOptionPane.ERROR_MESSAGE);
    }

    /// Opens one link away from the EDT and reports failures back on the EDT.
    ///
    /// @param destination trusted help or localized-reason destination
    private void openLink(URI destination) {
        if (closed.get()) {
            return;
        }
        try {
            CompletableFuture.runAsync(() -> {
                try {
                    actions.openLink(destination);
                } catch (Exception exception) {
                    throw new CompletionException(exception);
                }
            }, worker).whenComplete((@Nullable Void ignored, @Nullable Throwable failure) -> {
                if (failure != null) {
                    EdtDispatcher.execute(() -> showLinkFailureOnEdt(destination, failure));
                }
            });
        } catch (RuntimeException failure) {
            EdtDispatcher.execute(() -> showLinkFailureOnEdt(destination, failure));
        }
    }

    /// Opens or raises the injected Swing game-log window while keeping failures on this window's EDT boundary.
    private void showGameLogsOnEdt() {
        EdtDispatcher.requireEventDispatchThread();
        try {
            actions.showGameLogs();
        } catch (RuntimeException failure) {
            LOG.warning("Failed to open game logs", failure);
            showMessageOnEdt(
                    StringUtils.getStackTrace(failure),
                    i18n("message.error"),
                    JOptionPane.ERROR_MESSAGE);
        }
    }

    /// Shows a link failure with the destination still available for manual copying.
    ///
    /// @param destination link that could not be opened
    /// @param failure desktop integration failure
    private void showLinkFailureOnEdt(URI destination, Throwable failure) {
        EdtDispatcher.requireEventDispatchThread();
        if (closed.get()) {
            return;
        }
        LOG.warning("Failed to open link " + destination, unwrapFailure(failure));
        showMessageOnEdt(destination.toString(), i18n("message.error"), JOptionPane.ERROR_MESSAGE);
    }

    /// Updates the compact operation status label without affecting toolbar geometry.
    ///
    /// @param text localized status text
    private void setOperationStatusOnEdt(String text) {
        EdtDispatcher.requireEventDispatchThread();
        @Nullable JLabel status = operationStatus;
        if (status != null) {
            status.setText(text);
        }
    }

    /// Presents a native message dialog unless the runtime is headless or the window has closed.
    ///
    /// @param message dialog body
    /// @param title dialog title
    /// @param messageType Swing message type constant
    private void showMessageOnEdt(String message, String title, int messageType) {
        EdtDispatcher.requireEventDispatchThread();
        if (closed.get() || GraphicsEnvironment.isHeadless()) {
            return;
        }
        JOptionPane.showMessageDialog(content, message, title, messageType);
    }

    /// Disposes all native and lazily created components on the EDT.
    private void disposeOnEdt() {
        EdtDispatcher.requireEventDispatchThread();
        @Nullable JFrame currentFrame = frame;
        frame = null;
        if (currentFrame != null) {
            currentFrame.dispose();
        }
        content = null;
        reasonPane = null;
        analysisProgress = null;
        operationStatus = null;
        exportButton = null;
    }

    /// Formats ordered environment details as selectable wrapped plain text.
    ///
    /// @return environment text with one blank line between fields
    private String environmentText() {
        StringBuilder text = new StringBuilder();
        for (GameCrashWindowModel.Detail detail : model.details()) {
            if (!text.isEmpty()) {
                text.append("\n\n");
            }
            text.append(detail.label()).append("\n").append(detail.value());
        }
        return text.toString();
    }

    /// Chooses the localized headline for every process-exit classification.
    ///
    /// @param exitType classified process-exit outcome
    /// @return localized crash headline
    static String titleFor(ProcessListener.ExitType exitType) {
        return switch (exitType) {
            case JVM_ERROR -> i18n("launch.failed.cannot_create_jvm");
            case APPLICATION_ERROR -> i18n("launch.failed.exited_abnormally");
            case SIGKILL -> i18n("launch.failed.sigkill");
            case NORMAL, INTERRUPTED -> i18n("game.crash.title");
        };
    }

    /// Wraps trusted localized markup in a display-properties-aware HTML document.
    ///
    /// @param feedback localized safety guidance that may contain HTML emphasis
    /// @param reason localized reason that may contain HTML links
    /// @return complete HTML document
    private static String htmlDocument(String feedback, String reason) {
        return "<html><body>"
                + newlinesToBreaks(feedback)
                + "<br><br>"
                + newlinesToBreaks(reason)
                + "</body></html>";
    }

    /// Converts platform line endings in trusted localized markup to HTML breaks.
    ///
    /// @param markup trusted localized markup
    /// @return markup with explicit HTML line breaks
    private static String newlinesToBreaks(String markup) {
        return markup.replace("\r\n", "\n").replace('\r', '\n').replace("\n", "<br>");
    }

    /// Resolves a completion wrapper to its actionable cause with a null-safe fallback.
    ///
    /// @param failure asynchronous failure, or null when a stage violated its result contract
    /// @return actionable failure
    private static Throwable unwrapFailure(@Nullable Throwable failure) {
        if ((failure instanceof CompletionException) && failure.getCause() != null) {
            return Objects.requireNonNull(failure.getCause(), "completion cause");
        }
        return failure == null ? new IllegalStateException("Asynchronous operation returned no result") : failure;
    }

    /// Returns a look-and-feel-aware divider color.
    ///
    /// @return non-null divider color
    private static Color dividerColor() {
        @Nullable Color color = javax.swing.UIManager.getColor("Separator.foreground");
        return color == null ? Color.GRAY : color;
    }

    /// Creates a two-thread daemon executor so both analysis sources can run concurrently.
    ///
    /// @return window-owned executor
    private static ExecutorService newWorker() {
        ThreadFactory threadFactory = runnable -> {
            Thread thread = new Thread(
                    runnable,
                    "Game Crash Analyzer-" + WORKER_SEQUENCE.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        return Executors.newFixedThreadPool(2, threadFactory);
    }

    /// Adapts the native frame-close event to idempotent window shutdown.
    @NotNullByDefault
    private static final class CloseWindowListener extends WindowAdapter {
        /// Idempotent owning-window close action.
        private final Runnable closeAction;

        /// Creates a native frame-close adapter.
        ///
        /// @param closeAction idempotent owning-window close action
        private CloseWindowListener(Runnable closeAction) {
            this.closeAction = Objects.requireNonNull(closeAction, "closeAction");
        }

        /// Releases window resources when the user closes its frame.
        ///
        /// @param event native closing event
        @Override
        public void windowClosing(WindowEvent event) {
            closeAction.run();
        }
    }
}
