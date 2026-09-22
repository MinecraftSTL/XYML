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
import space.minecraftstl.xyml.game.ExportedCrashBundle;
import space.minecraftstl.xyml.game.GameInstanceManifest;
import space.minecraftstl.xyml.game.LaunchOptions;
import space.minecraftstl.xyml.game.Log;
import space.minecraftstl.xyml.game.analyzer.AnalyzeResult;
import space.minecraftstl.xyml.game.analyzer.LogAnalyzable;
import space.minecraftstl.xyml.game.analyzer.RepairActionDescriptor;
import space.minecraftstl.xyml.game.analyzer.RepairTaskPhase;
import space.minecraftstl.xyml.game.analyzer.Solver;
import space.minecraftstl.xyml.launch.ProcessListener;
import space.minecraftstl.xyml.task.Task;
import space.minecraftstl.xyml.task.TaskExecutor;
import space.minecraftstl.xyml.task.TaskListener;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;
import space.minecraftstl.xyml.ui.swing.runtime.MissingDependencySearchAction;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

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

    /// Per-window confirmation and runtime-selection boundary.
    private final RepairInteraction repairInteraction;

    /// Prevents frame recreation and all late asynchronous UI updates after close.
    private final AtomicBoolean closed = new AtomicBoolean();

    /// Completes after analysis has no pending search, one search opens successfully, or the user closes the window.
    private final CompletableFuture<@Nullable Void> followUpCompletion = new CompletableFuture<>();

    /// Ensures analysis starts only once even when repeated calls raise the same window.
    private final AtomicBoolean analysisStarted = new AtomicBoolean();

    /// Visible native frame, accessed only on the EDT.
    private @Nullable JFrame frame;

    /// Lazily created content root, accessed only on the EDT.
    private @Nullable JPanel content;

    /// Selectable HTML diagnosis view, accessed only on the EDT.
    private @Nullable JEditorPane reasonPane;

    /// Ordered independent diagnosis rows, accessed only on the EDT.
    private @Nullable JPanel diagnosisRowsPanel;

    /// Mutable row state indexed by stable diagnosis identifier, accessed only on the EDT.
    private final Map<String, RepairRow> repairRows = new LinkedHashMap<>();

    /// Analysis progress indicator, accessed only on the EDT.
    private @Nullable JProgressBar analysisProgress;

    /// Trailing crash-report QR marker, accessed only on the EDT.
    private @Nullable CrashReportQrCodeMarker reportQrCodeMarker;

    /// Short operation status displayed beside the action buttons, accessed only on the EDT.
    private @Nullable JLabel operationStatus;

    /// Export action disabled while a bundle is being produced, accessed only on the EDT.
    private @Nullable JButton exportButton;

    /// Reveal action for the latest successfully exported report, accessed only on the EDT.
    private @Nullable JButton revealButton;

    /// Latest successfully exported report, accessed only on the EDT.
    private @Nullable Path exportedCrashReport;

    /// Current analysis stage retained for best-effort cancellation.
    private @Nullable CompletableFuture<GameCrashAnalysis> analysisFuture;

    /// Current export stage retained for best-effort cancellation.
    private @Nullable CompletableFuture<Path> exportFuture;

    /// Current file-manager reveal stage retained for best-effort cancellation.
    private @Nullable CompletableFuture<Void> revealFuture;

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
    /// @param manifest launched game-instance manifest
    /// @param launchOptions resolved launch configuration
    /// @param logs current captured process-output history, copied before asynchronous work starts
    /// @param showGameLogs action opening or raising the Swing game-log window
    /// @return closeable crash-window handle
    public static SwingGameCrashWindow open(
            ManagedProcess process,
            ProcessListener.ExitType exitType,
            DefaultGameRepository repository,
            GameInstanceManifest manifest,
            LaunchOptions launchOptions,
            List<Log> logs,
            Runnable showGameLogs) {
        return open(process, exitType, repository, manifest, launchOptions, logs, showGameLogs, null);
    }

    /// Creates, opens, and returns a read-only window for one validated exported crash bundle.
    ///
    /// @param owner component or window used to own the imported log viewer
    /// @param bundle validated crash-export contents
    /// @return closeable diagnostic-only crash-window handle
    public static SwingGameCrashWindow openImported(Component owner, ExportedCrashBundle bundle) {
        Objects.requireNonNull(owner, "owner");
        ExportedCrashBundle imported = Objects.requireNonNull(bundle, "bundle");
        ExecutorService worker = newWorker();
        DefaultGameCrashAnalysisService service = new DefaultGameCrashAnalysisService(worker);
        SwingGameCrashWindow window = new SwingGameCrashWindow(
                GameCrashWindowModel.fromImported(imported),
                (ignoredInput, ignoredLatestLog) -> service.analyze(imported),
                new GameCrashReasonFormatter(),
                new ImportedGameCrashWindowActions(owner, imported),
                worker,
                true);
        window.show();
        return window;
    }

    /// Creates, opens, and returns a production crash window with an optional missing-mod search action.
    ///
    /// @param process completed managed game process
    /// @param exitType classified abnormal exit type
    /// @param repository repository owning the launched instance
    /// @param manifest launched game-instance manifest
    /// @param launchOptions resolved launch configuration
    /// @param logs current captured process-output history, copied before asynchronous work starts
    /// @param showGameLogs action opening or raising the Swing game-log window
    /// @param openMissingModSearch action opening the Mods search page, or null when unavailable
    /// @return closeable crash-window handle
    public static SwingGameCrashWindow open(
            ManagedProcess process,
            ProcessListener.ExitType exitType,
            DefaultGameRepository repository,
            GameInstanceManifest manifest,
            LaunchOptions launchOptions,
            List<Log> logs,
            Runnable showGameLogs,
            @Nullable java.util.function.Consumer<String> openMissingModSearch) {
        @Nullable MissingDependencySearchAction searchAction = openMissingModSearch == null
                ? null
                : (dependencyId, ignoredGameVersion) -> openMissingModSearch.accept(dependencyId);
        return openWithMissingDependencySearch(
                process,
                exitType,
                repository,
                manifest,
                launchOptions,
                logs,
                showGameLogs,
                searchAction);
    }

    /// Creates, opens, and returns a production crash window with a version-aware dependency-search action.
    ///
    /// @param process completed managed game process
    /// @param exitType classified abnormal exit type
    /// @param repository repository owning the launched instance
    /// @param manifest launched game-instance manifest
    /// @param launchOptions resolved launch configuration
    /// @param logs current captured process-output history, copied before asynchronous work starts
    /// @param showGameLogs action opening or raising the Swing game-log window
    /// @param openMissingModSearch action opening a dependency search with its analyzed version, or null
    /// @return closeable crash-window handle
    public static SwingGameCrashWindow openWithMissingDependencySearch(
            ManagedProcess process,
            ProcessListener.ExitType exitType,
            DefaultGameRepository repository,
            GameInstanceManifest manifest,
            LaunchOptions launchOptions,
            List<Log> logs,
            Runnable showGameLogs,
            @Nullable MissingDependencySearchAction openMissingModSearch) {
        @Unmodifiable List<Log> copiedLogs = List.copyOf(Objects.requireNonNull(logs, "logs"));
        GameCrashWindowModel model = GameCrashWindowModel.fromLaunch(
                exitType,
                repository,
                manifest,
                launchOptions,
                copiedLogs,
                openMissingModSearch);
        ExecutorService worker = newWorker();
        GameCrashWindowActions actions = new DefaultGameCrashWindowActions(
                process,
                repository,
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
        this(
                model,
                analysisService,
                reasonFormatter,
                actions,
                worker,
                nativePresentationEnabled,
                NativeRepairInteraction.INSTANCE);
    }

    /// Creates a window with an explicit repair-interaction boundary for deterministic UI tests.
    ///
    /// @param model immutable display and analysis inputs
    /// @param analysisService asynchronous diagnosis service
    /// @param reasonFormatter analyzer-result localizer
    /// @param actions export and desktop side effects
    /// @param worker window-owned executor
    /// @param nativePresentationEnabled whether this instance may create a native frame
    /// @param repairInteraction confirmation and runtime-selection boundary
    SwingGameCrashWindow(
            GameCrashWindowModel model,
            GameCrashAnalysisService analysisService,
            GameCrashReasonFormatter reasonFormatter,
            GameCrashWindowActions actions,
            ExecutorService worker,
            boolean nativePresentationEnabled,
            RepairInteraction repairInteraction) {
        this.model = Objects.requireNonNull(model, "model");
        this.analysisService = Objects.requireNonNull(analysisService, "analysisService");
        this.reasonFormatter = Objects.requireNonNull(reasonFormatter, "reasonFormatter");
        this.actions = Objects.requireNonNull(actions, "actions");
        this.worker = Objects.requireNonNull(worker, "worker");
        this.nativePresentationEnabled = nativePresentationEnabled;
        this.repairInteraction = Objects.requireNonNull(repairInteraction, "repairInteraction");
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
        try {
            actions.close();
        } catch (RuntimeException closeFailure) {
            LOG.warning("Failed to close game crash window actions", closeFailure);
        }
        model.close();
        worker.shutdownNow();
        EdtDispatcher.execute(this::disposeOnEdt);
    }

    /// Returns the lifecycle boundary required by hidden-launcher missing-dependency follow-up.
    ///
    /// Analysis without an executable missing-dependency search completes immediately after rendering. When such a
    /// search is available, the launcher runtime remains alive until one search opens successfully or this crash window
    /// closes, so the user can decide whether and in which order to invoke the independent action.
    ///
    /// @return follow-up completion stage
    public CompletionStage<@Nullable Void> followUpCompletion() {
        return followUpCompletion;
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

    /// Reports whether the crash-report QR marker was composed in the page header.
    ///
    /// @return true after the page header has been created
    boolean hasReportQrCodeOnEdt() {
        EdtDispatcher.requireEventDispatchThread();
        return reportQrCodeMarker != null;
    }

    /// Clicks one independent repair action for deterministic headless tests.
    ///
    /// @param resultId stable diagnosis identifier
    void clickRepairOnEdt(String resultId) {
        EdtDispatcher.requireEventDispatchThread();
        requireRepairRow(resultId).button.doClick();
    }

    /// Returns the current independent repair-row state for deterministic headless tests.
    ///
    /// @param resultId stable diagnosis identifier
    /// @return current lifecycle-state name
    String repairStateOnEdt(String resultId) {
        EdtDispatcher.requireEventDispatchThread();
        return requireRepairRow(resultId).state.name();
    }

    /// Returns the immutable lifecycle history for one independent repair row.
    ///
    /// @param resultId stable diagnosis identifier
    /// @return state names in transition order, including the initial available state
    @Unmodifiable List<String> repairStateHistoryOnEdt(String resultId) {
        EdtDispatcher.requireEventDispatchThread();
        return requireRepairRow(resultId).stateHistory.stream().map(Enum::name).toList();
    }

    /// Returns one repair action's visible command text for deterministic headless tests.
    ///
    /// @param resultId stable diagnosis identifier
    /// @return current localized button text
    String repairActionTextOnEdt(String resultId) {
        EdtDispatcher.requireEventDispatchThread();
        return requireRepairRow(resultId).button.getText();
    }

    /// Returns one repair action's visible status text for deterministic headless tests.
    ///
    /// @param resultId stable diagnosis identifier
    /// @return current localized status text
    String repairStatusTextOnEdt(String resultId) {
        EdtDispatcher.requireEventDispatchThread();
        return requireRepairRow(resultId).status.getText();
    }

    /// Returns one repair row's visible bounded evidence for deterministic headless tests.
    ///
    /// @param resultId stable diagnosis identifier
    /// @return localized evidence label text
    String repairEvidenceTextOnEdt(String resultId) {
        EdtDispatcher.requireEventDispatchThread();
        return requireRepairRow(resultId).evidenceText;
    }

    /// Reports whether one repair action is currently enabled.
    ///
    /// @param resultId stable diagnosis identifier
    /// @return true when the action accepts another click
    boolean isRepairActionEnabledOnEdt(String resultId) {
        EdtDispatcher.requireEventDispatchThread();
        return requireRepairRow(resultId).button.isEnabled();
    }

    /// Resolves one rendered repair row or fails the caller's invalid test request.
    ///
    /// @param resultId stable diagnosis identifier
    /// @return rendered repair row
    private RepairRow requireRepairRow(String resultId) {
        @Nullable RepairRow row = repairRows.get(Objects.requireNonNull(resultId, "resultId"));
        if (row == null) {
            throw new IllegalArgumentException("Unknown repair row: " + resultId);
        }
        return row;
    }

    /// Creates or raises the native frame and starts diagnosis once.
    private void showOnEdt() {
        EdtDispatcher.requireEventDispatchThread();
        if (closed.get()) {
            return;
        }
        try {
            JPanel root = contentOnEdt();
            startAnalysisOnEdt();
            if (!nativePresentationEnabled || GraphicsEnvironment.isHeadless()) {
                return;
            }

            if (frame == null) {
                JFrame createdFrame = new JFrame(i18n(
                        model.diagnosticOnly() ? "game.crash.import.title" : "game.crash.title"));
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
        } catch (RuntimeException | Error failure) {
            followUpCompletion.completeExceptionally(failure);
            throw failure;
        }
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

    /// Creates the exit-type-specific headline with the crash-report QR marker at the trailing edge.
    ///
    /// @return header component
    private Component createHeaderOnEdt() {
        EdtDispatcher.requireEventDispatchThread();
        JLabel headline = new JLabel(model.diagnosticOnly()
                ? i18n("game.crash.import.title")
                : titleFor(model.exitType()));
        headline.setFont(headline.getFont().deriveFont(Font.BOLD, headline.getFont().getSize2D() + 2.0F));
        headline.setBorder(BorderFactory.createEmptyBorder(2, 2, 2, 2));

        CrashReportQrCodeMarker qrCodeMarker = new CrashReportQrCodeMarker();
        reportQrCodeMarker = qrCodeMarker;

        JPanel header = new JPanel(new BorderLayout(12, 0));
        header.add(headline, BorderLayout.CENTER);
        header.add(qrCodeMarker, BorderLayout.EAST);
        return header;
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
        scroll.setName("gameCrashInformationScroll");
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
        reason.setText(htmlDocument(displayedReason));
        reason.setCaretPosition(0);
        reasonPane = reason;

        JPanel diagnosisContent = new CrashDiagnosisViewport();
        diagnosisContent.setLayout(new BoxLayout(diagnosisContent, BoxLayout.Y_AXIS));
        diagnosisContent.setAlignmentX(Component.LEFT_ALIGNMENT);
        reason.setAlignmentX(Component.LEFT_ALIGNMENT);
        diagnosisContent.add(reason);
        diagnosisContent.add(Box.createVerticalStrut(8));
        JPanel rows = new JPanel();
        rows.setLayout(new BoxLayout(rows, BoxLayout.Y_AXIS));
        rows.setOpaque(false);
        rows.setAlignmentX(Component.LEFT_ALIGNMENT);
        rows.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
        diagnosisRowsPanel = rows;
        diagnosisContent.add(rows);

        JScrollPane scroll = new JScrollPane(diagnosisContent);
        scroll.setName("gameCrashReasonScroll");
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
        JButton logs = new JButton(i18n("logwindow.title"));
        logs.setName("gameCrashLogs");
        logs.addActionListener(event -> showGameLogsOnEdt());

        JButton help = new JButton(i18n("help"));
        help.setName("gameCrashHelp");
        help.setToolTipText(i18n("logwindow.help"));
        help.addActionListener(event -> openLink(URI.create(Metadata.CONTACT_URL)));

        JLabel status = new JLabel(" ", SwingConstants.LEADING);
        status.setName("gameCrashOperationStatus");
        operationStatus = status;

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.TRAILING, 8, 0));
        buttons.setName("gameCrashActionButtons");
        if (model.exportAllowed()) {
            JButton export = new JButton(i18n("logwindow.export_game_crash_logs"));
            export.setName("gameCrashExport");
            export.addActionListener(event -> exportCrashLogsOnEdt());
            exportButton = export;
            buttons.add(export);

            JButton reveal = new JButton(i18n("button.reveal_dir"));
            reveal.setName("gameCrashReveal");
            reveal.setToolTipText(i18n("reveal.in_file_manager"));
            reveal.getAccessibleContext().setAccessibleName(i18n("reveal.in_file_manager"));
            reveal.addActionListener(event -> revealExportedCrashReportOnEdt());
            reveal.setVisible(false);
            revealButton = reveal;
            buttons.add(reveal);
        }
        buttons.add(logs);
        buttons.add(help);

        JPanel toolbar = new JPanel(new BorderLayout(8, 0));
        toolbar.setName("gameCrashActionsToolbar");
        toolbar.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, dividerColor()));
        toolbar.add(status, BorderLayout.CENTER);
        toolbar.add(buttons, BorderLayout.EAST);
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
            stage = analysisService.analyze(model.logAnalyzable(), model.latestLog());
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
            renderDiagnosisRowsOnEdt(null);
        } else {
            displayedReason = reasonFormatter.format(result);
            renderDiagnosisRowsOnEdt(result);
        }
        if (!hasPendingMissingDependencySearchOnEdt()) {
            followUpCompletion.complete(null);
        }
        @Nullable JEditorPane reason = reasonPane;
        if (reason != null) {
            reason.setText(htmlDocument(displayedReason));
            reason.setCaretPosition(0);
        }
    }

    /// Rebuilds the ordered independent-cause list without starting any repair task.
    ///
    /// @param analysis merged diagnosis, or null after an analysis failure
    private void renderDiagnosisRowsOnEdt(@Nullable GameCrashAnalysis analysis) {
        EdtDispatcher.requireEventDispatchThread();
        @Nullable JPanel rows = diagnosisRowsPanel;
        if (rows == null) {
            return;
        }
        rows.removeAll();
        repairRows.clear();
        if (analysis == null || analysis.resultCount() == 0) {
            rows.revalidate();
            rows.repaint();
            return;
        }

        Set<String> renderedCauseIds = new LinkedHashSet<>();
        for (AnalyzeResult<LogAnalyzable> diagnosis : analysis.logResults()) {
            String resultId = diagnosis.resultId().name();
            if (!renderedCauseIds.add(resultId)) {
                continue;
            }
            String reason = i18n(
                    diagnosis.solver().messageKey(),
                    diagnosis.solver().messageArguments().stream()
                            .map(GameCrashReasonFormatter::escapeHtmlArgument)
                            .toArray());
            @Unmodifiable List<String> sources =
                    analysis.logEvidenceSources().getOrDefault(diagnosis.resultId(), List.of());
            String sourceText = String.join(", ", sources);
            String matchText = String.join("; ", diagnosis.evidence());
            String evidenceText;
            if (matchText.isEmpty()) {
                evidenceText = sourceText.isEmpty() ? i18n("game.crash.repair.log_evidence") : sourceText;
            } else {
                evidenceText = sourceText.isEmpty() ? matchText : sourceText + "; " + matchText;
            }
            String evidence = i18n("game.crash.repair.evidence", boundedEvidence(evidenceText));
            rows.add(createRepairRowOnEdt(
                    resultId,
                    reason,
                    evidence,
                    model.repairActionsAllowed() ? diagnosis.solver() : null,
                    analysis.runtimeCandidates(diagnosis.resultId())));
            rows.add(Box.createVerticalStrut(6));
        }
        for (space.minecraftstl.xyml.game.CrashReportAnalyzer.Result diagnosis : analysis.results()) {
            String resultId = diagnosis.rule().name();
            if (!renderedCauseIds.add(resultId)) {
                continue;
            }
            String reason = reasonFormatter.format(new GameCrashAnalysis(List.of(diagnosis), Set.of()));
            List<String> sources = analysis.evidenceSources().getOrDefault(diagnosis.rule(), List.of());
            String sourceAndMatch = String.join(", ", sources);
            String match = boundedEvidence(diagnosis.matcher().group());
            String evidenceText = sourceAndMatch.isEmpty() ? match : sourceAndMatch + "; " + match;
            String evidence = i18n(
                    "game.crash.repair.evidence",
                    boundedEvidence(evidenceText));
            rows.add(createRepairRowOnEdt(
                    resultId,
                    reason,
                    evidence,
                    null,
                    List.of()));
            rows.add(Box.createVerticalStrut(6));
        }
        rows.revalidate();
        rows.repaint();
    }

    /// Reports whether the rendered diagnosis keeps one executable missing-dependency search available.
    ///
    /// @return true while at least one read-only search row needs an explicit user decision
    private boolean hasPendingMissingDependencySearchOnEdt() {
        EdtDispatcher.requireEventDispatchThread();
        return repairRows.values().stream().anyMatch(row -> isRepeatableSearch(row.solver));
    }

    /// Creates one compact reason row with an independent repair action when a safe solver exists.
    ///
    /// @param resultId stable cause identifier
    /// @param reason localized explanation
    /// @param evidence bounded evidence summary
    /// @param solver optional repair solver
    /// @param candidates immutable runtime candidates captured during background analysis
    /// @return composed row component
    private JPanel createRepairRowOnEdt(
            String resultId,
            String reason,
            String evidence,
            @Nullable Solver solver,
            @Unmodifiable List<LogAnalyzable.JavaRuntimeCandidate> candidates) {
        EdtDispatcher.requireEventDispatchThread();
        JPanel row = new JPanel(new BorderLayout(8, 4));
        row.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(dividerColor()),
                BorderFactory.createEmptyBorder(6, 8, 6, 8)));
        row.setAlignmentX(Component.LEFT_ALIGNMENT);

        JPanel details = new JPanel();
        details.setLayout(new BoxLayout(details, BoxLayout.Y_AXIS));
        details.setOpaque(false);
        details.setAlignmentX(Component.LEFT_ALIGNMENT);
        JEditorPane text = new JEditorPane("text/html", htmlDocument(reason));
        text.setEditable(false);
        text.setOpaque(false);
        text.setAlignmentX(Component.LEFT_ALIGNMENT);
        text.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, true);
        text.setBorder(BorderFactory.createEmptyBorder());
        details.add(text);
        WrappingHtmlPane evidenceLabel = new WrappingHtmlPane(
                htmlDocument(GameCrashReasonFormatter.escapeHtmlArgument(evidence)),
                row.getFont().deriveFont(Font.PLAIN, row.getFont().getSize2D() - 1.0F));
        details.add(evidenceLabel);
        row.add(details, BorderLayout.CENTER);

        boolean executable = solver != null && solver.repairAction().executable();
        @Nullable JLabel status = null;
        if (executable) {
            status = new JLabel();
            status.setVisible(false);
        }
        @Nullable JButton action = null;
        if (executable) {
            action = new JButton(actionLabel(solver));
            action.setName("gameCrashRepair-" + resultId);
        }
        if (action != null) {
            JLabel visibleStatus = Objects.requireNonNull(status, "status");
            RepairRow repairRow = new RepairRow(
                    resultId, solver, candidates, action, evidenceLabel, evidence, visibleStatus);
            repairRows.put(resultId, repairRow);
            action.addActionListener(event -> executeRepairRowOnEdt(repairRow));
            JPanel controls = new JPanel(new BorderLayout(4, 4));
            controls.setOpaque(false);
            controls.add(visibleStatus, BorderLayout.NORTH);
            controls.add(action, BorderLayout.SOUTH);
            row.add(controls, BorderLayout.EAST);
        }
        return row;
    }

    /// Bounds evidence before it enters a plain Swing label.
    ///
    /// @param evidence matched log fragment
    /// @return bounded plain-text evidence
    private static String boundedEvidence(String evidence) {
        String normalized = Objects.requireNonNull(evidence, "evidence")
                .replaceAll("[\\r\\n\\t]+", " ")
                .strip();
        String bounded = normalized.length() <= 240 ? normalized : normalized.substring(0, 240) + "...";
        return bounded;
    }

    /// Claims one row and schedules creation and execution of a fresh solver task off the EDT.
    ///
    /// @param row mutable row state
    private void executeRepairRowOnEdt(RepairRow row) {
        EdtDispatcher.requireEventDispatchThread();
        if (!model.repairActionsAllowed()) {
            return;
        }
        if (closed.get()
                || row.state == RepairState.PREPARING
                || row.state == RepairState.AWAITING_SELECTION
                || row.state == RepairState.RUNNING
                || (row.state == RepairState.SUCCEEDED && !isRepeatableSearch(row.solver))) {
            return;
        }
        if (row.state == RepairState.BLOCKED_RESIDUAL) {
            @Nullable TaskExecutor residualExecutor = row.executor;
            if (residualExecutor != null) {
                row.transitionTo(RepairState.PREPARING);
                row.button.setEnabled(false);
                row.setStatus(actionStatus(
                        row.solver,
                        "game.crash.repair.preparing",
                        "game.crash.search_missing_dependency.preparing"));
                scheduleResidualCleanupOnWorker(row, residualExecutor);
                return;
            }
            row.transitionTo(RepairState.AVAILABLE);
        }
        row.transitionTo(RepairState.PREPARING);
        row.button.setEnabled(false);
        row.button.setText(actionLabel(row.solver));
        row.setStatus(actionStatus(
                row.solver,
                "game.crash.repair.preparing",
                "game.crash.search_missing_dependency.preparing"));
        if (row.solver.repairAction().confirmationRequirement()
                == RepairActionDescriptor.ConfirmationRequirement.REQUIRED
                && !confirmRepairOnEdt(row)) {
            row.transitionTo(RepairState.AVAILABLE);
            row.clearStatus();
            row.button.setEnabled(true);
            return;
        }
        CandidateChoice candidateChoice;
        try {
            candidateChoice = chooseCandidateOnEdt(row);
        } catch (RuntimeException candidateFailure) {
            failCandidateSelectionOnEdt(row, candidateFailure);
            return;
        }
        if (candidateChoice.cancelled()) {
            row.transitionTo(RepairState.AVAILABLE);
            row.clearStatus();
            row.button.setEnabled(true);
            return;
        }
        scheduleRepairPreparationOnWorker(row, candidateChoice.candidateId());
    }

    /// Schedules bounded residual-resource cleanup away from the Swing event thread.
    ///
    /// @param row mutable repair row
    /// @param executor task executor retaining the residual resources
    private void scheduleResidualCleanupOnWorker(RepairRow row, TaskExecutor executor) {
        Objects.requireNonNull(row, "row");
        Objects.requireNonNull(executor, "executor");
        try {
            worker.execute(() -> retryResidualCleanupOnWorker(row, executor));
        } catch (RuntimeException | Error schedulingFailure) {
            finishResidualCleanupOnEdt(row, executor, false, List.of(schedulingFailure.getClass().getSimpleName()),
                    schedulingFailure);
        }
    }

    /// Performs residual cleanup and publishes the result back to the EDT.
    ///
    /// @param row mutable repair row retained for the EDT callback
    /// @param executor executor whose task-owned cleanup is being retried
    private void retryResidualCleanupOnWorker(RepairRow row, TaskExecutor executor) {
        boolean cleanupSucceeded = false;
        @Unmodifiable List<String> residual = List.of();
        @Nullable Throwable failure = null;
        try {
            cleanupSucceeded = executor.retryResourceCleanup();
            residual = List.copyOf(executor.getResidualResources());
            cleanupSucceeded = cleanupSucceeded && residual.isEmpty();
            if (!cleanupSucceeded && residual.isEmpty()) {
                residual = List.of("cleanup incomplete");
            }
        } catch (RuntimeException | Error cleanupFailure) {
            failure = cleanupFailure;
            residual = List.of(cleanupFailure.getClass().getSimpleName());
        }
        boolean completedCleanup = cleanupSucceeded;
        @Unmodifiable List<String> completedResidual = residual;
        @Nullable Throwable completedFailure = failure;
        EdtDispatcher.execute(() -> finishResidualCleanupOnEdt(
                row,
                executor,
                completedCleanup,
                completedResidual,
                completedFailure));
    }

    /// Publishes residual cleanup and resumes the normal confirmation/selection path after success.
    ///
    /// @param row mutable repair row
    /// @param executor executor whose cleanup was retried
    /// @param cleanupSucceeded whether no residual resource remains
    /// @param residual residual descriptions, or a bounded failure description
    /// @param failure cleanup exception, if any
    private void finishResidualCleanupOnEdt(
            RepairRow row,
            TaskExecutor executor,
            boolean cleanupSucceeded,
            @Unmodifiable List<String> residual,
            @Nullable Throwable failure) {
        EdtDispatcher.requireEventDispatchThread();
        if (closed.get() || row.state != RepairState.PREPARING || row.executor != executor) {
            return;
        }
        if (!cleanupSucceeded) {
            markBlockedResidualOnEdt(row, residual);
            if (failure != null) {
                LOG.warning("Failed to retry crash-repair resource cleanup for " + row.resultId, failure);
            }
            return;
        }
        row.executor = null;
        if (row.residualOriginalSuccess) {
            row.residualOriginalSuccess = false;
            row.transitionTo(RepairState.SUCCEEDED);
            if (isRepeatableSearch(row.solver)) {
                row.setStatus(i18n("game.crash.search_missing_dependency.done"));
                row.button.setText(actionLabel(row.solver));
                row.button.setEnabled(true);
                setOperationStatusOnEdt(i18n("game.crash.search_missing_dependency.done"));
                followUpCompletion.complete(null);
            } else {
                row.setStatus(i18n("game.crash.repair.succeeded"));
                row.button.setEnabled(false);
            }
            return;
        }
        row.residualOriginalSuccess = false;
        row.transitionTo(RepairState.AVAILABLE);
        row.clearStatus();
        row.button.setText(actionLabel(row.solver));
        row.button.setEnabled(true);
        executeRepairRowOnEdt(row);
    }

    /// Creates and starts one solver task on the window worker, never on the Swing event thread.
    ///
    /// The worker checks the close flag before each lifecycle boundary. A task which has already started still owns
    /// its normal executor cleanup; the completion callback carries that executor so a residual lease remains
    /// available to the row's retry action instead of being discarded by a late window close.
    ///
    /// @param row mutable row state, accessed only by callbacks on the EDT
    /// @param candidateId selected candidate identifier, or null for the solver's ordinary automatic path
    private void scheduleRepairPreparationOnWorker(RepairRow row, @Nullable String candidateId) {
        Objects.requireNonNull(row, "row");
        try {
            worker.execute(() -> prepareAndStartRepairOnWorker(row, candidateId));
        } catch (RuntimeException | Error schedulingFailure) {
            finishRepairRowOnEdt(row, false, null, schedulingFailure);
        }
    }

    /// Creates a solver task, attaches its listener, and starts it on the worker thread.
    ///
    /// @param row mutable row state retained for the EDT callbacks
    /// @param candidateId selected candidate identifier, or null for the solver's ordinary automatic path
    private void prepareAndStartRepairOnWorker(RepairRow row, @Nullable String candidateId) {
        if (closed.get()) {
            return;
        }
        @Nullable TaskExecutor executor = null;
        @Nullable Throwable failure = null;
        try {
            Task<?> task = Objects.requireNonNull(
                    row.solver.createTask(candidateId),
                    "repair solver returned no task");
            boolean taskPublishesPhases = repairTaskPhase(task) == RepairTaskPhase.PREPARING;
            AtomicReference<TaskExecutor> executorReference = new AtomicReference<>();
            executor = task.executor(new TaskListener() {
                /// Publishes executor ownership after cancellation becomes legal but before task work begins.
                @Override
                public void onStart() {
                    TaskExecutor startingExecutor = Objects.requireNonNull(
                            executorReference.get(),
                            "repair executor");
                    AtomicBoolean published = new AtomicBoolean();
                    try {
                        EdtDispatcher.executeAndWait(() -> published.set(
                                publishRepairStartedOnEdt(
                                        row,
                                        startingExecutor,
                                        taskPublishesPhases)));
                    } finally {
                        if (!published.get() && !startingExecutor.isCancelled()) {
                            startingExecutor.cancel();
                        }
                    }
                }

                /// Publishes a task-owned selection or execution phase without treating preparation as running.
                ///
                /// @param updatedTask task whose property changed
                @Override
                public void onPropertiesUpdate(Task<?> updatedTask) {
                    if (updatedTask != task) {
                        return;
                    }
                    @Nullable RepairTaskPhase phase = repairTaskPhase(updatedTask);
                    if (phase == null || phase == RepairTaskPhase.PREPARING) {
                        return;
                    }
                    TaskExecutor currentExecutor = Objects.requireNonNull(
                            executorReference.get(),
                            "repair executor");
                    AtomicBoolean published = new AtomicBoolean();
                    try {
                        EdtDispatcher.executeAndWait(() -> published.set(
                                publishRepairTaskPhaseOnEdt(row, currentExecutor, phase)));
                    } finally {
                        if (!published.get() && !currentExecutor.isCancelled()) {
                            currentExecutor.cancel();
                        }
                    }
                }

                /// {@inheritDoc}
                @Override
                public void onStop(boolean success, TaskExecutor stoppedExecutor) {
                    @Nullable Throwable taskFailure = success ? null : stoppedExecutor.getFailure();
                    EdtDispatcher.execute(() -> finishRepairRowOnEdt(
                            row,
                            success,
                            stoppedExecutor,
                            taskFailure));
                }
            });
            TaskExecutor createdExecutor = executor;
            executorReference.set(createdExecutor);
            if (closed.get()) {
                return;
            }
            createdExecutor.start();
        } catch (RuntimeException | Error lifecycleFailure) {
            failure = lifecycleFailure;
        }
        if (failure != null) {
            @Nullable TaskExecutor failedExecutor = executor;
            Throwable lifecycleFailure = failure;
            EdtDispatcher.execute(() -> finishRepairRowOnEdt(row, false, failedExecutor, lifecycleFailure));
        }
    }

    /// Publishes a started executor before any task body can run.
    ///
    /// The executor sets its started flag before invoking its start listener, so a concurrent close may cancel it
    /// safely. Returning false makes the listener abort the execution chain before the task body begins.
    ///
    /// @param row mutable row state
    /// @param executor started executor whose task body has not begun
    /// @param taskPublishesPhases whether the task reports its real internal selection lifecycle
    /// @return true when the row accepted this execution chain
    private boolean publishRepairStartedOnEdt(
            RepairRow row,
            TaskExecutor executor,
            boolean taskPublishesPhases) {
        EdtDispatcher.requireEventDispatchThread();
        if (closed.get()) {
            return false;
        }
        if (row.state == RepairState.PREPARING || row.state == RepairState.AWAITING_SELECTION) {
            row.executor = executor;
            if (taskPublishesPhases) {
                return true;
            }
            if (isRepeatableSearch(row.solver) && row.state == RepairState.PREPARING) {
                row.transitionTo(RepairState.AWAITING_SELECTION);
            }
            row.transitionTo(RepairState.RUNNING);
            row.setStatus(actionStatus(
                    row.solver,
                    "game.crash.repair.running",
                    "game.crash.search_missing_dependency.running"));
            return true;
        }
        return false;
    }

    /// Publishes the exact internal phase of a selection-aware repair task.
    ///
    /// @param row mutable repair row
    /// @param executor executor owning the current attempt
    /// @param phase newly published task phase
    /// @return true when the current row accepted the phase
    private boolean publishRepairTaskPhaseOnEdt(
            RepairRow row,
            TaskExecutor executor,
            RepairTaskPhase phase) {
        EdtDispatcher.requireEventDispatchThread();
        if (closed.get() || row.executor != executor) {
            return false;
        }
        if (phase == RepairTaskPhase.AWAITING_SELECTION && row.state == RepairState.PREPARING) {
            row.transitionTo(RepairState.AWAITING_SELECTION);
            row.setStatus(i18n("game.crash.search_missing_dependency.awaiting_selection"));
            return true;
        }
        if (phase == RepairTaskPhase.RUNNING
                && (row.state == RepairState.PREPARING || row.state == RepairState.AWAITING_SELECTION)) {
            row.transitionTo(RepairState.RUNNING);
            row.setStatus(actionStatus(
                    row.solver,
                    "game.crash.repair.running",
                    "game.crash.search_missing_dependency.running"));
            return true;
        }
        return false;
    }

    /// Reads one optional task-owned phase marker without trusting arbitrary task properties.
    ///
    /// @param task task whose immutable phase value is requested
    /// @return recognized phase, or null for a conventional task lifecycle
    private static @Nullable RepairTaskPhase repairTaskPhase(Task<?> task) {
        Object value = Objects.requireNonNull(task, "task")
                .getProperties()
                .get(RepairTaskPhase.TASK_PROPERTY);
        return value instanceof RepairTaskPhase phase ? phase : null;
    }

    /// Publishes a candidate-enumeration failure while the row is still awaiting the internal selection step.
    ///
    /// @param row mutable repair row
    /// @param failure candidate discovery or dialog failure
    private void failCandidateSelectionOnEdt(RepairRow row, RuntimeException failure) {
        EdtDispatcher.requireEventDispatchThread();
        row.transitionTo(RepairState.FAILED_RETRYABLE);
        row.setStatus(i18n("game.crash.repair.failed"));
        row.button.setText(i18n("game.crash.repair.retry"));
        row.button.setEnabled(true);
        LOG.warning("Failed to select Java runtime candidate for " + row.resultId, failure);
    }

    /// Publishes a row's terminal state and enables retry after failure.
    ///
    /// @param row mutable row state
    /// @param success whether the fresh task completed successfully
    /// @param stoppedExecutor completed executor, or null when task creation failed
    /// @param failure task or scheduling failure
    private void finishRepairRowOnEdt(
            RepairRow row,
            boolean success,
            @Nullable TaskExecutor stoppedExecutor,
            @Nullable Throwable failure) {
        EdtDispatcher.requireEventDispatchThread();
        if (closed.get() || (row.state != RepairState.PREPARING
                && row.state != RepairState.AWAITING_SELECTION
                && row.state != RepairState.RUNNING)) {
            return;
        }
        @Nullable TaskExecutor completedExecutor = stoppedExecutor == null ? row.executor : stoppedExecutor;
        if (completedExecutor != null && row.executor == null) {
            row.executor = completedExecutor;
        }
        if (completedExecutor != null) {
            try {
                @Unmodifiable List<String> residual = completedExecutor.getResidualResources();
                if (!residual.isEmpty()) {
                    row.residualOriginalSuccess = success;
                    markBlockedResidualOnEdt(row, residual);
                    return;
                }
            } catch (RuntimeException residualFailure) {
                row.residualOriginalSuccess = false;
                markBlockedResidualOnEdt(row, List.of(residualFailure.getClass().getSimpleName()));
                LOG.warning("Unable to inspect crash-repair resource cleanup for " + row.resultId, residualFailure);
                return;
            }
        }
        row.executor = null;
        if (success) {
            row.transitionTo(RepairState.SUCCEEDED);
            if (isRepeatableSearch(row.solver)) {
                row.setStatus(i18n("game.crash.search_missing_dependency.done"));
                row.button.setText(actionLabel(row.solver));
                row.button.setEnabled(true);
                setOperationStatusOnEdt(i18n("game.crash.search_missing_dependency.done"));
                followUpCompletion.complete(null);
            } else {
                row.setStatus(i18n("game.crash.repair.succeeded"));
                row.button.setEnabled(false);
            }
            return;
        }
        if (unwrapFailure(failure) instanceof CancellationException) {
            row.transitionTo(RepairState.AVAILABLE);
            row.clearStatus();
            row.button.setText(actionLabel(row.solver));
            row.button.setEnabled(true);
            return;
        }
        row.transitionTo(RepairState.FAILED_RETRYABLE);
        row.setStatus(actionStatus(
                row.solver,
                "game.crash.repair.failed",
                "game.crash.search_missing_dependency.failed"));
        row.button.setText(actionStatus(
                row.solver,
                "game.crash.repair.retry",
                "game.crash.search_missing_dependency.retry"));
        row.button.setEnabled(true);
        String actionName = isRepeatableSearch(row.solver) ? "Missing-dependency search" : "Automatic crash repair";
        LOG.warning(actionName + " failed for " + row.resultId, unwrapFailure(failure));
    }

    /// Keeps a repair row retryable while task-owned resource cleanup remains blocked.
    ///
    /// @param row mutable repair row
    /// @param residual resource descriptions, used only for diagnostics
    private void markBlockedResidualOnEdt(RepairRow row, @Unmodifiable List<String> residual) {
        EdtDispatcher.requireEventDispatchThread();
        row.transitionTo(RepairState.BLOCKED_RESIDUAL);
        row.setStatus(actionStatus(
                row.solver,
                "game.crash.repair.failed",
                "game.crash.search_missing_dependency.failed"));
        row.button.setText(actionStatus(
                row.solver,
                "game.crash.repair.retry",
                "game.crash.search_missing_dependency.retry"));
        row.button.setEnabled(true);
        LOG.warning("Crash repair retains task resources for " + row.resultId + ": " + residual);
    }

    /// Confirms a repair that can modify persistent launcher or runtime state.
    ///
    /// A headless invocation is already an explicit programmatic button action, so it proceeds without a native
    /// dialog. This keeps command-line and test callers usable while ensuring the Swing path never writes before the
    /// user confirms.
    private boolean confirmRepairOnEdt(RepairRow row) {
        EdtDispatcher.requireEventDispatchThread();
        row.transitionTo(RepairState.AWAITING_SELECTION);
        return repairInteraction.confirm(Objects.requireNonNull(content, "content"));
    }

    /// Presents the internal Java-runtime choice after repair confirmation.
    ///
    /// A single candidate does not require an additional prompt. In headless mode the recommended candidate is
    /// selected deterministically. Returning a cancelled choice never creates a task and therefore cannot mutate
    /// launcher settings.
    ///
    /// @param row repair row whose solver owns the candidate boundary
    /// @return selected or cancelled candidate choice
    private CandidateChoice chooseCandidateOnEdt(RepairRow row) {
        EdtDispatcher.requireEventDispatchThread();
        @Unmodifiable List<LogAnalyzable.JavaRuntimeCandidate> candidates = row.candidates;
        if (candidates.isEmpty()) {
            return CandidateChoice.automatic();
        }
        row.transitionTo(RepairState.AWAITING_SELECTION);
        LogAnalyzable.JavaRuntimeCandidate recommended = candidates.stream()
                .filter(LogAnalyzable.JavaRuntimeCandidate::recommended)
                .findFirst()
                .orElse(candidates.get(0));
        @Nullable LogAnalyzable.JavaRuntimeCandidate selected = repairInteraction.selectJavaRuntime(
                Objects.requireNonNull(content, "content"),
                candidates,
                recommended);
        if (selected == null) {
            return CandidateChoice.cancelledChoice();
        }
        return CandidateChoice.selected(selected.id());
    }

    /// Returns the command label for one structured solver action.
    ///
    /// Missing-dependency search is deliberately presented as a read-only search command; all other executable
    /// actions are persistent automatic repairs and use the repair command wording.
    ///
    /// @param solver structured solver whose action is being rendered
    /// @return localized command label
    private static String actionLabel(Solver solver) {
        return solver.repairAction().actionType() == RepairActionDescriptor.ActionType.OPEN_MOD_SEARCH
                ? i18n("game.crash.search_missing_dependency")
                : i18n("game.crash.repair.execute");
    }

    /// Selects localized state text from the solver's read-only or persistent action class.
    ///
    /// @param solver structured solver whose state is being rendered
    /// @param repairKey localization key for persistent repair actions
    /// @param searchKey localization key for read-only missing-dependency search
    /// @return localized action-specific state text
    private static String actionStatus(Solver solver, String repairKey, String searchKey) {
        return i18n(isRepeatableSearch(solver) ? searchKey : repairKey);
    }

    /// Returns whether a successful read-only action remains useful for another explicit selection.
    ///
    /// @param solver structured solver whose action has completed
    /// @return true only for the read-only missing-dependency search action
    private static boolean isRepeatableSearch(Solver solver) {
        return solver.repairAction().actionType() == RepairActionDescriptor.ActionType.OPEN_MOD_SEARCH;
    }

    /// Starts one asynchronous crash-bundle export and disables duplicate requests until completion.
    private void exportCrashLogsOnEdt() {
        EdtDispatcher.requireEventDispatchThread();
        if (closed.get()) {
            return;
        }
        JButton export = Objects.requireNonNull(exportButton, "export button");
        JButton reveal = Objects.requireNonNull(revealButton, "reveal button");
        exportedCrashReport = null;
        reveal.setEnabled(true);
        reveal.setVisible(false);
        export.setEnabled(false);
        setOperationStatusOnEdt(i18n("logwindow.export_game_crash_logs") + "...");

        CompletableFuture<Path> future;
        try {
            future = actions.exportCrashLogs().toCompletableFuture();
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
    /// @param failure export failure, or null after success
    private void finishExportOnEdt(@Nullable Path result, @Nullable Throwable failure) {
        EdtDispatcher.requireEventDispatchThread();
        if (closed.get()) {
            return;
        }
        JButton export = Objects.requireNonNull(exportButton, "export button");
        JButton reveal = Objects.requireNonNull(revealButton, "reveal button");
        export.setEnabled(true);
        if (failure == null && result != null) {
            exportedCrashReport = result.toAbsolutePath();
            reveal.setEnabled(true);
            reveal.setVisible(true);
            String message = i18n("message.success");
            setOperationStatusOnEdt(message);
            return;
        }

        exportedCrashReport = null;
        reveal.setEnabled(true);
        reveal.setVisible(false);
        Throwable exportFailure = unwrapFailure(failure);
        LOG.warning("Failed to export game crash info", exportFailure);
        String message = i18n("settings.launcher.launcher_log.export.failed");
        setOperationStatusOnEdt(message);
        showMessageOnEdt(
                message + "\n" + StringUtils.getStackTrace(exportFailure),
                i18n("message.error"),
                JOptionPane.ERROR_MESSAGE);
    }

    /// Reveals the latest successfully exported report through the native file manager.
    private void revealExportedCrashReportOnEdt() {
        EdtDispatcher.requireEventDispatchThread();
        if (closed.get()) {
            return;
        }
        @Nullable Path report = exportedCrashReport;
        @Nullable JButton reveal = revealButton;
        if (report == null || reveal == null) {
            return;
        }
        reveal.setEnabled(false);

        CompletableFuture<Void> future;
        try {
            future = CompletableFuture.runAsync(() -> {
                try {
                    actions.revealFile(report);
                } catch (Exception failure) {
                    throw new CompletionException(failure);
                }
            }, worker);
        } catch (RuntimeException failure) {
            finishRevealOnEdt(failure);
            return;
        }
        revealFuture = future;
        future.whenComplete((@Nullable Void ignored, @Nullable Throwable failure) ->
                EdtDispatcher.execute(() -> finishRevealOnEdt(failure)));
    }

    /// Restores the reveal action and reports a file-manager failure unless the window already closed.
    ///
    /// @param failure reveal failure, or null after success
    private void finishRevealOnEdt(@Nullable Throwable failure) {
        EdtDispatcher.requireEventDispatchThread();
        revealFuture = null;
        if (closed.get()) {
            return;
        }
        @Nullable JButton reveal = revealButton;
        if (reveal != null) {
            reveal.setEnabled(true);
        }
        if (failure == null) {
            return;
        }

        Throwable revealFailure = unwrapFailure(failure);
        LOG.warning("Failed to reveal exported game crash report", revealFailure);
        setOperationStatusOnEdt(i18n("message.failed"));
        showMessageOnEdt(
                i18n("message.failed") + "\n" + StringUtils.getStackTrace(revealFailure),
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

    /// Presents a native message dialog only while native presentation is enabled and the window remains live.
    ///
    /// @param message dialog body
    /// @param title dialog title
    /// @param messageType Swing message type constant
    private void showMessageOnEdt(String message, String title, int messageType) {
        EdtDispatcher.requireEventDispatchThread();
        if (closed.get() || !nativePresentationEnabled || GraphicsEnvironment.isHeadless()) {
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
        diagnosisRowsPanel = null;
        analysisProgress = null;
        reportQrCodeMarker = null;
        operationStatus = null;
        exportButton = null;
        revealButton = null;
        exportedCrashReport = null;
        revealFuture = null;
        for (RepairRow row : repairRows.values()) {
            row.cancelOnClose();
        }
        repairRows.clear();
        followUpCompletion.complete(null);
    }

    /// Abstracts the two modal choices in one repair attempt so headless tests can exercise cancellation safely.
    @NotNullByDefault
    interface RepairInteraction {
        /// Obtains confirmation before a repair that can modify persistent state.
        ///
        /// @param parent crash-window content used as the modal parent
        /// @return true when repair preparation may continue
        boolean confirm(JPanel parent);

        /// Selects one Java runtime candidate after the repair has been confirmed.
        ///
        /// @param parent crash-window content used as the modal parent
        /// @param candidates immutable candidate snapshot in display order
        /// @param recommended candidate preselected by the launcher
        /// @return selected candidate, or null when the user cancels
        @Nullable LogAnalyzable.JavaRuntimeCandidate selectJavaRuntime(
                JPanel parent,
                @Unmodifiable List<LogAnalyzable.JavaRuntimeCandidate> candidates,
                LogAnalyzable.JavaRuntimeCandidate recommended);
    }

    /// Production repair interaction backed by native Swing dialogs when a graphics environment is available.
    @NotNullByDefault
    private static final class NativeRepairInteraction implements RepairInteraction {
        /// Shared stateless interaction instance.
        private static final NativeRepairInteraction INSTANCE = new NativeRepairInteraction();

        /// Prevents redundant stateless interaction instances.
        private NativeRepairInteraction() {
        }

        /// Confirms a persistent repair, treating headless callers as explicit advanced invocations.
        ///
        /// @param parent crash-window content used as the modal parent
        /// @return true when execution may continue
        @Override
        public boolean confirm(JPanel parent) {
            if (GraphicsEnvironment.isHeadless()) {
                return true;
            }
            int option = JOptionPane.showConfirmDialog(
                    parent,
                    i18n("game.crash.solver.automatic"),
                    i18n("message.warning"),
                    JOptionPane.YES_NO_OPTION,
                    JOptionPane.WARNING_MESSAGE);
            return option == JOptionPane.YES_OPTION;
        }

        /// Presents the runtime chooser with the recommended candidate preselected.
        ///
        /// @param parent crash-window content used as the modal parent
        /// @param candidates immutable candidate snapshot in display order
        /// @param recommended candidate preselected by the launcher
        /// @return selected candidate, or null when the user cancels
        @Override
        public @Nullable LogAnalyzable.JavaRuntimeCandidate selectJavaRuntime(
                JPanel parent,
                @Unmodifiable List<LogAnalyzable.JavaRuntimeCandidate> candidates,
                LogAnalyzable.JavaRuntimeCandidate recommended) {
            if (candidates.size() == 1 || GraphicsEnvironment.isHeadless()) {
                return recommended;
            }
            Object selected = JOptionPane.showInputDialog(
                    parent,
                    i18n("game.crash.solver.replace_java"),
                    i18n("message.warning"),
                    JOptionPane.QUESTION_MESSAGE,
                    null,
                    candidates.toArray(),
                    recommended);
            return selected instanceof LogAnalyzable.JavaRuntimeCandidate candidate ? candidate : null;
        }
    }

    /// Mutable state for one independent repair row.
    @NotNullByDefault
    private static final class RepairRow {
        /// Stable cause identifier shown only as an internal component suffix.
        private final String resultId;

        /// Solver that creates a fresh task for every attempt.
        private final Solver solver;

        /// Immutable runtime candidates discovered before the row reached the EDT.
        private final @Unmodifiable List<LogAnalyzable.JavaRuntimeCandidate> candidates;

        /// Row action button.
        private final JButton button;

        /// Bounded wrapping evidence view shown for this cause.
        private final JEditorPane evidence;

        /// Plain bounded evidence retained for deterministic tests.
        private final String evidenceText;

        /// Row status label.
        private final JLabel status;

        /// Current row state.
        private RepairState state = RepairState.AVAILABLE;

        /// Ordered lifecycle history retained for deterministic state-machine verification.
        private final List<RepairState> stateHistory = new ArrayList<>(List.of(RepairState.AVAILABLE));

        /// Real task executor for the current attempt, or null before a click.
        private @Nullable TaskExecutor executor;

        /// Whether the task had already succeeded before residual cleanup became blocked.
        private boolean residualOriginalSuccess;

        /// Creates one executable repair row.
        ///
        /// @param resultId stable cause identifier
        /// @param solver solver that creates fresh tasks
        /// @param candidates immutable runtime candidates
        /// @param button row action button
        /// @param evidence bounded wrapping evidence view
        /// @param evidenceText plain bounded evidence retained for tests
        /// @param status lifecycle status label
        private RepairRow(
                String resultId,
                Solver solver,
                @Unmodifiable List<LogAnalyzable.JavaRuntimeCandidate> candidates,
                JButton button,
                JEditorPane evidence,
                String evidenceText,
                JLabel status) {
            this.resultId = Objects.requireNonNull(resultId, "resultId");
            this.solver = Objects.requireNonNull(solver, "solver");
            this.candidates = List.copyOf(Objects.requireNonNull(candidates, "candidates"));
            this.button = Objects.requireNonNull(button, "button");
            this.evidence = Objects.requireNonNull(evidence, "evidence");
            this.evidenceText = Objects.requireNonNull(evidenceText, "evidenceText");
            this.status = Objects.requireNonNull(status, "status");
        }

        /// Records one real lifecycle transition without duplicating an unchanged state.
        ///
        /// @param nextState next independent repair state
        private void transitionTo(RepairState nextState) {
            RepairState checkedState = Objects.requireNonNull(nextState, "nextState");
            if (state == checkedState) {
                return;
            }
            state = checkedState;
            stateHistory.add(checkedState);
        }

        /// Shows one lifecycle status under the row action.
        ///
        /// @param text localized lifecycle status
        private void setStatus(String text) {
            status.setText(Objects.requireNonNull(text, "text"));
            status.setVisible(true);
        }

        /// Clears the lifecycle status when the action is available again.
        private void clearStatus() {
            status.setText("");
            status.setVisible(false);
        }

        /// Requests cancellation of the currently running task during window disposal.
        private void cancelOnClose() {
            @Nullable TaskExecutor currentExecutor = executor;
            if (currentExecutor == null) {
                return;
            }
            try {
                currentExecutor.cancel();
            } catch (RuntimeException | Error cancellationFailure) {
                LOG.warning("Failed to cancel crash repair " + resultId, cancellationFailure);
            }
        }
    }

    /// Independent repair-row lifecycle states.
    @NotNullByDefault
    private enum RepairState {
        /// Action is available but has not been claimed.
        AVAILABLE,

        /// Solver task is being created and preflighted.
        PREPARING,

        /// User selection is required before task execution.
        AWAITING_SELECTION,

        /// Fresh task is running.
        RUNNING,

        /// Repair completed successfully.
        SUCCEEDED,

        /// Repair failed and the same row can be retried with a fresh task.
        FAILED_RETRYABLE,

        /// Repair completed with unresolved cleanup or resource residue.
        BLOCKED_RESIDUAL
    }

    /// Immutable result of the optional candidate selection dialog.
    @NotNullByDefault
    private record CandidateChoice(boolean cancelled, @Nullable String candidateId) {
        /// Creates the ordinary automatic-path choice.
        ///
        /// @return choice that delegates to the solver's ordinary task factory
        private static CandidateChoice automatic() {
            return new CandidateChoice(false, null);
        }

        /// Creates a selected candidate choice.
        ///
        /// @param candidateId selected identifier
        /// @return selected choice
        private static CandidateChoice selected(String candidateId) {
            return new CandidateChoice(false, Objects.requireNonNull(candidateId, "candidateId"));
        }

        /// Creates a cancelled choice that cannot execute a task.
        ///
        /// @return cancelled choice
        private static CandidateChoice cancelledChoice() {
            return new CandidateChoice(true, null);
        }
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

    /// Wraps a trusted localized reason in a display-properties-aware HTML document.
    ///
    /// @param reason localized reason that may contain HTML links
    /// @return complete HTML document
    private static String htmlDocument(String reason) {
        return "<html><body>"
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
