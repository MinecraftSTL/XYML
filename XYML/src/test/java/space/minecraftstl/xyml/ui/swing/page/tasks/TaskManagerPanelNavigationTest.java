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
import space.minecraftstl.xyml.task.TaskExecutionRegistry;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;

import javax.swing.JTabbedPane;
import java.awt.Component;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Objects;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Verifies explicit running-tab selection independently from shell navigation.
@NotNullByDefault
public final class TaskManagerPanelNavigationTest {
    /// Completed and aborted selections are reset without replacing their page components or records.
    @Test
    public void selectsRunningTabIdempotentlyWithoutReplacingContents() {
        EdtDispatcher.executeAndWait(() -> {
            TaskExecutionRegistry registry = new TaskExecutionRegistry();
            try (TaskManagerPanel panel = new TaskManagerPanel(registry)) {
                JTabbedPane tabs = tabs(panel);
                Component runningContent = tabs.getComponentAt(0);
                long revision = registry.publication().revision();
                for (int previousTab : new int[] {1, 2, 0, 2}) {
                    tabs.setSelectedIndex(previousTab);
                    panel.showRunningTasks();
                    panel.showRunningTasks();
                    assertEquals(0, tabs.getSelectedIndex());
                    assertSame(runningContent, tabs.getSelectedComponent());
                    assertEquals(revision, registry.publication().revision());
                }
            }
        });
    }

    /// An explicit selection cannot mutate Swing state from a worker thread.
    @Test
    public void rejectsSelectionOutsideEventDispatchThread() {
        AtomicReference<@Nullable TaskManagerPanel> reference = new AtomicReference<>();
        EdtDispatcher.executeAndWait(() -> reference.set(new TaskManagerPanel(new TaskExecutionRegistry())));
        TaskManagerPanel panel = Objects.requireNonNull(reference.get());
        try {
            assertThrows(IllegalStateException.class, panel::showRunningTasks);
        } finally {
            EdtDispatcher.executeAndWait(panel::close);
        }
    }

    /// Closure is idempotent, releases the registry subscription, and rejects later navigation requests.
    ///
    /// @throws ReflectiveOperationException when the registry listener ownership cannot be inspected
    @Test
    public void rejectsSelectionAfterPageClosure() throws ReflectiveOperationException {
        Field listeners = TaskExecutionRegistry.class.getDeclaredField("revisionListeners");
        listeners.setAccessible(true);
        TaskExecutionRegistry registry = new TaskExecutionRegistry();
        AtomicReference<@Nullable TaskManagerPanel> reference = new AtomicReference<>();
        EdtDispatcher.executeAndWait(() -> reference.set(new TaskManagerPanel(registry)));
        assertEquals(1, ((List<?>) listeners.get(registry)).size());
        TaskManagerPanel panel = Objects.requireNonNull(reference.get());
        EdtDispatcher.executeAndWait(() -> {
            panel.close();
            panel.close();
            assertEquals(0, panel.getComponentCount());
            assertThrows(IllegalStateException.class, panel::showRunningTasks);
        });
        assertEquals(0, ((List<?>) listeners.get(registry)).size());
    }

    /// Finds the direct lifecycle-tab child without exposing mutable widgets through the production API.
    ///
    /// @param panel open task manager
    /// @return lifecycle tab container
    private static JTabbedPane tabs(TaskManagerPanel panel) {
        return Arrays.stream(panel.getComponents())
                .filter(JTabbedPane.class::isInstance)
                .map(JTabbedPane.class::cast)
                .findFirst()
                .orElseThrow();
    }
}
