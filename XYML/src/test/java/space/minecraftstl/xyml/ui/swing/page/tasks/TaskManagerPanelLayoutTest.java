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
import org.junit.jupiter.api.Test;
import space.minecraftstl.xyml.task.Task;
import space.minecraftstl.xyml.task.TaskExecutionLogEntry;
import space.minecraftstl.xyml.task.TaskExecutionRegistry;
import space.minecraftstl.xyml.task.TaskExecutionSnapshot;
import space.minecraftstl.xyml.task.TaskExecutionStatus;
import space.minecraftstl.xyml.task.TaskExecutionTaskSnapshot;
import space.minecraftstl.xyml.task.TaskExecutionTaskStatus;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;

import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JViewport;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Point;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.event.MouseWheelListener;
import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies the task page's bounded log and horizontally scrollable detail layout.
@NotNullByDefault
public final class TaskManagerPanelLayoutTest {
    /// Placement where the target row top is inside the outer list viewport.
    private static final int ANCHOR_VISIBLE = 0;

    /// Placement where the target row top is above the outer list viewport.
    private static final int ANCHOR_ABOVE = 1;

    /// Placement where the target row top is below the outer list viewport.
    private static final int ANCHOR_BELOW = 2;

    /// The expanded workflow keeps nested titles aligned and exposes horizontal overflow controls.
    @Test
    public void keepsNestedDetailsScrollableAndTitlesAligned() {
        TaskExecutionSnapshot snapshot = deepSnapshot();
        AtomicReference<@Nullable TaskManagerPanel> panelReference = new AtomicReference<>();
        EdtDispatcher.executeAndWait(() -> {
            TaskManagerPanel panel = new TaskManagerPanel(new TaskExecutionRegistry());
            panelReference.set(panel);
            publish(panel, snapshot);
            panel.setSize(new Dimension(760, 900));
            layoutTree(panel);
            named(panel, "taskExecutionDisclosure", JButton.class).get(0).doClick();
            layoutTree(panel);
        });

        TaskManagerPanel panel = Objects.requireNonNull(panelReference.get(), "panel");
        try {
            EdtDispatcher.executeAndWait(() -> {
                List<JTextArea> titles = named(panel, "taskExecutionTitle", JTextArea.class);
                List<JTextArea> taskNames = named(panel, "taskDetailName", JTextArea.class);
                assertEquals(1, titles.size());
                assertTrue(taskNames.size() > 1);
                int titleWidth = titles.get(0).getWidth();
                assertTrue(titleWidth > 0);
                assertTrue(titles.get(0).getLineWrap());
                for (JTextArea taskName : taskNames) {
                    assertTrue(taskName.getLineWrap());
                    assertEquals(titleWidth, taskName.getWidth());
                }

                List<JScrollPane> logScrolls = named(panel, "taskLogScroll", JScrollPane.class);
                assertFalse(logScrolls.isEmpty());
                int logWidth = logScrolls.get(0).getPreferredSize().width;
                assertTrue(logWidth >= titleWidth);
                int renderedLogWidth = logScrolls.get(0).getWidth();
                assertTrue(renderedLogWidth > 0);
                for (JScrollPane logScroll : logScrolls) {
                    assertEquals(
                            JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED,
                            logScroll.getHorizontalScrollBarPolicy());
                    JTextArea log = named(logScroll, "", JTextArea.class).stream().findFirst().orElseThrow();
                    assertFalse(log.getLineWrap());
                    assertTrue(log.isOpaque());
                    assertFalse(logScroll.getViewport().isOpaque());
                    assertNotNull(log.getSelectionColor());
                    assertNotNull(log.getSelectedTextColor());
                    assertEquals(logWidth, logScroll.getPreferredSize().width);
                    assertEquals(renderedLogWidth, logScroll.getWidth());
                }
                JScrollPane timelineScroll = logScrolls.get(logScrolls.size() - 1);
                assertTrue(timelineScroll.getHorizontalScrollBar().isVisible());
                assertTrue(timelineScroll.getHorizontalScrollBar().getMaximum()
                        > timelineScroll.getHorizontalScrollBar().getVisibleAmount());

                JScrollPane detailsScroll = named(panel, "taskExecutionDetailsScroll", JScrollPane.class)
                        .stream()
                        .findFirst()
                        .orElseThrow();
                assertEquals(
                        JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED,
                        detailsScroll.getHorizontalScrollBarPolicy());
                assertEquals(
                        JScrollPane.VERTICAL_SCROLLBAR_NEVER,
                        detailsScroll.getVerticalScrollBarPolicy());
                assertTrue(detailsScroll.getHorizontalScrollBar().getMaximum()
                        > detailsScroll.getHorizontalScrollBar().getVisibleAmount());
                assertTrue(detailsScroll.getPreferredSize().height
                        > detailsScroll.getViewport().getView().getPreferredSize().height
                        + detailsScroll.getHorizontalScrollBar().getPreferredSize().height);

                JPanel row = named(panel, "taskExecutionRow-" + snapshot.id(), JPanel.class).get(0);
                JButton disclosure = named(row, "taskExecutionDisclosure", JButton.class).get(0);
                assertTrue(disclosure.isContentAreaFilled());
                assertTrue(disclosure.isBorderPainted());
                assertTrue(disclosure.isFocusPainted());
                assertTrue(disclosure.isRolloverEnabled());
                assertTrue(disclosure.getHeight()
                        >= row.getHeight() - row.getInsets().top - row.getInsets().bottom);

                Point timelineBottom = SwingUtilities.convertPoint(
                        timelineScroll,
                        0,
                        timelineScroll.getHeight(),
                        detailsScroll);
                Point detailsScrollBarTop = SwingUtilities.convertPoint(
                        detailsScroll.getHorizontalScrollBar(),
                        0,
                        0,
                        detailsScroll);
                assertTrue(detailsScrollBarTop.y - timelineBottom.y >= 8);

                panel.setSize(new Dimension(1_020, 900));
                layoutTree(panel);
                assertTrue(named(panel, "taskExecutionTitle", JTextArea.class).get(0).getWidth() > titleWidth);
            });
        } finally {
            EdtDispatcher.executeAndWait(panel::close);
        }
    }

    /// Clearing a log selection repaints the previous selection area instead of leaving a stale highlight or glyph.
    @Test
    public void clearsLogSelectionWithoutLeavingPaintedResidue() {
        TaskExecutionSnapshot snapshot = deepSnapshot();
        AtomicReference<@Nullable TaskManagerPanel> panelReference = new AtomicReference<>();
        EdtDispatcher.executeAndWait(() -> {
            TaskManagerPanel panel = new TaskManagerPanel(new TaskExecutionRegistry());
            panelReference.set(panel);
            publish(panel, snapshot);
            panel.setSize(new Dimension(760, 900));
            layoutTree(panel);
            expand(panel, snapshot.id());
            layoutTree(panel);
        });

        TaskManagerPanel panel = Objects.requireNonNull(panelReference.get(), "panel");
        try {
            EdtDispatcher.executeAndWait(() -> {
                JScrollPane logScroll = named(panel, "taskLogScroll", JScrollPane.class).get(0);
                JTextArea log = named(logScroll, "", JTextArea.class).stream().findFirst().orElseThrow();
                Dimension preferredSize = log.getPreferredSize();
                log.setSize(Math.max(1, preferredSize.width), Math.max(1, preferredSize.height));
                log.getCaret().setVisible(false);
                log.getCaret().setSelectionVisible(true);

                BufferedImage baseline = paint(log);
                BufferedImage repaintSurface = copy(baseline);
                int selectionEnd = Math.min(log.getDocument().getLength(), 24);
                log.select(0, selectionEnd);
                log.getCaret().setSelectionVisible(true);
                paint(log, repaintSurface);
                BufferedImage selected = copy(repaintSurface);
                log.select(0, 0);
                log.getCaret().setSelectionVisible(false);
                log.getCaret().setVisible(false);
                paint(log, repaintSurface);

                assertFalse(imagesEqual(baseline, selected));
                assertTrue(imagesEqual(baseline, repaintSurface));
            });
        } finally {
            EdtDispatcher.executeAndWait(panel::close);
        }
    }

    /// Expanding a workflow preserves the target row's top edge in the outer list viewport.
    @Test
    public void expandingPreservesTargetRowViewportPosition() {
        TaskExecutionSnapshot target = deepSnapshot();
        @Unmodifiable List<TaskExecutionSnapshot> snapshots = surroundingSnapshots(target);
        AtomicReference<@Nullable TaskManagerPanel> panelReference = new AtomicReference<>();
        AtomicReference<@Nullable Integer> rowTopReference = new AtomicReference<>();
        EdtDispatcher.executeAndWait(() -> {
            TaskManagerPanel panel = new TaskManagerPanel(new TaskExecutionRegistry());
            panelReference.set(panel);
            publish(panel, snapshots);
            panel.setSize(new Dimension(640, 320));
            layoutTree(panel);
            JScrollPane listScroll = named(panel, "taskManagerRunningScroll", JScrollPane.class).get(0);
            JViewport viewport = listScroll.getViewport();
            JPanel row = named(panel, "taskExecutionRow-" + target.id(), JPanel.class).get(0);
            int maxBefore = Math.max(0, viewport.getView().getHeight() - viewport.getExtentSize().height);
            int requestedViewY = Math.max(0, Math.min(maxBefore, row.getY() - viewport.getExtentSize().height / 3));
            viewport.setViewPosition(new Point(0, requestedViewY));
            layoutTree(panel);
            rowTopReference.set(SwingUtilities.convertPoint(row, 0, 0, viewport).y);
            named(row, "taskExecutionDisclosure", JButton.class).get(0).doClick();
            layoutTree(panel);
        });
        EdtDispatcher.executeAndWait(() -> {
        });

        TaskManagerPanel panel = Objects.requireNonNull(panelReference.get(), "panel");
        try {
            EdtDispatcher.executeAndWait(() -> {
                JPanel row = named(panel, "taskExecutionRow-" + target.id(), JPanel.class).get(0);
                JScrollPane listScroll = named(panel, "taskManagerRunningScroll", JScrollPane.class).get(0);
                int actualTop = SwingUtilities.convertPoint(row, 0, 0, listScroll.getViewport()).y;
                assertEquals(Objects.requireNonNull(rowTopReference.get(), "row top"), actualTop);
            });
        } finally {
            EdtDispatcher.executeAndWait(panel::close);
        }
    }

    /// Expanding from a short list preserves the target row position before the new details overflow the viewport.
    @Test
    public void expandingShortListPreservesTargetRowViewportPosition() {
        TaskExecutionSnapshot target = deepSnapshot();
        Instant timestamp = target.startedAt();
        @Unmodifiable List<TaskExecutionSnapshot> snapshots = List.of(
                simpleSnapshot(
                        UUID.nameUUIDFromBytes("short-before".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                        timestamp.plusSeconds(2L),
                        "Short list before"),
                target,
                simpleSnapshot(
                        UUID.nameUUIDFromBytes("short-after".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                        timestamp.minusSeconds(2L),
                        "Short list after"));
        AtomicReference<@Nullable TaskManagerPanel> panelReference = new AtomicReference<>();
        AtomicReference<@Nullable Integer> rowTopReference = new AtomicReference<>();
        EdtDispatcher.executeAndWait(() -> {
            TaskManagerPanel panel = new TaskManagerPanel(new TaskExecutionRegistry());
            panelReference.set(panel);
            publish(panel, snapshots);
            panel.setSize(new Dimension(640, 720));
            layoutTree(panel);
            JScrollPane listScroll = named(panel, "taskManagerRunningScroll", JScrollPane.class).get(0);
            JViewport viewport = listScroll.getViewport();
            JPanel row = named(panel, "taskExecutionRow-" + target.id(), JPanel.class).get(0);
            assertTrue(viewport.getView().getHeight() <= viewport.getExtentSize().height);
            rowTopReference.set(SwingUtilities.convertPoint(row, 0, 0, viewport).y);
            named(row, "taskExecutionDisclosure", JButton.class).get(0).doClick();
            layoutTree(panel);
        });
        EdtDispatcher.executeAndWait(() -> {
        });

        TaskManagerPanel panel = Objects.requireNonNull(panelReference.get(), "panel");
        try {
            EdtDispatcher.executeAndWait(() -> {
                JPanel row = named(panel, "taskExecutionRow-" + target.id(), JPanel.class).get(0);
                JScrollPane listScroll = named(panel, "taskManagerRunningScroll", JScrollPane.class).get(0);
                int actualTop = SwingUtilities.convertPoint(row, 0, 0, listScroll.getViewport()).y;
                assertEquals(Objects.requireNonNull(rowTopReference.get(), "row top"), actualTop);
            });
        } finally {
            EdtDispatcher.executeAndWait(panel::close);
        }
    }

    /// Keeps a cached tab frame visible until deferred viewport correction has completed.
    @Test
    public void toggleKeepsCachedFrameUntilViewportSettles() {
        TaskExecutionSnapshot snapshot = deepSnapshot();
        AtomicReference<@Nullable TaskManagerPanel> panelReference = new AtomicReference<>();
        EdtDispatcher.executeAndWait(() -> {
            TaskManagerPanel panel = new TaskManagerPanel(new TaskExecutionRegistry());
            panelReference.set(panel);
            publish(panel, snapshot);
            panel.setSize(new Dimension(640, 320));
            layoutTree(panel);
            toggleExpandedWithoutButtonPress(panel, snapshot.id());

            javax.swing.JTabbedPane tabs = named(panel, "taskManagerTabs", javax.swing.JTabbedPane.class).get(0);
            Field frozenFrame = declaredField(tabs, "frozenFrame");
            assertNotNull(readField(frozenFrame, tabs));
            assertTrue(imagesEqual(paint(panel), paint(panel)));
        });

        TaskManagerPanel panel = Objects.requireNonNull(panelReference.get(), "panel");
        try {
            EdtDispatcher.executeAndWait(() -> {
                // Flush the deferred viewport corrections and release the cached frame.
            });
            EdtDispatcher.executeAndWait(() -> {
                // Flush the second deferred correction.
            });
            EdtDispatcher.executeAndWait(() -> {
                javax.swing.JTabbedPane tabs = named(panel, "taskManagerTabs", javax.swing.JTabbedPane.class).get(0);
                assertNull(readField(declaredField(tabs, "frozenFrame"), tabs));
            });
        } finally {
            EdtDispatcher.executeAndWait(panel::close);
        }
    }

    /// Collapsing from any part of an expanded row preserves its viewport anchor and never leaves invalid bounds.
    @Test
    public void collapsesFromFullHeightDisclosureWithoutLeavingBlankSpace() {
        TaskExecutionSnapshot target = deepSnapshot();
        @Unmodifiable List<TaskExecutionSnapshot> snapshots = surroundingSnapshots(target);
        AtomicReference<@Nullable TaskManagerPanel> panelReference = new AtomicReference<>();
        EdtDispatcher.executeAndWait(() -> {
            TaskManagerPanel panel = new TaskManagerPanel(new TaskExecutionRegistry());
            panelReference.set(panel);
            publish(panel, snapshots);
            panel.setSize(new Dimension(640, 320));
            layoutTree(panel);
            expand(panel, target.id());
            layoutTree(panel);
        });

        TaskManagerPanel panel = Objects.requireNonNull(panelReference.get(), "panel");
        try {
            verifyCollapseAnchor(panel, target.id(), ANCHOR_VISIBLE);
            expandAndLayout(panel, target.id());
            verifyCollapseAnchor(panel, target.id(), ANCHOR_ABOVE);
            expandAndLayout(panel, target.id());
            verifyCollapseAnchor(panel, target.id(), ANCHOR_BELOW);
        } finally {
            EdtDispatcher.executeAndWait(panel::close);
        }
    }

    /// Keeps a shallow details viewport free of false horizontal overflow when the outer list has many rows.
    @Test
    public void doesNotCreateFalseDetailsOverflowForVerticalListScrollbar() {
        TaskExecutionSnapshot target = simpleSnapshot(
                UUID.nameUUIDFromBytes("shallow-target".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                Instant.now(),
                "Shallow target workflow");
        @Unmodifiable List<TaskExecutionSnapshot> snapshots = surroundingSnapshots(target);
        AtomicReference<@Nullable TaskManagerPanel> panelReference = new AtomicReference<>();
        EdtDispatcher.executeAndWait(() -> {
            TaskManagerPanel panel = new TaskManagerPanel(new TaskExecutionRegistry());
            panelReference.set(panel);
            publish(panel, snapshots);
            panel.setSize(new Dimension(640, 320));
            layoutTree(panel);
            expand(panel, target.id());
            layoutTree(panel);
        });

        TaskManagerPanel panel = Objects.requireNonNull(panelReference.get(), "panel");
        try {
            EdtDispatcher.executeAndWait(() -> {
                JScrollPane details = named(panel, "taskExecutionDetailsScroll", JScrollPane.class).stream()
                        .filter(scroll -> scroll.getParent() != null)
                        .findFirst()
                        .orElseThrow();
                assertEquals(
                        JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED,
                        details.getHorizontalScrollBarPolicy());
                assertTrue(details.getHorizontalScrollBar().getMaximum()
                        <= details.getHorizontalScrollBar().getVisibleAmount(),
                        () -> "false details overflow: max=" + details.getHorizontalScrollBar().getMaximum()
                                + ", visible=" + details.getHorizontalScrollBar().getVisibleAmount());
                assertTrue(details.getPreferredSize().height
                        <= details.getViewport().getView().getPreferredSize().height + 1);
            });
        } finally {
            EdtDispatcher.executeAndWait(panel::close);
        }
    }

    /// Keeps vertical scrolling inside an overflowing log while routing other detail wheels to the outer list.
    @Test
    public void routesDetailsWheelToLogAndOuterList() {
        TaskExecutionSnapshot target = deepSnapshot();
        @Unmodifiable List<TaskExecutionSnapshot> snapshots = surroundingSnapshots(target);
        AtomicReference<@Nullable TaskManagerPanel> panelReference = new AtomicReference<>();
        EdtDispatcher.executeAndWait(() -> {
            TaskManagerPanel panel = new TaskManagerPanel(new TaskExecutionRegistry());
            panelReference.set(panel);
            publish(panel, snapshots);
            panel.setSize(new Dimension(640, 320));
            layoutTree(panel);
            expand(panel, target.id());
            layoutTree(panel);
        });

        TaskManagerPanel panel = Objects.requireNonNull(panelReference.get(), "panel");
        try {
            EdtDispatcher.executeAndWait(() -> {
                JScrollPane listScroll = named(panel, "taskManagerRunningScroll", JScrollPane.class).get(0);
                JScrollBar listBar = listScroll.getVerticalScrollBar();
                assertTrue(listBar.getMaximum() > listBar.getVisibleAmount());
                JScrollPane timelineScroll = named(panel, "taskLogScroll", JScrollPane.class).stream()
                        .reduce((first, second) -> second)
                        .orElseThrow();
                JTextArea timeline = named(timelineScroll, "", JTextArea.class).stream().findFirst().orElseThrow();
                JScrollBar timelineVertical = timelineScroll.getVerticalScrollBar();
                JScrollBar timelineHorizontal = timelineScroll.getHorizontalScrollBar();
                assertTrue(timelineVertical.getMaximum() > timelineVertical.getVisibleAmount());
                assertTrue(timelineHorizontal.getMaximum() > timelineHorizontal.getVisibleAmount());
                listBar.setValue(0);
                timelineVertical.setValue(0);
                MouseWheelEvent vertical = wheel(timeline, false, 1);
                dispatchWheel(timeline, vertical);
                assertTrue(vertical.isConsumed());
                assertEquals(0, listBar.getValue());
                assertTrue(timelineVertical.getValue() > 0);

                JTextArea taskName = named(panel, "taskDetailName", JTextArea.class).get(0);
                MouseWheelEvent detailsVertical = wheel(taskName, false, 1);
                dispatchWheel(taskName, detailsVertical);
                assertTrue(detailsVertical.isConsumed());
                assertTrue(listBar.getValue() > 0);

                int listPosition = listBar.getValue();
                timelineHorizontal.setValue(0);
                MouseWheelEvent horizontal = wheel(timeline, true, 1);
                dispatchWheel(timeline, horizontal);
                assertTrue(horizontal.isConsumed());
                assertEquals(listPosition, listBar.getValue());
                assertTrue(timelineHorizontal.getValue() > 0);
            });
        } finally {
            EdtDispatcher.executeAndWait(panel::close);
        }
    }

    /// Exposes terminal-row actions without turning them into additional top-level records.
    @Test
    public void terminalRowsExposeDeleteAndAbortedRetryActions() {
        Instant timestamp = Instant.now();
        TaskExecutionSnapshot completed = new TaskExecutionSnapshot(
                UUID.nameUUIDFromBytes("completed-action".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                "Completed workflow",
                TaskExecutionStatus.SUCCEEDED,
                OptionalDouble.of(1.0D),
                1.0D,
                1.0D,
                true,
                true,
                false,
                timestamp,
                timestamp.plusSeconds(1L),
                null,
                List.of(),
                List.of());
        TaskExecutionSnapshot aborted = new TaskExecutionSnapshot(
                UUID.nameUUIDFromBytes("aborted-action".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                "Aborted workflow",
                TaskExecutionStatus.FAILED,
                OptionalDouble.of(0.4D),
                1.0D,
                0.4D,
                true,
                true,
                false,
                timestamp.plusSeconds(2L),
                timestamp.plusSeconds(3L),
                "failure",
                List.of(),
                List.of());
        TaskExecutionSnapshot automaticAborted = new TaskExecutionSnapshot(
                UUID.nameUUIDFromBytes("automatic-aborted-action".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                "Automatic aborted workflow",
                TaskExecutionStatus.FAILED,
                OptionalDouble.of(0.2D),
                1.0D,
                0.2D,
                true,
                false,
                false,
                timestamp.plusSeconds(4L),
                timestamp.plusSeconds(5L),
                "automatic failure",
                List.of(),
                List.of());
        TaskExecutionSnapshot automaticCompleted = new TaskExecutionSnapshot(
                UUID.nameUUIDFromBytes("automatic-completed-action".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                "Automatic completed workflow",
                TaskExecutionStatus.SUCCEEDED,
                OptionalDouble.of(1.0D),
                1.0D,
                1.0D,
                true,
                false,
                false,
                timestamp.plusSeconds(6L),
                timestamp.plusSeconds(7L),
                null,
                List.of(),
                List.of());
        AtomicReference<@Nullable TaskManagerPanel> panelReference = new AtomicReference<>();
        EdtDispatcher.executeAndWait(() -> {
            TaskManagerPanel panel = new TaskManagerPanel(new TaskExecutionRegistry());
            panelReference.set(panel);
            publish(panel, List.of(completed, aborted, automaticAborted, automaticCompleted));
            panel.setSize(new Dimension(760, 480));
            layoutTree(panel);
        });
        TaskManagerPanel panel = Objects.requireNonNull(panelReference.get(), "panel");
        try {
            EdtDispatcher.executeAndWait(() -> {
                List<JButton> deleteButtons = named(panel, "taskExecutionDelete", JButton.class);
                List<JButton> retryButtons = named(panel, "taskExecutionRetry", JButton.class);
                assertEquals(3, deleteButtons.size());
                assertEquals(1, retryButtons.size());
                assertEquals(1, named(
                        panel,
                        "taskExecutionRow-" + completed.id(),
                        JPanel.class).size());
                assertTrue(named(
                        panel,
                        "taskExecutionRow-" + automaticCompleted.id(),
                        JPanel.class).isEmpty());
                for (JButton button : deleteButtons) {
                    assertEquals("", button.getText());
                    assertNotNull(button.getIcon());
                    assertNotNull(button.getToolTipText());
                }
                assertEquals("", retryButtons.get(0).getText());
                assertNotNull(retryButtons.get(0).getIcon());
                assertNotNull(retryButtons.get(0).getToolTipText());
            });
        } finally {
            EdtDispatcher.executeAndWait(panel::close);
        }
    }

    /// Creates one unit-scroll wheel event for a details component.
    ///
    /// @param component event source
    /// @param horizontal whether Shift should request horizontal scrolling
    /// @param rotation signed wheel rotation
    /// @return synthetic wheel event
    private static MouseWheelEvent wheel(Component component, boolean horizontal, int rotation) {
        return new MouseWheelEvent(
                component,
                MouseEvent.MOUSE_WHEEL,
                System.currentTimeMillis(),
                horizontal ? MouseEvent.SHIFT_DOWN_MASK : 0,
                4,
                4,
                0,
                false,
                MouseWheelEvent.WHEEL_UNIT_SCROLL,
                3,
                rotation);
    }

    /// Delivers one synthetic event to the listeners installed on a details component.
    ///
    /// @param component details component receiving the event
    /// @param event wheel event
    private static void dispatchWheel(Component component, MouseWheelEvent event) {
        for (MouseWheelListener listener : component.getMouseWheelListeners()) {
            listener.mouseWheelMoved(event);
        }
    }

    /// Publishes a fixture through the page's private render boundary.
    ///
    /// @param panel task page under test
    /// @param snapshot immutable execution fixture
    private static void publish(TaskManagerPanel panel, TaskExecutionSnapshot snapshot) {
        publish(panel, List.of(snapshot));
    }

    /// Publishes a complete immutable execution fixture through the page's private render boundary.
    ///
    /// @param panel task page under test
    /// @param snapshots immutable execution fixtures
    private static void publish(TaskManagerPanel panel, @Unmodifiable List<TaskExecutionSnapshot> snapshots) {
        try {
            Method render = TaskManagerPanel.class.getDeclaredMethod(
                    "renderSnapshots",
                    TaskExecutionRegistry.Publication.class);
            if (!render.trySetAccessible()) {
                throw new AssertionError("Task page render method is not accessible to its layout test");
            }
            render.invoke(panel, new TaskExecutionRegistry.Publication(1L, snapshots));
        } catch (ReflectiveOperationException failure) {
            Throwable cause = failure instanceof InvocationTargetException invocation
                    ? invocation.getCause()
                    : failure;
            throw new AssertionError("Failed to publish layout fixture", cause);
        }
    }

    /// Expands one target row and lays out the complete task page.
    ///
    /// @param panel task page under test
    /// @param executionId target execution ID
    private static void expandAndLayout(TaskManagerPanel panel, UUID executionId) {
        EdtDispatcher.executeAndWait(() -> {
            expand(panel, executionId);
            layoutTree(panel);
        });
        EdtDispatcher.executeAndWait(() -> {
            // Flush the deferred viewport restoration queued by the preceding collapse, if any.
        });
    }

    /// Expands a target row through its disclosure action.
    ///
    /// @param panel task page under test
    /// @param executionId target execution ID
    private static void expand(TaskManagerPanel panel, UUID executionId) {
        JPanel row = named(panel, "taskExecutionRow-" + executionId, JPanel.class).get(0);
        named(row, "taskExecutionDisclosure", JButton.class).get(0).doClick();
    }

    /// Expands a row without changing the disclosure button model before the frame is captured.
    ///
    /// @param panel task page under test
    /// @param executionId execution whose details should be expanded
    private static void toggleExpandedWithoutButtonPress(TaskManagerPanel panel, UUID executionId) {
        try {
            Method toggle = TaskManagerPanel.class.getDeclaredMethod("toggleExpanded", UUID.class);
            if (!toggle.trySetAccessible()) {
                throw new AssertionError("task toggle method is not accessible");
            }
            toggle.invoke(panel, executionId);
        } catch (ReflectiveOperationException failure) {
            Throwable cause = failure instanceof InvocationTargetException invocation
                    ? invocation.getCause()
                    : failure;
            throw new AssertionError("failed to toggle task details", cause);
        }
    }

    /// Resolves and opens one private field for a layout regression assertion.
    ///
    /// @param target object declaring the field
    /// @param name field name
    /// @return accessible field handle
    private static Field declaredField(Object target, String name) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            if (!field.trySetAccessible()) {
                throw new AssertionError("field is not accessible: " + name);
            }
            return field;
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("failed to resolve field: " + name, failure);
        }
    }

    /// Reads one private field for a layout regression assertion.
    ///
    /// @param field accessible field handle
    /// @param target object containing the field
    /// @return field value, possibly null
    private static @Nullable Object readField(Field field, Object target) {
        try {
            return field.get(target);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("failed to read field: " + field.getName(), failure);
        }
    }

    /// Exercises one pre-collapse placement and verifies the resulting clamped viewport anchor.
    ///
    /// @param panel task page under test
    /// @param executionId target execution ID
    /// @param placement requested row placement before collapse
    private static void verifyCollapseAnchor(TaskManagerPanel panel, UUID executionId, int placement) {
        AtomicReference<@Nullable ViewportAnchor> beforeReference = new AtomicReference<>();
        EdtDispatcher.executeAndWait(() -> {
            JScrollPane listScroll = named(panel, "taskManagerRunningScroll", JScrollPane.class).get(0);
            JViewport viewport = listScroll.getViewport();
            JPanel row = named(panel, "taskExecutionRow-" + executionId, JPanel.class).get(0);
            layoutTree(panel);
            int rowContentTop = row.getY();
            int maxBefore = Math.max(0, viewport.getView().getHeight() - viewport.getExtentSize().height);
            int requestedViewY = switch (placement) {
                case ANCHOR_VISIBLE -> Math.max(0, rowContentTop - viewport.getExtentSize().height / 2);
                case ANCHOR_ABOVE -> Math.min(maxBefore, rowContentTop + 20);
                case ANCHOR_BELOW -> Math.max(0, rowContentTop - viewport.getExtentSize().height - 20);
                default -> throw new AssertionError("Unknown anchor placement: " + placement);
            };
            viewport.setViewPosition(new Point(0, requestedViewY));
            layoutTree(panel);
            int oldViewY = viewport.getViewPosition().y;
            int oldRowTop = row.getY() - oldViewY;
            int extentHeight = viewport.getExtentSize().height;
            if (placement == ANCHOR_VISIBLE) {
                assertTrue(oldRowTop >= 0 && oldRowTop <= extentHeight);
            } else if (placement == ANCHOR_ABOVE) {
                assertTrue(oldRowTop < 0);
            } else {
                assertTrue(oldRowTop > extentHeight);
            }
            beforeReference.set(new ViewportAnchor(oldViewY, oldRowTop));
            named(row, "taskExecutionDisclosure", JButton.class).get(0).doClick();
            layoutTree(panel);
        });
        EdtDispatcher.executeAndWait(() -> {
        });
        ViewportAnchor before = Objects.requireNonNull(beforeReference.get(), "collapse anchor");
        EdtDispatcher.executeAndWait(() -> {
            JScrollPane listScroll = named(panel, "taskManagerRunningScroll", JScrollPane.class).get(0);
            JViewport viewport = listScroll.getViewport();
            JPanel row = named(panel, "taskExecutionRow-" + executionId, JPanel.class).get(0);
            layoutTree(panel);
            int newViewY = viewport.getViewPosition().y;
            int newRowTop = row.getY() - newViewY;
            int newMax = Math.max(0, viewport.getView().getHeight() - viewport.getExtentSize().height);
            int newContentTop = row.getY();
            int desiredRowTop = before.rowTopInViewport() < 0 ? 0 : before.rowTopInViewport();
            int expectedViewY = Math.max(0, Math.min(newMax, newContentTop - desiredRowTop));
            assertEquals(expectedViewY, newViewY);
            assertTrue(newViewY >= 0 && newViewY <= newMax);
            if (before.rowTopInViewport() < 0) {
                assertEquals(0, newRowTop);
            } else if (placement == ANCHOR_BELOW) {
                assertEquals(before.viewPositionY(), newViewY);
            }
        });
    }

    /// Creates enough surrounding rows to exercise visible, above, and below collapse placements.
    ///
    /// @param target expanded target fixture
    /// @return immutable surrounding execution fixtures
    private static @Unmodifiable List<TaskExecutionSnapshot> surroundingSnapshots(TaskExecutionSnapshot target) {
        List<TaskExecutionSnapshot> snapshots = new ArrayList<>();
        Instant timestamp = target.startedAt();
        for (int index = 0; index < 14; index++) {
            snapshots.add(simpleSnapshot(
                    UUID.nameUUIDFromBytes(("before-" + index).getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                    timestamp.plusSeconds(index + 1L),
                    "Surrounding workflow before " + index));
        }
        snapshots.add(target);
        for (int index = 0; index < 14; index++) {
            snapshots.add(simpleSnapshot(
                    UUID.nameUUIDFromBytes(("after-" + index).getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                    timestamp.minusSeconds(index + 1L),
                    "Surrounding workflow after " + index));
        }
        return List.copyOf(snapshots);
    }

    /// Creates one compact active surrounding execution.
    ///
    /// @param id execution ID
    /// @param timestamp execution start timestamp
    /// @param title execution title
    /// @return active execution fixture
    private static TaskExecutionSnapshot simpleSnapshot(UUID id, Instant timestamp, String title) {
        return new TaskExecutionSnapshot(
                id,
                title,
                TaskExecutionStatus.RUNNING,
                OptionalDouble.of(0.25D),
                1.0D,
                0.25D,
                true,
                true,
                true,
                timestamp,
                null,
                null,
                List.of(),
                List.of(new TaskExecutionLogEntry(timestamp, null, "running", "surrounding")));
    }

    /// Immutable values captured immediately before a collapse action.
    @NotNullByDefault
    private static final class ViewportAnchor {
        /// Outer list viewport position before collapse.
        private final int viewPositionY;

        /// Target row top relative to the viewport before collapse.
        private final int rowTopInViewport;

        /// Creates one viewport anchor.
        ///
        /// @param viewPositionY vertical view position
        /// @param rowTopInViewport row top relative to viewport
        private ViewportAnchor(int viewPositionY, int rowTopInViewport) {
            this.viewPositionY = viewPositionY;
            this.rowTopInViewport = rowTopInViewport;
        }

        /// Returns the captured vertical view position.
        ///
        /// @return vertical position
        private int viewPositionY() {
            return viewPositionY;
        }

        /// Returns the captured row top relative to the viewport.
        ///
        /// @return row top
        private int rowTopInViewport() {
            return rowTopInViewport;
        }
    }

    /// Builds one top-level execution with enough nesting to require the details scrollbar.
    ///
    /// @return deep immutable execution fixture
    private static TaskExecutionSnapshot deepSnapshot() {
        Instant timestamp = Instant.now();
        List<TaskExecutionTaskSnapshot> tasks = new ArrayList<>();
        @Nullable UUID parent = null;
        for (int index = 0; index < 20; index++) {
            UUID id = UUID.nameUUIDFromBytes(("task-" + index).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            tasks.add(new TaskExecutionTaskSnapshot(
                    id,
                    parent,
                    "A deeply nested task title that should wrap " + index,
                    "stage." + index,
                    Task.TaskSignificance.MAJOR,
                    index == 19 ? TaskExecutionTaskStatus.RUNNING : TaskExecutionTaskStatus.SUCCEEDED,
                    OptionalDouble.of(index == 19 ? 0.35D : 1.0D),
                    timestamp,
                    null,
                    null,
                    List.of(new TaskExecutionLogEntry(timestamp, id, "progress", "A very long log line "
                            + "with diagnostic details that must remain horizontally scrollable ".repeat(8)))));
            parent = id;
        }
        List<TaskExecutionLogEntry> timelineLogs = new ArrayList<>();
        for (int index = 0; index < 18; index++) {
            timelineLogs.add(new TaskExecutionLogEntry(
                    timestamp.plusMillis(index),
                    null,
                    "running",
                    "Timeline event " + index + " with diagnostic details "
                            + "that must remain horizontally scrollable ".repeat(8)));
        }
        return new TaskExecutionSnapshot(
                UUID.nameUUIDFromBytes("workflow".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                "A top-level workflow title that should wrap according to the window width",
                TaskExecutionStatus.RUNNING,
                OptionalDouble.of(0.65D),
                20.0D,
                19.0D,
                true,
                true,
                true,
                timestamp,
                null,
                null,
                tasks,
                timelineLogs);
    }

    /// Lays out every nested Swing container after assigning a fixed test size.
    ///
    /// @param container root container
    private static void layoutTree(Container container) {
        container.doLayout();
        for (Component child : container.getComponents()) {
            if (child instanceof Container nested) {
                layoutTree(nested);
            }
        }
    }

    /// Paints a component into an isolated image for deterministic visual assertions.
    ///
    /// @param component component to paint
    /// @return rendered component image
    private static BufferedImage paint(Component component) {
        BufferedImage image = new BufferedImage(
                Math.max(1, component.getWidth()),
                Math.max(1, component.getHeight()),
                BufferedImage.TYPE_INT_ARGB);
        paint(component, image);
        return image;
    }

    /// Paints a component onto an existing surface without clearing it first.
    ///
    /// @param component component to paint
    /// @param image destination surface
    private static void paint(Component component, BufferedImage image) {
        Graphics graphics = image.getGraphics();
        try {
            component.paint(graphics);
        } finally {
            graphics.dispose();
        }
    }

    /// Copies one rendered image so a subsequent repaint can be compared against the original pixels.
    ///
    /// @param source source image
    /// @return independent image copy
    private static BufferedImage copy(BufferedImage source) {
        BufferedImage copy = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics graphics = copy.getGraphics();
        try {
            graphics.drawImage(source, 0, 0, null);
        } finally {
            graphics.dispose();
        }
        return copy;
    }

    /// Compares two rendered images pixel by pixel.
    ///
    /// @param first first rendered image
    /// @param second second rendered image
    /// @return whether dimensions and every pixel match
    private static boolean imagesEqual(BufferedImage first, BufferedImage second) {
        if (first.getWidth() != second.getWidth() || first.getHeight() != second.getHeight()) {
            return false;
        }
        for (int y = 0; y < first.getHeight(); y++) {
            for (int x = 0; x < first.getWidth(); x++) {
                if (first.getRGB(x, y) != second.getRGB(x, y)) {
                    return false;
                }
            }
        }
        return true;
    }

    /// Returns every named component of one type in a component subtree.
    ///
    /// @param root subtree root
    /// @param name component name, or empty for the first unnamed text view
    /// @param type requested component type
    /// @param <T> component type
    /// @return matching components in traversal order
    private static <T extends Component> List<T> named(Container root, String name, Class<T> type) {
        List<T> matches = new ArrayList<>();
        collectNamed(root, name, type, matches);
        return matches;
    }

    /// Collects matching components recursively.
    ///
    /// @param root subtree root
    /// @param name requested component name
    /// @param type requested component type
    /// @param matches mutable result list
    /// @param <T> component type
    private static <T extends Component> void collectNamed(
            Container root,
            String name,
            Class<T> type,
            List<T> matches) {
        for (Component child : root.getComponents()) {
            if (type.isInstance(child) && (name.isEmpty() || name.equals(child.getName()))) {
                matches.add(type.cast(child));
            }
            if (child instanceof Container nested) {
                collectNamed(nested, name, type, matches);
            }
        }
    }
}
