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
import space.minecraftstl.xyml.ui.swing.SwingTransparency;
import space.minecraftstl.xyml.ui.swing.SwingUiDispatcher;
import space.minecraftstl.xyml.ui.swing.page.instances.management.ViewportTrackingPanel;

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
import javax.swing.JViewport;
import javax.swing.KeyStroke;
import javax.swing.Scrollable;
import javax.swing.SwingConstants;
import javax.swing.UIManager;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
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

    /// Horizontal indentation applied to every nested task level.
    private static final int TASK_INDENT = 18;

    /// Smallest readable title-column width before the details viewport must scroll horizontally.
    private static final int MIN_TASK_TITLE_WIDTH = 180;

    /// Fallback title-column width used before the page receives its first real window allocation.
    private static final int DEFAULT_TASK_TITLE_WIDTH = 360;

    /// Smallest log viewport width retained for short windows and narrow themes.
    private static final int MIN_LOG_WIDTH = 260;

    /// Maximum log viewport width so long lines remain inspectable without dominating the task row.
    private static final int MAX_LOG_WIDTH = 520;

    /// Approximate monospaced columns used to keep a log's natural size bounded before scrolling.
    private static final int LOG_COLUMNS = 72;

    /// Horizontal space consumed by list and row insets when deriving the title column from the page width.
    private static final int TASK_CONTENT_INSETS = 44;

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

    /// Page width used by the most recent title-column calculation.
    private int renderedLayoutWidth = -1;

    /// Prevents a width refresh from recursively rebuilding the component tree during layout.
    private boolean refreshingLayout;

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
        SwingTransparency.revealBackgroundThroughTabs(tabs);
        tabs.addTab(
                i18n("swing.task.tab.running"),
                createListScrollPane(runningList, "taskManagerRunningScroll"));
        tabs.addTab(
                i18n("swing.task.tab.completed"),
                createListScrollPane(completedList, "taskManagerCompletedScroll"));
        tabs.addTab(
                i18n("swing.task.tab.aborted"),
                createListScrollPane(abortedList, "taskManagerAbortedScroll"));
        tabs.getAccessibleContext().setAccessibleName(i18n("swing.task.manager"));

        add(headingPanel, BorderLayout.NORTH);
        add(tabs, BorderLayout.CENTER);
        addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent event) {
                refreshLayoutForWidth();
            }
        });
    }

    /// Creates one transparent vertical list that grows with its rows.
    private static JPanel createListPanel() {
        JPanel list = new ViewportTrackingPanel(new BorderLayout());
        // BoxLayout needs the final panel instance as its target, so install it after the scroll-aware panel exists.
        list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
        list.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        list.setOpaque(false);
        return list;
    }

    /// Creates a borderless list scroll surface that reveals the launcher background.
    ///
    /// @param list transparent list content
    /// @param name stable component name
    /// @return transparent list scroll pane
    private static JScrollPane createListScrollPane(JPanel list, String name) {
        JScrollPane scrollPane = new JScrollPane(Objects.requireNonNull(list, "list"));
        SwingTransparency.revealBackgroundThroughScrollPane(scrollPane);
        scrollPane.setName(Objects.requireNonNull(name, "name"));
        scrollPane.setBorder(BorderFactory.createEmptyBorder());
        scrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scrollPane.getVerticalScrollBar().setOpaque(false);
        return scrollPane;
    }

    /// Rebuilds title columns after the task page receives a new window width.
    private void refreshLayoutForWidth() {
        EdtDispatcher.requireEventDispatchThread();
        int width = getWidth();
        if (refreshingLayout || closed || displayedRevision < 0L || width <= 0 || width == renderedLayoutWidth) {
            return;
        }
        renderedLayoutWidth = width;
        refreshingLayout = true;
        try {
            renderSnapshots(new TaskExecutionRegistry.Publication(displayedRevision, displayedSnapshots));
        } finally {
            refreshingLayout = false;
        }
    }

    /// Lays out the page and refreshes title columns when a parent assigns a new size without a peer event.
    @Override
    public void doLayout() {
        super.doLayout();
        if (!refreshingLayout) {
            int width = getWidth();
            if (width > 0 && width != renderedLayoutWidth) {
                refreshLayoutForWidth();
                super.doLayout();
            }
        }
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
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.setOpaque(false);
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

        JButton disclosure = new JButton(expanded ? "v" : ">");
        disclosure.setName("taskExecutionDisclosure");
        disclosure.setToolTipText(expanded
                ? i18n("swing.task.hide_details")
                : i18n("swing.task.show_details"));
        disclosure.setMargin(new Insets(0, 4, 0, 4));
        disclosure.addActionListener(event -> toggleExpanded(snapshot.id()));
        Dimension disclosurePreferredSize = disclosure.getPreferredSize();
        disclosure.setMaximumSize(new Dimension(disclosurePreferredSize.width, Integer.MAX_VALUE));

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
        Dimension actionPreferredSize = actionPanel.getPreferredSize();
        actionPanel.setMaximumSize(new Dimension(actionPreferredSize.width, Integer.MAX_VALUE));

        int titleWidth = calculateTaskTitleWidth(disclosure.getPreferredSize().width, actionPreferredSize.width);
        JPanel header = new JPanel();
        header.setLayout(new BoxLayout(header, BoxLayout.X_AXIS));
        header.setAlignmentX(Component.LEFT_ALIGNMENT);
        header.setOpaque(false);
        // Treat unused header space as part of the top-level disclosure target. Child controls keep their own
        // handlers, so a click on the disclosure or cancellation button is not toggled a second time.
        header.addMouseListener(toggleOnClick(snapshot.id()));
        header.add(disclosure);
        header.add(Box.createHorizontalStrut(10));

        JPanel titlePanel = new JPanel();
        titlePanel.setOpaque(false);
        titlePanel.setLayout(new BoxLayout(titlePanel, BoxLayout.Y_AXIS));
        titlePanel.setAlignmentY(Component.TOP_ALIGNMENT);
        configureDetailsToggle(titlePanel, snapshot.id(), snapshot.title());
        JTextArea title = createWrappedTitleArea(snapshot.title(), titleWidth, "taskExecutionTitle");
        title.setFont(title.getFont().deriveFont(Font.BOLD));
        setWrappedTitleSize(title, titleWidth);
        JLabel status = new JLabel(statusText(snapshot.status()));
        status.setName("taskExecutionStatus");
        status.setMaximumSize(new Dimension(titleWidth, status.getPreferredSize().height));
        MouseAdapter toggleOnClick = toggleOnClick(snapshot.id());
        title.addMouseListener(toggleOnClick);
        status.addMouseListener(toggleOnClick);
        titlePanel.add(title);
        titlePanel.add(status);
        setFixedWidth(titlePanel, titleWidth);
        header.add(titlePanel);
        header.add(Box.createHorizontalGlue());
        actionPanel.setAlignmentY(Component.TOP_ALIGNMENT);
        header.add(actionPanel);
        row.add(header, BorderLayout.NORTH);

        if (expanded) {
            row.add(createDetails(snapshot, titleWidth), BorderLayout.CENTER);
        }
        return row;
    }

    /// Computes the main title column from the currently allocated task-page width.
    ///
    /// @param disclosureWidth disclosure control width
    /// @param actionWidth progress and cancellation control width
    /// @return stable title width shared by the workflow and its actual tasks
    private int calculateTaskTitleWidth(int disclosureWidth, int actionWidth) {
        int pageWidth = getWidth() > 0 ? getWidth() : tabs.getWidth();
        if (pageWidth <= 0) {
            return DEFAULT_TASK_TITLE_WIDTH;
        }
        Insets panelInsets = getInsets();
        int availableWidth = pageWidth - panelInsets.left - panelInsets.right - TASK_CONTENT_INSETS;
        int calculatedWidth = availableWidth - disclosureWidth - actionWidth - 10;
        return calculatedWidth > 0 ? calculatedWidth : MIN_TASK_TITLE_WIDTH;
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
    private JScrollPane createDetails(TaskExecutionSnapshot snapshot, int titleWidth) {
        TaskDetailsContentPanel details = new TaskDetailsContentPanel();
        details.setAlignmentX(Component.LEFT_ALIGNMENT);
        details.setBorder(BorderFactory.createEmptyBorder(4, 34, 0, 0));

        JLabel tasksHeading = new JLabel(i18n("swing.task.details.tasks"));
        tasksHeading.setFont(tasksHeading.getFont().deriveFont(Font.BOLD));
        tasksHeading.setAlignmentX(Component.LEFT_ALIGNMENT);
        details.add(tasksHeading);
        Map<UUID, Integer> depths = taskDepths(snapshot.tasks());
        for (TaskExecutionTaskSnapshot task : snapshot.tasks()) {
            details.add(createTaskDetail(task, depths.getOrDefault(task.id(), 0), titleWidth));
            details.add(Box.createVerticalStrut(4));
        }
        if (snapshot.tasks().isEmpty()) {
            details.add(new JLabel(i18n("swing.task.details.no_tasks")));
        }

        JLabel timelineHeading = new JLabel(i18n("swing.task.details.timeline"));
        timelineHeading.setFont(timelineHeading.getFont().deriveFont(Font.BOLD));
        timelineHeading.setBorder(BorderFactory.createEmptyBorder(8, 0, 4, 0));
        timelineHeading.setAlignmentX(Component.LEFT_ALIGNMENT);
        details.add(timelineHeading);
        String timeline = formatLogs(snapshot.logs());
        if (snapshot.failure() != null && !snapshot.failure().isBlank()) {
            timeline = timeline + (timeline.isEmpty() ? "" : "\n")
                    + i18n("swing.task.details.error") + ":\n" + snapshot.failure();
        }
        JTextArea timelineArea = readOnlyLogArea(timeline.isBlank()
                ? i18n("swing.task.details.no_log")
                : timeline);
        details.add(createLogScrollPane(timelineArea, logWidth(titleWidth)));

        JScrollPane scrollPane = new JScrollPane(details);
        SwingTransparency.revealBackgroundThroughScrollPane(scrollPane);
        scrollPane.setName("taskExecutionDetailsScroll");
        scrollPane.setBorder(BorderFactory.createEmptyBorder());
        scrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        scrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_NEVER);
        scrollPane.getHorizontalScrollBar().setOpaque(false);
        scrollPane.getHorizontalScrollBar().setUnitIncrement(18);
        Dimension detailsPreferredSize = details.getPreferredSize();
        scrollPane.setPreferredSize(new Dimension(
                Math.max(1, titleWidth),
                Math.max(1, detailsPreferredSize.height)));
        scrollPane.setAlignmentX(Component.LEFT_ALIGNMENT);
        return scrollPane;
    }

    /// Creates one actual-task detail row, indented according to its parent task.
    private JPanel createTaskDetail(TaskExecutionTaskSnapshot task, int depth, int titleWidth) {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.setOpaque(false);
        panel.setBorder(BorderFactory.createEmptyBorder(2, depth * TASK_INDENT, 2, 0));

        JPanel summary = new JPanel();
        summary.setLayout(new BoxLayout(summary, BoxLayout.X_AXIS));
        summary.setAlignmentX(Component.LEFT_ALIGNMENT);
        summary.setOpaque(false);
        JPanel labels = new JPanel();
        labels.setOpaque(false);
        labels.setLayout(new BoxLayout(labels, BoxLayout.Y_AXIS));
        labels.setAlignmentX(Component.LEFT_ALIGNMENT);
        JTextArea name = createWrappedTitleArea(task.name(), titleWidth, "taskDetailName");
        JLabel phase = new JLabel(taskStageText(task));
        phase.setName("taskDetailStage");
        labels.add(name);
        labels.add(phase);
        setFixedWidth(labels, titleWidth);
        summary.add(labels);
        summary.add(Box.createHorizontalGlue());

        JPanel right = new JPanel(new FlowLayout(FlowLayout.TRAILING, 6, 0));
        right.setOpaque(false);
        right.setAlignmentY(Component.TOP_ALIGNMENT);
        JProgressBar progress = createTaskProgressBar(task);
        right.add(progress);
        right.add(new JLabel(taskStatusText(task.status())));
        Dimension rightPreferredSize = right.getPreferredSize();
        right.setMaximumSize(new Dimension(rightPreferredSize.width, Integer.MAX_VALUE));
        summary.add(right);
        panel.add(summary);

        String taskLogText = formatLogs(task.logs());
        if (task.failure() != null && !task.failure().isBlank()) {
            taskLogText = taskLogText + (taskLogText.isBlank() ? "" : "\n")
                    + i18n("swing.task.details.error") + ":\n" + task.failure();
        }
        if (!taskLogText.isBlank()) {
            JTextArea log = readOnlyLogArea(taskLogText);
            log.setRows(Math.min(4, Math.max(1, task.logs().size())));
            panel.add(createLogScrollPane(log, logWidth(titleWidth)));
        }
        return panel;
    }

    /// Creates one transparent, fixed-width title area that wraps only at word boundaries.
    ///
    /// @param text title text
    /// @param width shared title-column width
    /// @param name stable component name
    /// @return wrapped title text component
    private static JTextArea createWrappedTitleArea(String text, int width, String name) {
        JTextArea area = new JTextArea(Objects.requireNonNull(text, "text"));
        area.setName(Objects.requireNonNull(name, "name"));
        area.setEditable(false);
        area.setFocusable(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setOpaque(false);
        area.setBorder(BorderFactory.createEmptyBorder());
        area.setRows(0);
        area.setColumns(0);
        setWrappedTitleSize(area, width);
        return area;
    }

    /// Re-measures one wrapped title after its font or width changes.
    ///
    /// @param area wrapped title text area
    /// @param width shared title-column width
    private static void setWrappedTitleSize(JTextArea area, int width) {
        int resolvedWidth = Math.max(1, width);
        area.setSize(resolvedWidth, Short.MAX_VALUE);
        Dimension measured = area.getPreferredSize();
        int resolvedHeight = Math.max(1, measured.height);
        Dimension fixedSize = new Dimension(resolvedWidth, resolvedHeight);
        area.setMinimumSize(fixedSize);
        area.setPreferredSize(fixedSize);
        area.setMaximumSize(fixedSize);
    }

    /// Constrains a title column to a stable width while preserving its measured height.
    ///
    /// @param component title column to constrain
    /// @param width desired width
    private static void setFixedWidth(JComponent component, int width) {
        Dimension measured = component.getPreferredSize();
        Dimension minimum = new Dimension(Math.max(1, width), measured.height);
        component.setMinimumSize(minimum);
        component.setPreferredSize(minimum);
        component.setMaximumSize(new Dimension(Math.max(1, width), Integer.MAX_VALUE));
    }

    /// Keeps logs narrower than the task title while retaining a usable minimum viewport.
    ///
    /// @param titleWidth shared task title width
    /// @return constrained log viewport width
    private static int logWidth(int titleWidth) {
        return Math.max(MIN_LOG_WIDTH, Math.min(MAX_LOG_WIDTH, Math.max(1, titleWidth - 24)));
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
        progress.setOpaque(false);
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
        progress.setOpaque(false);
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
        area.setFocusable(true);
        area.setLineWrap(false);
        area.setWrapStyleWord(false);
        area.setOpaque(false);
        area.setBackground(logSurfaceColor());
        area.setForeground(logTextColor());
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, area.getFont().getSize()));
        area.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
        area.setColumns(LOG_COLUMNS);
        area.setRows(Math.min(8, Math.max(2, text.split("\\R", -1).length)));
        area.setCaretPosition(0);
        return area;
    }

    /// Wraps one log area in a transparent scroll surface with a themed translucent viewport.
    ///
    /// @param area selectable read-only log area
    /// @param width constrained log viewport width
    /// @return configured log scroll pane
    private static JScrollPane createLogScrollPane(JTextArea area, int width) {
        JScrollPane scrollPane = new JScrollPane(Objects.requireNonNull(area, "area"));
        SwingTransparency.revealBackgroundThroughScrollPane(scrollPane);
        scrollPane.setBorder(BorderFactory.createLineBorder(logBorderColor()));
        scrollPane.setName("taskLogScroll");
        scrollPane.setOpaque(false);
        scrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        scrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
        JViewport viewport = scrollPane.getViewport();
        viewport.setOpaque(true);
        viewport.setBackground(logSurfaceColor());
        scrollPane.getVerticalScrollBar().setOpaque(false);
        scrollPane.getHorizontalScrollBar().setOpaque(false);
        scrollPane.getHorizontalScrollBar().setUnitIncrement(18);
        Dimension measured = scrollPane.getPreferredSize();
        int resolvedWidth = Math.max(1, width);
        scrollPane.setMinimumSize(new Dimension(Math.min(MIN_LOG_WIDTH, resolvedWidth), measured.height));
        scrollPane.setPreferredSize(new Dimension(resolvedWidth, measured.height));
        scrollPane.setMaximumSize(new Dimension(resolvedWidth, Integer.MAX_VALUE));
        scrollPane.setAlignmentX(Component.LEFT_ALIGNMENT);
        return scrollPane;
    }

    /// Returns a translucent theme surface for log readability without hiding the launcher background.
    ///
    /// @return panel background with controlled alpha
    private static Color logSurfaceColor() {
        @Nullable Color base = UIManager.getColor("Panel.background");
        if (base == null) {
            base = UIManager.getColor("Button.background");
        }
        Color resolved = base == null ? new Color(32, 32, 32) : base;
        return new Color(resolved.getRed(), resolved.getGreen(), resolved.getBlue(), 166);
    }

    /// Resolves a foreground that remains readable against the current theme surface.
    ///
    /// @return theme-aware log text color
    private static Color logTextColor() {
        @Nullable Color surface = UIManager.getColor("Panel.background");
        if (surface == null) {
            surface = UIManager.getColor("Button.background");
        }
        Color resolvedSurface = surface == null ? new Color(32, 32, 32) : surface;
        @Nullable Color foreground = UIManager.getColor("TextArea.foreground");
        if (foreground == null) {
            foreground = UIManager.getColor("Label.foreground");
        }
        if (foreground == null || colorDistance(foreground, resolvedSurface) < 90) {
            return contrastingTextColor(resolvedSurface);
        }
        return foreground;
    }

    /// Chooses a subtle themed border for the translucent log surface.
    ///
    /// @return translucent border color
    private static Color logBorderColor() {
        @Nullable Color border = UIManager.getColor("Component.borderColor");
        if (border == null) {
            border = UIManager.getColor("Separator.foreground");
        }
        Color resolved = border == null ? new Color(128, 128, 128) : border;
        return new Color(resolved.getRed(), resolved.getGreen(), resolved.getBlue(), 120);
    }

    /// Computes a compact RGB distance used to guard against low-contrast theme combinations.
    ///
    /// @param first first color
    /// @param second second color
    /// @return sum of absolute RGB channel differences
    private static int colorDistance(Color first, Color second) {
        return Math.abs(first.getRed() - second.getRed())
                + Math.abs(first.getGreen() - second.getGreen())
                + Math.abs(first.getBlue() - second.getBlue());
    }

    /// Selects a high-contrast text color for one theme surface.
    ///
    /// @param surface surface color
    /// @return black or white text color
    private static Color contrastingTextColor(Color surface) {
        int luminance = surface.getRed() * 299
                + surface.getGreen() * 587
                + surface.getBlue() * 114;
        return luminance >= 128_000 ? Color.BLACK : Color.WHITE;
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

    /// Selects a themed translucent border accent for one aggregate state.
    ///
    /// The row itself remains transparent so the launcher background stays visible; only this narrow state cue is
    /// painted around the record.
    ///
    /// @param status top-level execution state
    /// @return themed translucent border color
    private static Color rowBorder(TaskExecutionStatus status) {
        String key = switch (status) {
            case FAILED, CANCELLED -> "Actions.Red";
            case SUCCEEDED -> "Actions.Green";
            default -> "Component.borderColor";
        };
        @Nullable Color border = UIManager.getColor(key);
        if (border == null) {
            border = UIManager.getColor("Separator.foreground");
        }
        Color resolved = border == null ? new Color(128, 128, 128) : border;
        return new Color(resolved.getRed(), resolved.getGreen(), resolved.getBlue(), 150);
    }

    /// Scrollable details content that fills the viewport until nested indentation needs extra width.
    @NotNullByDefault
    private static final class TaskDetailsContentPanel extends JPanel implements Scrollable {
        /// Creates transparent vertically stacked task details.
        private TaskDetailsContentPanel() {
            super();
            setOpaque(false);
            setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        }

        /// Returns the natural content size for the enclosing details viewport.
        ///
        /// @return preferred scrollable viewport size
        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        /// Returns a compact horizontal or vertical scroll increment.
        ///
        /// @param visibleRect current viewport rectangle
        /// @param orientation scroll orientation
        /// @param direction scroll direction
        /// @return positive unit increment
        @Override
        public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction) {
            Objects.requireNonNull(visibleRect, "visibleRect");
            return orientation == SwingConstants.HORIZONTAL ? TASK_INDENT : 18;
        }

        /// Returns one viewport-relative block increment.
        ///
        /// @param visibleRect current viewport rectangle
        /// @param orientation scroll orientation
        /// @param direction scroll direction
        /// @return positive block increment
        @Override
        public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction) {
            Objects.requireNonNull(visibleRect, "visibleRect");
            int extent = orientation == SwingConstants.HORIZONTAL
                    ? visibleRect.width
                    : visibleRect.height;
            return Math.max(18, extent - 18);
        }

        /// Fills the details viewport while no nested task requires horizontal overflow.
        ///
        /// @return whether the content fits the viewport width
        @Override
        public boolean getScrollableTracksViewportWidth() {
            if (!(getParent() instanceof JViewport viewport)) {
                return false;
            }
            return getPreferredSize().width <= viewport.getWidth();
        }

        /// Leaves vertical growth to the outer task-list scrollbar.
        ///
        /// @return always false
        @Override
        public boolean getScrollableTracksViewportHeight() {
            return false;
        }
    }
}
