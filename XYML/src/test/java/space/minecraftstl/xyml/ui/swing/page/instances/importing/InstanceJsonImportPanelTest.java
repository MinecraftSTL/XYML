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
package space.minecraftstl.xyml.ui.swing.page.instances.importing;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import space.minecraftstl.xyml.task.Task;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;
import space.minecraftstl.xyml.ui.swing.task.TaskProgressStrings;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JTextField;
import java.awt.Component;
import java.awt.Container;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies pure EDT validation, deferred worker execution, and localized terminal feedback.
@NotNullByDefault
final class InstanceJsonImportPanelTest {
    /// Derives the default name lexically and runs the injected task away from the EDT.
    @Test
    void importsDeferredTaskWithoutParsingOnEventDispatchThread() throws InterruptedException {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            AtomicReference<@Nullable Path> receivedSource = new AtomicReference<>();
            AtomicReference<@Nullable String> receivedName = new AtomicReference<>();
            AtomicBoolean serviceCalledOnEdt = new AtomicBoolean();
            AtomicBoolean taskRanOnEdt = new AtomicBoolean(true);
            CountDownLatch taskRan = new CountDownLatch(1);
            InstanceJsonImportService service = (source, instanceId) -> {
                receivedSource.set(source);
                receivedName.set(instanceId);
                serviceCalledOnEdt.set(javax.swing.SwingUtilities.isEventDispatchThread());
                return Task.<@Nullable Void>supplyAsync(executor, () -> {
                    taskRanOnEdt.set(javax.swing.SwingUtilities.isEventDispatchThread());
                    taskRan.countDown();
                    return null;
                });
            };
            AtomicReference<@Nullable InstanceJsonImportPanel> panelReference = new AtomicReference<>();

            EdtDispatcher.executeAndWait(() -> {
                InstanceJsonImportPanel panel = new InstanceJsonImportPanel(
                        service,
                        InstanceJsonImportStrings.english(),
                        TaskProgressStrings.english(),
                        null,
                        Duration.ZERO);
                panel.open(Path.of("Example.JSON"));
                panelReference.set(panel);
                JTextField sourceField = findNamed(panel, "instanceJsonSource", JTextField.class);
                JTextField nameField = findNamed(panel, "instanceJsonInstanceId", JTextField.class);
                JButton importButton = findNamed(panel, "instanceJsonImport", JButton.class);
                assertEquals(Path.of("Example.JSON").toAbsolutePath().normalize().toString(),
                        sourceField.getText());
                assertEquals("Example", nameField.getText());
                assertEquals(Boolean.TRUE, nameField.getClientProperty("JTextField.showClearButton"));
                nameField.setText("Imported Example");
                assertTrue(importButton.isEnabled());
                importButton.doClick();
            });

            assertTrue(taskRan.await(5, TimeUnit.SECONDS));
            assertTrue(serviceCalledOnEdt.get());
            assertFalse(taskRanOnEdt.get());
            assertEquals(Path.of("Example.JSON").toAbsolutePath().normalize(), receivedSource.get());
            assertEquals("Imported Example", receivedName.get());
            awaitStatus(panelReference, InstanceJsonImportStrings.english().succeededStatus());
            Objects.requireNonNull(panelReference.get()).close();
        } finally {
            executor.shutdownNow();
        }
    }

    /// Maps a categorized malformed-JSON failure to the dedicated localized status.
    @Test
    void showsCategorizedMalformedJsonFailure() throws InterruptedException {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            CountDownLatch taskRan = new CountDownLatch(1);
            InstanceJsonImportService service = (source, instanceId) ->
                    Task.<@Nullable Void>supplyAsync(executor, () -> {
                        taskRan.countDown();
                        throw InstanceJsonImportException.malformedJson(
                                source,
                                new IllegalArgumentException("bad json"));
                    });
            AtomicReference<@Nullable InstanceJsonImportPanel> panelReference = new AtomicReference<>();

            EdtDispatcher.executeAndWait(() -> {
                InstanceJsonImportPanel panel = new InstanceJsonImportPanel(
                        service,
                        InstanceJsonImportStrings.english(),
                        TaskProgressStrings.english(),
                        null,
                        Duration.ZERO);
                panel.open(Path.of("broken.json"));
                panelReference.set(panel);
                findNamed(panel, "instanceJsonImport", JButton.class).doClick();
            });

            assertTrue(taskRan.await(5, TimeUnit.SECONDS));
            awaitStatus(panelReference, InstanceJsonImportStrings.english().malformedJsonStatus());
            Objects.requireNonNull(panelReference.get()).close();
        } finally {
            executor.shutdownNow();
        }
    }

    /// Waits for one asynchronous terminal callback to update the EDT-confined status label.
    ///
    /// @param panelReference panel under test
    /// @param expected expected terminal text
    /// @throws InterruptedException if polling is interrupted
    private static void awaitStatus(
            AtomicReference<@Nullable InstanceJsonImportPanel> panelReference,
            String expected) throws InterruptedException {
        for (int attempt = 0; attempt < 100; ++attempt) {
            AtomicReference<@Nullable String> current = new AtomicReference<>();
            EdtDispatcher.executeAndWait(() -> current.set(findNamed(
                    Objects.requireNonNull(panelReference.get()),
                    "instanceJsonStatus",
                    JLabel.class).getText()));
            if (expected.equals(current.get())) {
                return;
            }
            Thread.sleep(10L);
        }
        AtomicReference<@Nullable String> terminal = new AtomicReference<>();
        EdtDispatcher.executeAndWait(() -> terminal.set(findNamed(
                Objects.requireNonNull(panelReference.get()),
                "instanceJsonStatus",
                JLabel.class).getText()));
        assertEquals(expected, terminal.get());
    }

    /// Finds one named component of the requested type in a Swing subtree.
    ///
    /// @param root component-tree root
    /// @param name required component name
    /// @param type required component type
    /// @param <T> component type
    /// @return matching component
    private static <T extends JComponent> T findNamed(
            Container root,
            String name,
            Class<T> type) {
        for (Component child : root.getComponents()) {
            if (type.isInstance(child) && name.equals(child.getName())) {
                return type.cast(child);
            }
            if (child instanceof Container container) {
                @Nullable T nested = findNamedOrNull(container, name, type);
                if (nested != null) {
                    return nested;
                }
            }
        }
        throw new AssertionError("Component not found: " + name);
    }

    /// Recursively finds one optional named component.
    ///
    /// @param root component subtree
    /// @param name required component name
    /// @param type required component type
    /// @param <T> component type
    /// @return matching component, or null
    private static <T extends JComponent> @Nullable T findNamedOrNull(
            Container root,
            String name,
            Class<T> type) {
        for (Component child : root.getComponents()) {
            if (type.isInstance(child) && name.equals(child.getName())) {
                return type.cast(child);
            }
            if (child instanceof Container container) {
                @Nullable T nested = findNamedOrNull(container, name, type);
                if (nested != null) {
                    return nested;
                }
            }
        }
        return null;
    }
}
