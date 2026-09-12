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
package space.minecraftstl.xyml.ui.swing.page.tasks;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.observable.Subscription;
import space.minecraftstl.xyml.task.TaskExecutionLogEntry;
import space.minecraftstl.xyml.task.TaskExecutionRegistry;
import space.minecraftstl.xyml.task.TaskExecutionSnapshot;
import space.minecraftstl.xyml.task.TaskExecutionStatus;
import space.minecraftstl.xyml.task.TaskExecutionTaskSnapshot;
import space.minecraftstl.xyml.task.TaskExecutionTaskStatus;
import space.minecraftstl.xyml.task.Schedulers;
import space.minecraftstl.xyml.util.i18n.I18n;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;
import space.minecraftstl.xyml.ui.swing.SwingUiDispatcher;

import javax.swing.AbstractAction;
import javax.swing.ActionMap;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.InputMap;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.KeyStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import static space.minecraftstl.xyml.util.i18n.I18n.i18n;

/// Displays top-level task executions in running, completed, and aborted tabs.
///
/// One row is created for one [TaskExecutionSnapshot]. The actual parallel or nested tasks are rendered only after
/// that row is expanded, so the page never turns internal task concurrency into additional top-level records.
@NotNullByDefault
public final class TaskManagerPanel extends JPanel implements AutoCloseable {
    /// Progress-bar maximum used by Swing's integer progress model.
    private static final int PROGRESS_MAXIMUM = 1000;

    /// Stable compact timestamp formatter for task timelines.
    private static final DateTimeFormatter TIMESTAMP_FORMATTER = DateTimeFormatter
            .ofLocalizedDateTime(FormatStyle.SHORT)
            .withLocale(Locale.getDefault())
            .withZone(ZoneId.systemDefault());

    /// Registry supplying immutable execution snapshots.
    private final TaskExecutionRegistry registry;

    /// Three tab contents keyed by their visible lifecycle category.
    private final JPanel runningList = createListPanel();
    private final JPanel completedList = createListPanel();
    private final JPanel abortedList = createListPanel();

    /// Tab container for the three top-level lifecycle views.
    private final JTabbedPane tabs = new JTabbedPane();

    /// Expanded state retained by execution ID while snapshots are refreshed.
    private final Set<UUID> expandedExecutions = new HashSet<>();

    /// Current immutable snapshot set used by the most recent render.
    private @Unmodifiable List<TaskExecutionSnapshot> displayedSnapshots = List.of();

    /// Last registry publication revision rendered on the event dispatch thread.
    private long displayedRevision = -1L;

    /// Registry listener owned by this page.
    private final Subscription registrySubscription;

    /// Prevents queued registry notifications from mutating a closed page.
    private volatile boolean closed;

    /// Creates a task manager connected to one execution registry.
    ///
    /// @param registry registry containing top-level task executions
    public TaskManagerPanel(TaskExecutionRegistry registry) {
        super(new BorderLayout(0, 12));
        this.registry = Objects.requireNonNull(registry, "registry");
        EdtDispatcher.requireEventDispatchThread();
        configureComponents();
        registrySubscription = registry.subscribeVersioned(this::snapshotsChanged);
        renderSnapshots(registry.publication());
    }

    /// Creates the page with the application-wide task registry.
    public TaskManagerPanel() {
        this(TaskExecutionRegistry.global());
    }

    /// Releases the registry listener and clears the rendered rows.
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        registrySubscription.unsubscribe();
        EdtDispatcher.execute(() -> {
            removeAll();
            revalidate();
            repaint();
        });
    }

    /// Creates the fixed page header and lifecycle tabs.
    private void configureComponents() {
        setName("taskManagerPanel");
        setOpaque(false);
        setBorder(BorderFactory.createEmptyBorder(18, 20, 18, 20));

        JLabel heading = new JLabel(i18n("swing.task.manager"));
        heading.setName("taskManagerHeading");
        heading.setFont(heading.getFont().deriveFont(Font.BOLD, heading.getFont().getSize2D() + 3.0F));

        JPanel headingPanel = new JPanel(new BorderLayout());
        headingPanel.setOpaque(false);
        headingPanel.add(heading, BorderLayout.WEST);

        tabs.setName("taskManagerTabs");
        tabs.addTab(i18n("swing.task.tab.running"), new JScrollPane(runningList));
        tabs.addTab(i18n("swing.task.tab.completed"), new JScrollPane(completedList));
        tabs.addTab(i18n("swing.task.tab.aborted"), new JScrollPane(abortedList));
        tabs.getAccessibleContext().setAccessibleName(i18n("swing.task.manager"));

        add(headingPanel, BorderLayout.NORTH);
        add(tabs, BorderLayout.CENTER);
    }

    /// Creates one transparent vertical list that grows with its rows.
    private static JPanel createListPanel() {
        JPanel list = new JPanel();
        list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
        list.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        list.setOpaque(false);
        return list;
    }

    /// Routes one complete registry publication to Swing without splitting one event into internal rows.
    private void snapshotsChanged(TaskExecutionRegistry.Publication publication) {
        SwingUiDispatcher.INSTANCE.dispatchOrRun(() -> {
            if (!closed && publication.revision() >= displayedRevision) {
                renderSnapshots(publication);
            }
        });
    }

    /// Rebuilds the three top-level lists from one immutable registry snapshot.
    private void renderSnapshots(TaskExecutionRegistry.Publication publication) {
        EdtDispatcher.requireEventDispatchThread();
        if (closed || publication.revision() < displayedRevision) {
            return;
        }
        displayedRevision = publication.revision();
        @Unmodifiable List<TaskExecutionSnapshot> snapshots = publication.snapshots();
        displayedSnapshots = List.copyOf(snapshots);
        Set<UUID> retainedIds = new HashSet<>();
        List<TaskExecutionSnapshot> running = new ArrayList<>();
        List<TaskExecutionSnapshot> completed = new ArrayList<>();
        List<TaskExecutionSnapshot> aborted = new ArrayList<>();
        for (TaskExecutionSnapshot snapshot : snapshots) {
            if (snapshot.status().isTerminal()) {
                if (snapshot.status() == TaskExecutionStatus.SUCCEEDED && snapshot.userVisible()) {
                    completed.add(snapshot);
                } else if (snapshot.status() == TaskExecutionStatus.FAILED
                        || snapshot.status() == TaskExecutionStatus.CANCELLED) {
                    aborted.add(snapshot);
                }
            } else if (snapshot.userVisible()) {
                running.add(snapshot);
            }
        }
        Comparator<TaskExecutionSnapshot> newestFirst = Comparator
                .comparing(TaskExecutionSnapshot::startedAt)
                .reversed();
        running.sort(newestFirst);
        completed.sort(Comparator.comparing(TaskManagerPanel::terminalTime).reversed());
        aborted.sort(Comparator.comparing(TaskManagerPanel::terminalTime).reversed());

        renderList(runningList, running, "swing.task.empty.running", retainedIds);
        renderList(completedList, completed, "swing.task.empty.completed", retainedIds);
        renderList(abortedList, aborted, "swing.task.empty.aborted", retainedIds);
        expandedExecutions.retainAll(retainedIds);
        tabs.setTitleAt(0, tabTitle("swing.task.tab.running", running.size()));
        tabs.setTitleAt(1, tabTitle("swing.task.tab.completed", completed.size()));
        tabs.setTitleAt(2, tabTitle("swing.task.tab.aborted", aborted.size()));
        revalidate();
        repaint();
    }

    /// Renders one category without creating rows for internal tasks.
    private void renderList(
            JPanel list,
            List<TaskExecutionSnapshot> snapshots,
            String emptyKey,
            Set<UUID> retainedIds) {
        list.removeAll();
        if (snapshots.isEmpty()) {
            JLabel empty = new JLabel(i18n(emptyKey));
            empty.setName(emptyKey.replace('.', '_'));
            empty.setBorder(BorderFactory.createEmptyBorder(14, 8, 14, 8));
            list.add(empty);
            return;
        }
        for (TaskExecutionSnapshot snapshot : snapshots) {
            retainedIds.add(snapshot.id());
            list.add(createExecutionRow(snapshot));
            list.add(Box.createVerticalStrut(8));
        }
        list.add(Box.createVerticalGlue());
    }

    /// Creates one expandable top-level execution row.
    private JPanel createExecutionRow(TaskExecutionSnapshot snapshot) {
        boolean expanded = expandedExecutions.contains(snapshot.id());
        JPanel row = new JPanel(new BorderLayout(0, 8));
        row.setName("taskExecutionRow-" + snapshot.id());
        row.setOpaque(true);
        row.setBackground(rowBackground(snapshot.status()));
        row.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(rowBorder(snapshot.status())),
                BorderFactory.createEmptyBorder(10, 12, 10, 12)));
        row.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseReleased(MouseEvent event) {
                if (event.getSource() == row
                        && event.getButton() == MouseEvent.BUTTON1
                        && event.getClickCount() == 1) {
                    toggleExpanded(snapshot.id());
                }
            }
        });

        JPanel header = new JPanel(new BorderLayout(10, 0));
        header.setOpaque(false);
        // Treat unused header space as part of the top-level disclosure target. Child controls keep their own
        // handlers, so a click on the disclosure or cancellation button is not toggled a second time.
        header.addMouseListener(toggleOnClick(snapshot.id()));
        JButton disclosure = new JButton(expanded ? "v" : ">");
        disclosure.setName("taskExecutionDisclosure");
        disclosure.setToolTipText(expanded
                ? i18n("swing.task.hide_details")
                : i18n("swing.task.show_details"));
        disclosure.setMargin(new java.awt.Insets(0, 4, 0, 4));
        disclosure.addActionListener(event -> toggleExpanded(snapshot.id()));
        header.add(disclosure, BorderLayout.WEST);

        JPanel titlePanel = new JPanel();
        titlePanel.setOpaque(false);
        titlePanel.setLayout(new BoxLayout(titlePanel, BoxLayout.Y_AXIS));
        configureDetailsToggle(titlePanel, snapshot.id(), snapshot.title());
        JLabel title = new JLabel(snapshot.title());
        title.setName("taskExecutionTitle");
        title.setFont(title.getFont().deriveFont(Font.BOLD));
        JLabel status = new JLabel(statusText(snapshot.status()));
        status.setName("taskExecutionStatus");
        MouseAdapter toggleOnClick = toggleOnClick(snapshot.id());
        title.addMouseListener(toggleOnClick);
        status.addMouseListener(toggleOnClick);
        titlePanel.add(title);
        titlePanel.add(status);
        header.add(titlePanel, BorderLayout.CENTER);

        JPanel actionPanel = new JPanel(new FlowLayout(FlowLayout.TRAILING, 8, 0));
        actionPanel.setOpaque(false);
        JProgressBar progressBar = createProgressBar(snapshot);
        progressBar.addMouseListener(toggleOnClick(snapshot.id()));
        actionPanel.add(progressBar);
        actionPanel.addMouseListener(toggleOnClick(snapshot.id()));
        if (!snapshot.status().isTerminal() && snapshot.cancelable()) {
            JButton cancel = new JButton(i18n("swing.task.button.cancel"));
            cancel.setName("taskExecutionCancel");
            cancel.addActionListener(event -> {
                cancel.setEnabled(false);
                Schedulers.io().execute(() -> registry.requestCancellation(snapshot.id()));
            });
            actionPanel.add(cancel);
        }
        header.add(actionPanel, BorderLayout.EAST);
        row.add(header, BorderLayout.NORTH);

        if (expanded) {
            row.add(createDetails(snapshot), BorderLayout.CENTER);
        }
        return row;
    }

    /// Makes one title region a mouse and keyboard-accessible details toggle.
    ///
    /// @param titlePanel title region receiving focus
    /// @param executionId execution whose details are toggled
    /// @param title accessible workflow title
    private void configureDetailsToggle(JPanel titlePanel, UUID executionId, String title) {
        titlePanel.setName("taskExecutionDetails");
        titlePanel.setFocusable(true);
        titlePanel.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        titlePanel.getAccessibleContext().setAccessibleName(title);
        titlePanel.getAccessibleContext().setAccessibleDescription(i18n("swing.task.show_details"));
        InputMap inputMap = titlePanel.getInputMap(JComponent.WHEN_FOCUSED);
        ActionMap actionMap = titlePanel.getActionMap();
        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "toggle-task-details");
        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0), "toggle-task-details");
        actionMap.put("toggle-task-details", new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent event) {
                toggleExpanded(executionId);
            }
        });
        titlePanel.addMouseListener(toggleOnClick(executionId));
    }

    /// Creates a single-click handler for one top-level record's details.
    ///
    /// @param executionId execution whose details are toggled
    /// @return mouse handler bound to the execution ID
    private MouseAdapter toggleOnClick(UUID executionId) {
        return new MouseAdapter() {
            @Override
            public void mouseReleased(MouseEvent event) {
                if (event.getButton() == MouseEvent.BUTTON1 && event.getClickCount() == 1) {
                    toggleExpanded(executionId);
                }
            }
        };
    }

    /// Builds the actual task list and top-level timeline for one expanded workflow.
    private JPanel createDetails(TaskExecutionSnapshot snapshot) {
        JPanel details = new JPanel();
        details.setOpaque(false);
        details.setLayout(new BoxLayout(details, BoxLayout.Y_AXIS));
        details.setBorder(BorderFactory.createEmptyBorder(4, 34, 0, 0));

        JLabel tasksHeading = new JLabel(i18n("swing.task.details.tasks"));
        tasksHeading.setFont(tasksHeading.getFont().deriveFont(Font.BOLD));
        details.add(tasksHeading);
        Map<UUID, Integer> depths = taskDepths(snapshot.tasks());
        for (TaskExecutionTaskSnapshot task : snapshot.tasks()) {
            details.add(createTaskDetail(task, depths.getOrDefault(task.id(), 0)));
            details.add(Box.createVerticalStrut(4));
        }
        if (snapshot.tasks().isEmpty()) {
            details.add(new JLabel(i18n("swing.task.details.no_tasks")));
        }

        JLabel timelineHeading = new JLabel(i18n("swing.task.details.timeline"));
        timelineHeading.setFont(timelineHeading.getFont().deriveFont(Font.BOLD));
        timelineHeading.setBorder(BorderFactory.createEmptyBorder(8, 0, 4, 0));
        details.add(timelineHeading);
        String timeline = formatLogs(snapshot.logs());
        if (snapshot.failure() != null && !snapshot.failure().isBlank()) {
            timeline = timeline + (timeline.isEmpty() ? "" : "\n")
                    + i18n("swing.task.details.error") + ":\n" + snapshot.failure();
        }
        JTextArea timelineArea = readOnlyLogArea(timeline.isBlank()
                ? i18n("swing.task.details.no_log")
                : timeline);
        details.add(new JScrollPane(timelineArea));
        return details;
    }

    /// Creates one actual-task detail row, indented according to its parent task.
    private JPanel createTaskDetail(TaskExecutionTaskSnapshot task, int depth) {
        JPanel panel = new JPanel(new BorderLayout(8, 2));
        panel.setOpaque(false);
        panel.setBorder(BorderFactory.createEmptyBorder(2, depth * 18, 2, 0));

        JPanel labels = new JPanel();
        labels.setOpaque(false);
        labels.setLayout(new BoxLayout(labels, BoxLayout.Y_AXIS));
        JLabel name = new JLabel(task.name());
        name.setName("taskDetailName");
        JLabel phase = new JLabel(taskStageText(task));
        phase.setName("taskDetailStage");
        labels.add(name);
        labels.add(phase);
        panel.add(labels, BorderLayout.CENTER);

        JPanel right = new JPanel(new FlowLayout(FlowLayout.TRAILING, 6, 0));
        right.setOpaque(false);
        JProgressBar progress = createTaskProgressBar(task);
        right.add(progress);
        right.add(new JLabel(taskStatusText(task.status())));
        panel.add(right, BorderLayout.EAST);

        String taskLogText = formatLogs(task.logs());
        if (task.failure() != null && !task.failure().isBlank()) {
            taskLogText = taskLogText + (taskLogText.isBlank() ? "" : "\n")
                    + i18n("swing.task.details.error") + ":\n" + task.failure();
        }
        if (!taskLogText.isBlank()) {
            JTextArea log = readOnlyLogArea(taskLogText);
            log.setRows(Math.min(4, Math.max(1, task.logs().size())));
            panel.add(new JScrollPane(log), BorderLayout.SOUTH);
        }
        return panel;
    }

    /// Computes indentation levels from the flattened parent-ID task representation.
    private static Map<UUID, Integer> taskDepths(@Unmodifiable List<TaskExecutionTaskSnapshot> tasks) {
        Map<UUID, UUID> parents = new HashMap<>();
        for (TaskExecutionTaskSnapshot task : tasks) {
            if (task.parentId() != null) {
                parents.put(task.id(), task.parentId());
            }
        }
        Map<UUID, Integer> depths = new HashMap<>();
        for (TaskExecutionTaskSnapshot task : tasks) {
            int depth = 0;
            @Nullable UUID parent = parents.get(task.id());
            Set<UUID> visited = new HashSet<>();
            while (parent != null && visited.add(parent)) {
                depth++;
                parent = parents.get(parent);
            }
            depths.put(task.id(), depth);
        }
        return depths;
    }

    /// Creates a determinate or indeterminate aggregate progress bar.
    private static JProgressBar createProgressBar(TaskExecutionSnapshot snapshot) {
        JProgressBar progress = new JProgressBar(0, PROGRESS_MAXIMUM);
        progress.setName("taskExecutionProgress");
        progress.setPreferredSize(new Dimension(120, 14));
        if (snapshot.progress().isPresent()) {
            progress.setValue(toProgressValue(snapshot.progress().getAsDouble()));
            progress.setIndeterminate(false);
        } else {
            progress.setIndeterminate(!snapshot.status().isTerminal());
            progress.setValue(snapshot.status() == TaskExecutionStatus.SUCCEEDED ? PROGRESS_MAXIMUM : 0);
        }
        progress.setToolTipText(i18n("swing.task.progress_name"));
        return progress;
    }

    /// Creates a task-level determinate or indeterminate progress bar.
    private static JProgressBar createTaskProgressBar(TaskExecutionTaskSnapshot task) {
        JProgressBar progress = new JProgressBar(0, PROGRESS_MAXIMUM);
        progress.setPreferredSize(new Dimension(90, 12));
        if (task.progress().isPresent()) {
            progress.setValue(toProgressValue(task.progress().getAsDouble()));
        } else {
            progress.setIndeterminate(!isTaskTerminal(task.status()));
            progress.setValue(task.status() == TaskExecutionTaskStatus.SUCCEEDED ? PROGRESS_MAXIMUM : 0);
        }
        return progress;
    }

    /// Converts a normalized progress value to the stable Swing range.
    private static int toProgressValue(double progress) {
        return (int) Math.round(Math.max(0.0D, Math.min(1.0D, progress)) * PROGRESS_MAXIMUM);
    }

    /// Returns whether an actual task has reached a terminal state.
    private static boolean isTaskTerminal(TaskExecutionTaskStatus status) {
        return status == TaskExecutionTaskStatus.SUCCEEDED
                || status == TaskExecutionTaskStatus.FAILED
                || status == TaskExecutionTaskStatus.CANCELLED;
    }

    /// Toggles one execution row and rerenders all lists using the latest immutable snapshots.
    private void toggleExpanded(UUID executionId) {
        if (!expandedExecutions.add(executionId)) {
            expandedExecutions.remove(executionId);
        }
        renderSnapshots(new TaskExecutionRegistry.Publication(displayedRevision, displayedSnapshots));
    }

    /// Formats one timeline as timestamp, event, and already-redacted message lines.
    private static String formatLogs(@Unmodifiable List<TaskExecutionLogEntry> logs) {
        StringBuilder output = new StringBuilder();
        for (TaskExecutionLogEntry log : logs) {
            if (output.length() > 0) {
                output.append('\n');
            }
            output.append(TIMESTAMP_FORMATTER.format(log.timestamp()))
                    .append("  ")
                    .append(log.event())
                    .append("  ")
                    .append(log.message());
        }
        return output.toString();
    }

    /// Creates a compact read-only log viewer that preserves selectable text.
    private static JTextArea readOnlyLogArea(String text) {
        JTextArea area = new JTextArea(text);
        area.setEditable(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setRows(Math.min(8, Math.max(2, text.split("\\R", -1).length)));
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, area.getFont().getSize()));
        area.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
        return area;
    }

    /// Returns a localized top-level state label.
    private static String statusText(TaskExecutionStatus status) {
        return switch (status) {
            case WAITING -> i18n("swing.task.status.waiting");
            case RUNNING -> i18n("swing.task.status.running");
            case CANCELLING -> i18n("swing.task.status.cancelling");
            case SUCCEEDED -> i18n("swing.task.status.succeeded");
            case FAILED -> i18n("swing.task.status.failed");
            case CANCELLED -> i18n("swing.task.status.cancelled");
        };
    }

    /// Returns a localized actual-task state label.
    private static String taskStatusText(TaskExecutionTaskStatus status) {
        return switch (status) {
            case WAITING -> i18n("swing.task.status.waiting");
            case RUNNING -> i18n("swing.task.status.running");
            case SUCCEEDED -> i18n("swing.task.status.succeeded");
            case FAILED -> i18n("swing.task.status.failed");
            case CANCELLED -> i18n("swing.task.status.cancelled");
        };
    }

    /// Returns stage text with a localized fallback when no stage is assigned.
    private static String taskStageText(TaskExecutionTaskSnapshot task) {
        String stage = task.stage();
        String displayStage = stage == null || stage.isBlank()
                ? i18n("swing.task.details.unknown_stage")
                : I18n.hasKey(stage) ? i18n(stage) : stage;
        return i18n("swing.task.details.stage") + ": "
                + displayStage;
    }

    /// Returns a localized tab title with the current top-level count.
    private static String tabTitle(String key, int count) {
        return i18n(key) + " (" + count + ")";
    }

    /// Returns a stable terminal or start timestamp for newest-first sorting.
    private static Instant terminalTime(TaskExecutionSnapshot snapshot) {
        return snapshot.endedAt() == null ? snapshot.startedAt() : snapshot.endedAt();
    }

    /// Selects a restrained background for one aggregate state.
    private static Color rowBackground(TaskExecutionStatus status) {
        return switch (status) {
            case FAILED, CANCELLED -> new Color(255, 242, 242);
            case SUCCEEDED -> new Color(242, 250, 242);
            default -> new Color(248, 249, 252);
        };
    }

    /// Selects a matching border accent for one aggregate state.
    private static Color rowBorder(TaskExecutionStatus status) {
        return switch (status) {
            case FAILED, CANCELLED -> new Color(220, 150, 150);
            case SUCCEEDED -> new Color(150, 205, 155);
            default -> new Color(190, 198, 210);
        };
    }
}
