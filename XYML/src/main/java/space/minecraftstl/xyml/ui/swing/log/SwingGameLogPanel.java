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
import space.minecraftstl.xyml.util.Log4jLevel;

import javax.swing.BorderFactory;
import javax.swing.DefaultComboBoxModel;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JToggleButton;
import javax.swing.KeyStroke;
import javax.swing.ListCellRenderer;
import javax.swing.ListSelectionModel;
import javax.swing.ScrollPaneConstants;
import javax.swing.UIManager;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

import static space.minecraftstl.xyml.util.i18n.I18n.i18n;
import static space.minecraftstl.xyml.util.logging.Logger.LOG;

/// Renders and controls one bounded game-process log stream using native Swing components.
///
/// Every method is confined to the Swing event-dispatch thread. The public facade performs that dispatch for callers
/// and copies mutable input batches before they cross the thread boundary.
@NotNullByDefault
final class SwingGameLogPanel extends JPanel {
    /// Severity levels exposed by the five log filter buttons.
    private static final @Unmodifiable List<Log4jLevel> FILTER_LEVELS = List.of(
            Log4jLevel.FATAL,
            Log4jLevel.ERROR,
            Log4jLevel.WARN,
            Log4jLevel.INFO,
            Log4jLevel.DEBUG);

    /// Retention choices exposed by the log panel.
    private static final @Unmodifiable List<Integer> STANDARD_LINE_LIMITS = List.of(500, 2000, 5000, 10000);

    /// Stable preferred panel size for the log surface.
    private static final Dimension PREFERRED_SIZE = new Dimension(800, 480);

    /// Bounded shared history and filter state.
    private final BoundedGameLogBuffer buffer;

    /// Process, clipboard, and export side effects.
    private final GameLogWindowActions actions;

    /// Background executor used for file and JVM-attachment operations.
    private final Executor backgroundExecutor;

    /// Persists user-selected line limits outside the Swing panel.
    private final IntConsumer maxLinesChanged;

    /// Applies the always-on-top toggle to the owning frame.
    private final Consumer<Boolean> alwaysOnTopChanged;

    /// Presents export outcomes.
    private final GameLogWindowNotifier notifier;

    /// Visible rows shown by the log list.
    private final DefaultListModel<Log> visibleModel = new DefaultListModel<>();

    /// Selectable log view supporting multi-row clipboard copy.
    private final JList<Log> logList = new JList<>(visibleModel);

    /// Automatic tail-following toggle.
    private final JCheckBox autoScroll = new JCheckBox(i18n("logwindow.autoscroll"), true);

    /// Always-on-top toggle delegated to the owning frame.
    private final JToggleButton alwaysOnTop = new JToggleButton(i18n("logwindow.always_on_top"));

    /// Retention-limit selector.
    private final JComboBox<Integer> lineLimit;

    /// Severity toggle buttons keyed by their log level.
    private final Map<Log4jLevel, JToggleButton> levelButtons = new EnumMap<>(Log4jLevel.class);

    /// Exports the retained text snapshot.
    private final JButton exportLogs = new JButton(i18n("button.export"));

    /// Stops the managed game process.
    private final JButton terminateGame = new JButton(i18n("logwindow.terminate_game"));

    /// Exports a JVM stack dump when attachment support exists.
    private final JButton exportDump = new JButton(i18n("logwindow.export_dump"));

    /// Clears retained and visible log rows.
    private final JButton clearLogs = new JButton(i18n("button.clear"));

    /// Non-modal feedback for clipboard actions.
    private final JLabel status = new JLabel(" ");

    /// Prevents asynchronous completions from mutating a disposed panel.
    private boolean disposed;

    /// Creates a production panel with native export notifications.
    ///
    /// @param buffer bounded shared history
    /// @param actions process and desktop side effects
    /// @param backgroundExecutor executor for blocking operations
    /// @param maxLinesChanged persistence callback for line-limit changes
    /// @param alwaysOnTopChanged owning-frame always-on-top callback
    SwingGameLogPanel(
            BoundedGameLogBuffer buffer,
            GameLogWindowActions actions,
            Executor backgroundExecutor,
            IntConsumer maxLinesChanged,
            Consumer<Boolean> alwaysOnTopChanged) {
        this(
                buffer,
                actions,
                backgroundExecutor,
                maxLinesChanged,
                alwaysOnTopChanged,
                new SwingGameLogWindowNotifier());
    }

    /// Creates a panel with injectable desktop and notification boundaries for headless tests.
    ///
    /// @param buffer bounded shared history
    /// @param actions process and desktop side effects
    /// @param backgroundExecutor executor for blocking operations
    /// @param maxLinesChanged persistence callback for line-limit changes
    /// @param alwaysOnTopChanged owning-frame always-on-top callback
    /// @param notifier export outcome presenter
    SwingGameLogPanel(
            BoundedGameLogBuffer buffer,
            GameLogWindowActions actions,
            Executor backgroundExecutor,
            IntConsumer maxLinesChanged,
            Consumer<Boolean> alwaysOnTopChanged,
            GameLogWindowNotifier notifier) {
        super(new BorderLayout(0, 8));
        EdtDispatcher.requireEventDispatchThread();
        this.buffer = Objects.requireNonNull(buffer, "buffer");
        this.actions = Objects.requireNonNull(actions, "actions");
        this.backgroundExecutor = Objects.requireNonNull(backgroundExecutor, "backgroundExecutor");
        this.maxLinesChanged = Objects.requireNonNull(maxLinesChanged, "maxLinesChanged");
        this.alwaysOnTopChanged = Objects.requireNonNull(alwaysOnTopChanged, "alwaysOnTopChanged");
        this.notifier = Objects.requireNonNull(notifier, "notifier");
        this.lineLimit = new JComboBox<>(lineLimitModel(buffer.maxLines()));
        initializeComponents();
        refreshVisibleRows();
        updateLevelLabels();
        processRunning(actions.isProcessRunning());
    }

    /// Appends one log entry and incrementally updates the visible rows.
    ///
    /// @param log entry to retain
    void append(Log log) {
        EdtDispatcher.requireEventDispatchThread();
        int removedRows = buffer.append(Objects.requireNonNull(log, "log"), visibleModel::addElement);
        removeLeadingRows(removedRows);
        updateLevelLabels();
        scrollToTailIfEnabled();
    }

    /// Appends a copied log batch and incrementally updates the visible rows.
    ///
    /// @param logs immutable copied batch in source order
    void appendAll(@Unmodifiable List<Log> logs) {
        EdtDispatcher.requireEventDispatchThread();
        int removedRows = buffer.appendAll(Objects.requireNonNull(logs, "logs"), visibleModel::addElement);
        removeLeadingRows(removedRows);
        updateLevelLabels();
        scrollToTailIfEnabled();
    }

    /// Disables process controls after raw process termination.
    void processExited() {
        EdtDispatcher.requireEventDispatchThread();
        processRunning(false);
    }

    /// Releases component listeners and visible row references while preserving the shared crash-diagnostic buffer.
    void disposePanel() {
        EdtDispatcher.requireEventDispatchThread();
        if (disposed) {
            return;
        }
        disposed = true;
        for (JToggleButton button : levelButtons.values()) {
            for (java.awt.event.ActionListener listener : button.getActionListeners()) {
                button.removeActionListener(listener);
            }
        }
        removeActionListeners(alwaysOnTop);
        removeActionListeners(lineLimit);
        removeActionListeners(autoScroll);
        removeActionListeners(exportLogs);
        removeActionListeners(terminateGame);
        removeActionListeners(exportDump);
        removeActionListeners(clearLogs);
        visibleModel.clear();
    }

    /// Returns the visible row count for deterministic panel tests.
    ///
    /// @return number of filtered rows currently rendered
    int visibleRowCount() {
        EdtDispatcher.requireEventDispatchThread();
        return visibleModel.size();
    }

    /// Returns the text at one visible row for deterministic panel tests.
    ///
    /// @param index visible row index
    /// @return rendered log text
    String visibleText(int index) {
        EdtDispatcher.requireEventDispatchThread();
        return visibleModel.get(index).getLog();
    }

    /// Returns the level filter control for deterministic panel tests.
    ///
    /// @param level filter severity
    /// @return corresponding toggle button
    JToggleButton levelButton(Log4jLevel level) {
        EdtDispatcher.requireEventDispatchThread();
        return Objects.requireNonNull(levelButtons.get(level), "No filter button for " + level);
    }

    /// Returns the auto-scroll control for deterministic panel tests.
    ///
    /// @return automatic tail-following checkbox
    JCheckBox autoScrollControl() {
        EdtDispatcher.requireEventDispatchThread();
        return autoScroll;
    }

    /// Returns the retention-limit control for deterministic panel tests.
    ///
    /// @return line-limit selector
    JComboBox<Integer> lineLimitControl() {
        EdtDispatcher.requireEventDispatchThread();
        return lineLimit;
    }

    /// Returns the export button for deterministic panel tests.
    ///
    /// @return log export button
    JButton exportLogsButton() {
        EdtDispatcher.requireEventDispatchThread();
        return exportLogs;
    }

    /// Returns the terminate button for deterministic panel tests.
    ///
    /// @return process termination button
    JButton terminateGameButton() {
        EdtDispatcher.requireEventDispatchThread();
        return terminateGame;
    }

    /// Returns the stack-dump button for deterministic panel tests.
    ///
    /// @return JVM stack-dump export button
    JButton exportDumpButton() {
        EdtDispatcher.requireEventDispatchThread();
        return exportDump;
    }

    /// Returns the clear button for deterministic panel tests.
    ///
    /// @return retained-log clearing button
    JButton clearLogsButton() {
        EdtDispatcher.requireEventDispatchThread();
        return clearLogs;
    }

    /// Returns the log list for deterministic selection and clipboard tests.
    ///
    /// @return visible selectable log list
    JList<Log> logList() {
        EdtDispatcher.requireEventDispatchThread();
        return logList;
    }

    /// Returns the current non-modal status text for deterministic panel tests.
    ///
    /// @return clipboard or operation feedback text
    String statusText() {
        EdtDispatcher.requireEventDispatchThread();
        return status.getText();
    }

    /// Builds the complete toolbar, log list, and action row.
    private void initializeComponents() {
        setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        setPreferredSize(new Dimension(PREFERRED_SIZE));

        add(createToolbar(), BorderLayout.NORTH);
        configureLogList();
        JScrollPane scrollPane = new JScrollPane(
                logList,
                ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        add(scrollPane, BorderLayout.CENTER);
        add(createActionBar(), BorderLayout.SOUTH);
    }

    /// Creates the retention, always-on-top, and severity controls.
    ///
    /// @return configured top toolbar
    private JComponent createToolbar() {
        JPanel toolbar = new JPanel(new BorderLayout(8, 0));

        JPanel windowControls = new JPanel(new FlowLayout(FlowLayout.LEADING, 6, 0));
        alwaysOnTop.addActionListener(event -> alwaysOnTopChanged.accept(alwaysOnTop.isSelected()));
        lineLimit.setToolTipText(i18n("logwindow.show_lines"));
        lineLimit.addActionListener(this::lineLimitChanged);
        windowControls.add(alwaysOnTop);
        windowControls.add(new JLabel(i18n("logwindow.show_lines")));
        windowControls.add(lineLimit);
        toolbar.add(windowControls, BorderLayout.WEST);

        JPanel filters = new JPanel(new GridLayout(1, FILTER_LEVELS.size(), 4, 0));
        for (Log4jLevel level : FILTER_LEVELS) {
            JToggleButton button = new JToggleButton();
            button.setSelected(true);
            button.setMargin(new Insets(4, 6, 4, 6));
            button.addActionListener(event -> filterChanged(level, button.isSelected()));
            levelButtons.put(level, button);
            filters.add(button);
        }
        toolbar.add(filters, BorderLayout.EAST);
        return toolbar;
    }

    /// Configures selection, rendering, and the Ctrl+C clipboard shortcut.
    private void configureLogList() {
        logList.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        logList.setCellRenderer(new LogCellRenderer());
        logList.setFont(new Font(Font.MONOSPACED, Font.PLAIN, logList.getFont().getSize()));
        logList.getInputMap(JComponent.WHEN_FOCUSED).put(
                KeyStroke.getKeyStroke(KeyEvent.VK_C, InputEvent.CTRL_DOWN_MASK),
                "copy-selected-logs");
        logList.getActionMap().put("copy-selected-logs", new CopySelectedLogsAction());
    }

    /// Creates automatic scrolling, export, termination, dump, and clear controls.
    ///
    /// @return configured bottom action bar
    private JComponent createActionBar() {
        JPanel actionsPanel = new JPanel(new BorderLayout(8, 0));
        actionsPanel.add(status, BorderLayout.CENTER);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.TRAILING, 6, 0));
        autoScroll.addActionListener(event -> scrollToTailIfEnabled());
        exportLogs.addActionListener(event -> exportLogs());
        terminateGame.addActionListener(event -> actions.terminateGame());
        exportDump.addActionListener(event -> exportDump());
        clearLogs.addActionListener(event -> clearLogs());
        buttons.add(autoScroll);
        buttons.add(exportLogs);
        buttons.add(terminateGame);
        buttons.add(exportDump);
        buttons.add(clearLogs);
        actionsPanel.add(buttons, BorderLayout.EAST);
        return actionsPanel;
    }

    /// Applies a selected retention limit and forwards it to persistent settings.
    ///
    /// @param event combo-box selection event
    private void lineLimitChanged(ActionEvent event) {
        @Nullable Object selected = lineLimit.getSelectedItem();
        if (!(selected instanceof Integer value) || value <= 0) {
            return;
        }
        int removedRows = buffer.setMaxLines(value);
        removeLeadingRows(removedRows);
        maxLinesChanged.accept(value);
    }

    /// Applies one severity filter and rebuilds the visible rows.
    ///
    /// @param level severity being toggled
    /// @param shown whether the severity should remain visible
    private void filterChanged(Log4jLevel level, boolean shown) {
        buffer.setLevelShown(level, shown);
        refreshVisibleRows();
        scrollToTailIfEnabled();
    }

    /// Rebuilds visible rows from the bounded filtered snapshot.
    private void refreshVisibleRows() {
        visibleModel.clear();
        for (Log log : buffer.visibleSnapshot()) {
            visibleModel.addElement(log);
        }
    }

    /// Updates the five severity labels with cumulative session counts.
    private void updateLevelLabels() {
        for (Log4jLevel level : FILTER_LEVELS) {
            String suffix = level.name().toLowerCase(Locale.ROOT) + "s";
            Objects.requireNonNull(levelButtons.get(level), "missing level button")
                    .setText(buffer.levelCount(level) + " " + suffix);
        }
    }

    /// Removes leading rows evicted by the bounded history.
    ///
    /// @param rowCount number of currently visible leading rows to remove
    private void removeLeadingRows(int rowCount) {
        for (int i = 0; i < rowCount; i++) {
            if (visibleModel.isEmpty()) {
                throw new IllegalStateException("Visible log model is inconsistent with the bounded history");
            }
            visibleModel.remove(0);
        }
    }

    /// Scrolls to the newest visible row when automatic tail following is enabled.
    private void scrollToTailIfEnabled() {
        if (autoScroll.isSelected() && !visibleModel.isEmpty()) {
            logList.ensureIndexIsVisible(visibleModel.size() - 1);
        }
    }

    /// Copies selected rows in visual order with the established trailing newline.
    private void copySelectedRows() {
        List<Log> selectedLogs = logList.getSelectedValuesList();
        if (selectedLogs.isEmpty()) {
            return;
        }
        StringBuilder text = new StringBuilder();
        for (Log log : selectedLogs) {
            text.append(log.getLog()).append('\n');
        }
        try {
            actions.copyText(text.toString());
            status.setText(i18n("message.copied"));
        } catch (RuntimeException failure) {
            status.setText(i18n("message.error"));
            LOG.warning("Failed to copy game logs", failure);
        }
    }

    /// Starts a background export of the retained log text snapshot.
    private void exportLogs() {
        @Unmodifiable List<String> lines = buffer.textSnapshot();
        startExport(exportLogs, i18n("button.export"), () -> actions.exportLogs(lines));
    }

    /// Starts a background JVM stack-dump export.
    private void exportDump() {
        startExport(exportDump, i18n("logwindow.export_dump"), actions::exportDump);
    }

    /// Clears retained and visible rows while preserving cumulative severity counters.
    private void clearLogs() {
        buffer.clear();
        visibleModel.clear();
    }

    /// Schedules one blocking export operation and marshals its result back to the EDT.
    ///
    /// @param sourceButton button disabled while the operation is pending
    /// @param operationName localized operation name used in failure notifications
    /// @param operation blocking export operation
    private void startExport(JButton sourceButton, String operationName, PathOperation operation) {
        if (disposed) {
            return;
        }
        sourceButton.setEnabled(false);
        try {
            backgroundExecutor.execute(() -> runExport(sourceButton, operationName, operation));
        } catch (RuntimeException failure) {
            sourceButton.setEnabled(true);
            notifier.exportFailed(this, operationName, failure);
        }
    }

    /// Executes one export outside the EDT and reveals a successful result when possible.
    ///
    /// Reveal failure is logged without changing a successful export into a failed export.
    ///
    /// @param sourceButton button disabled while the operation is pending
    /// @param operationName localized operation name used in failure notifications
    /// @param operation blocking export operation
    private void runExport(JButton sourceButton, String operationName, PathOperation operation) {
        @Nullable Path result = null;
        @Nullable Throwable failure = null;
        try {
            result = operation.run();
            try {
                actions.revealFile(result);
            } catch (Exception revealFailure) {
                LOG.warning("Failed to reveal exported game log file " + result, revealFailure);
            }
        } catch (Throwable exportFailure) {
            failure = exportFailure;
            LOG.warning("Failed to export game log data", exportFailure);
        }

        @Nullable Path completedResult = result;
        @Nullable Throwable completedFailure = failure;
        EdtDispatcher.execute(() -> finishExport(sourceButton, operationName, completedResult, completedFailure));
    }

    /// Re-enables one export control and presents its outcome unless the panel has been disposed.
    ///
    /// @param sourceButton button disabled while the operation was pending
    /// @param operationName localized operation name used in failure notifications
    /// @param result exported path, or null after failure
    /// @param failure export failure, or null after success
    private void finishExport(
            JButton sourceButton,
            String operationName,
            @Nullable Path result,
            @Nullable Throwable failure) {
        EdtDispatcher.requireEventDispatchThread();
        if (disposed) {
            return;
        }
        sourceButton.setEnabled(sourceButton != exportDump || actions.canExportDump());
        if (failure != null) {
            notifier.exportFailed(this, operationName, failure);
        } else {
            notifier.exportSucceeded(this, Objects.requireNonNull(result, "successful export path"));
        }
    }

    /// Enables or disables controls that require a live process.
    ///
    /// @param running whether the managed process is still running
    private void processRunning(boolean running) {
        terminateGame.setEnabled(running);
        exportDump.setEnabled(running && actions.canExportDump());
        if (!exportDump.isEnabled()) {
            exportDump.setToolTipText(i18n("logwindow.export_dump.no_dependency"));
        } else {
            exportDump.setToolTipText(null);
        }
    }

    /// Builds a sorted selector model containing the current limit and all standard choices.
    ///
    /// @param currentLimit current positive retention limit
    /// @return selector model with the current value selected
    private static DefaultComboBoxModel<Integer> lineLimitModel(int currentLimit) {
        List<Integer> limits = new ArrayList<>(STANDARD_LINE_LIMITS);
        if (!limits.contains(currentLimit)) {
            limits.add(currentLimit);
            limits.sort(Comparator.naturalOrder());
        }
        DefaultComboBoxModel<Integer> model = new DefaultComboBoxModel<>();
        for (Integer limit : limits) {
            model.addElement(limit);
        }
        model.setSelectedItem(currentLimit);
        return model;
    }

    /// Removes every action listener from a disposable Swing control.
    ///
    /// @param component button, checkbox, or combo box whose owned listeners should be released
    private static void removeActionListeners(JComponent component) {
        if (component instanceof javax.swing.AbstractButton button) {
            for (java.awt.event.ActionListener listener : button.getActionListeners()) {
                button.removeActionListener(listener);
            }
        } else if (component instanceof JComboBox<?> comboBox) {
            for (java.awt.event.ActionListener listener : comboBox.getActionListeners()) {
                comboBox.removeActionListener(listener);
            }
        }
    }

    /// Executes one blocking export and returns its destination.
    @FunctionalInterface
    @NotNullByDefault
    private interface PathOperation {
        /// Runs the export operation.
        ///
        /// @return exported file path
        /// @throws Exception when the export fails
        Path run() throws Exception;
    }

    /// Swing action backing the Ctrl+C selected-row shortcut.
    @NotNullByDefault
    private final class CopySelectedLogsAction extends javax.swing.AbstractAction {
        /// Creates the selected-row clipboard action.
        private CopySelectedLogsAction() {
        }

        /// Copies selected log rows to the system clipboard.
        ///
        /// @param event clipboard action event
        @Override
        public void actionPerformed(ActionEvent event) {
            copySelectedRows();
        }
    }

    /// Renders wrapped severity-colored rows without letting long messages resize the enclosing window.
    @NotNullByDefault
    private static final class LogCellRenderer implements ListCellRenderer<Log> {
        /// Fallback renderer used for standard selection colors and font state.
        private final DefaultListCellRenderer fallback = new DefaultListCellRenderer();

        /// Reusable wrapped text component.
        private final JTextArea text = new JTextArea();

        /// Creates the reusable wrapped renderer.
        private LogCellRenderer() {
            text.setEditable(false);
            text.setLineWrap(true);
            text.setWrapStyleWord(false);
            text.setBorder(BorderFactory.createEmptyBorder(3, 5, 3, 5));
        }

        /// Configures one visible row using selection and severity colors.
        ///
        /// @param list owning list
        /// @param value log entry, or null for an empty renderer row
        /// @param index row index
        /// @param selected whether the row is selected
        /// @param focused whether the row owns keyboard focus
        /// @return configured wrapped text component
        @Override
        public Component getListCellRendererComponent(
                JList<? extends Log> list,
                @Nullable Log value,
                int index,
                boolean selected,
                boolean focused) {
            Component baseline = fallback.getListCellRendererComponent(list, value, index, selected, focused);
            text.setFont(new Font(Font.MONOSPACED, Font.PLAIN, baseline.getFont().getSize()));
            text.setText(value == null ? "" : value.getLog());
            text.setBackground(baseline.getBackground());
            text.setForeground(selected || value == null
                    ? baseline.getForeground()
                    : severityColor(value.getLevel(), baseline.getForeground()));
            int width = Math.max(160, list.getWidth() - 24);
            text.setSize(width, Short.MAX_VALUE);
            return text;
        }

        /// Chooses a restrained readable foreground for one severity.
        ///
        /// @param level entry severity
        /// @param fallbackColor active look-and-feel foreground
        /// @return severity-specific or fallback foreground
        private static Color severityColor(Log4jLevel level, Color fallbackColor) {
            return switch (level) {
                case FATAL, ERROR -> colorOr("Actions.Red", new Color(190, 45, 45));
                case WARN -> colorOr("Actions.Yellow", new Color(170, 105, 0));
                case DEBUG, TRACE -> colorOr("Label.disabledForeground", fallbackColor.darker());
                case INFO, ALL -> fallbackColor;
            };
        }

        /// Resolves a look-and-feel color with a stable fallback.
        ///
        /// @param key UI defaults color key
        /// @param fallbackColor color used when the key is unavailable
        /// @return resolved color
        private static Color colorOr(String key, Color fallbackColor) {
            @Nullable Color color = UIManager.getColor(key);
            return color == null ? fallbackColor : color;
        }
    }
}
