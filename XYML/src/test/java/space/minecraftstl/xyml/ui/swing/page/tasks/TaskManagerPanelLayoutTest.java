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
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies the task page's bounded log and horizontally scrollable detail layout.
@NotNullByDefault
public final class TaskManagerPanelLayoutTest {
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
                for (JScrollPane logScroll : logScrolls) {
                    assertEquals(
                            JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED,
                            logScroll.getHorizontalScrollBarPolicy());
                    JTextArea log = named(logScroll, "", JTextArea.class).stream().findFirst().orElseThrow();
                    assertFalse(log.getLineWrap());
                    assertTrue(logScroll.getPreferredSize().width < 600);
                }

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

                panel.setSize(new Dimension(1_020, 900));
                layoutTree(panel);
                assertTrue(named(panel, "taskExecutionTitle", JTextArea.class).get(0).getWidth() > titleWidth);
            });
        } finally {
            EdtDispatcher.executeAndWait(panel::close);
        }
    }

    /// Publishes a fixture through the page's private render boundary.
    ///
    /// @param panel task page under test
    /// @param snapshot immutable execution fixture
    private static void publish(TaskManagerPanel panel, TaskExecutionSnapshot snapshot) {
        try {
            Method render = TaskManagerPanel.class.getDeclaredMethod(
                    "renderSnapshots",
                    TaskExecutionRegistry.Publication.class);
            if (!render.trySetAccessible()) {
                throw new AssertionError("Task page render method is not accessible to its layout test");
            }
            render.invoke(panel, new TaskExecutionRegistry.Publication(1L, List.of(snapshot)));
        } catch (ReflectiveOperationException failure) {
            Throwable cause = failure instanceof InvocationTargetException invocation
                    ? invocation.getCause()
                    : failure;
            throw new AssertionError("Failed to publish layout fixture", cause);
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
                List.of(new TaskExecutionLogEntry(timestamp, null, "running", "A very long aggregate log line "
                        + "with diagnostic details that must remain horizontally scrollable ".repeat(8))));
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
