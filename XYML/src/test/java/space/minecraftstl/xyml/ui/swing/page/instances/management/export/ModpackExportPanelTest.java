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
package space.minecraftstl.xyml.ui.swing.page.instances.management.export;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import space.minecraftstl.xyml.game.export.ModpackExportTaskFactory;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;
import space.minecraftstl.xyml.ui.swing.task.TaskProgressStrings;

import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingUtilities;
import javax.swing.JTextField;
import javax.swing.JTree;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import java.awt.Component;
import java.awt.Container;
import java.awt.Rectangle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies that modpack export file selection remains local, lazy, and expansion-driven.
@NotNullByDefault
final class ModpackExportPanelTest {
    /// Temporary run directory whose nested child proves root indexing is not recursive.
    @TempDir
    private Path runDirectory;

    /// Construction performs no directory resolution; activation reads only the root and expansion reads children.
    @Test
    void enumeratesOnlyAfterActivationAndDirectoryExpansion() throws Exception {
        Path configDirectory = Files.createDirectories(runDirectory.resolve("config"));
        Files.writeString(runDirectory.resolve("top-level.txt"), "top-level");
        Files.writeString(configDirectory.resolve("settings.txt"), "settings");
        AtomicInteger resolverCalls = new AtomicInteger();
        QueuedExecutor executor = new QueuedExecutor();
        AtomicReference<@Nullable ModpackExportPanel> panelReference = new AtomicReference<>();

        EdtDispatcher.executeAndWait(() -> {
            ModpackExportPanel panel = new ModpackExportPanel(
                    ignored -> {
                        resolverCalls.incrementAndGet();
                        return runDirectory;
                    },
                    "instance",
                    unusedTaskFactory(),
                    fixedOutputChooser(runDirectory.resolve("bundle")),
                    executor,
                    TaskProgressStrings.english(),
                    null,
                    Duration.ZERO);
            panelReference.set(panel);
            assertEquals(0, resolverCalls.get());
            assertEquals(0, executor.pendingCount());

            panel.activate();

            assertEquals(0, resolverCalls.get());
            assertEquals(1, executor.pendingCount());
        });

        executor.runNext();
        AtomicReference<@Nullable JTree> treeReference = new AtomicReference<>();
        AtomicReference<@Nullable DefaultMutableTreeNode> configReference = new AtomicReference<>();
        EdtDispatcher.executeAndWait(() -> {
            ModpackExportPanel panel = Objects.requireNonNull(panelReference.get(), "panel");
            JTree tree = findNamed(panel, "modpackExportFiles", JTree.class);
            assertNotNull(tree);
            treeReference.set(tree);
            DefaultTreeModel model = (DefaultTreeModel) tree.getModel();
            DefaultMutableTreeNode root = (DefaultMutableTreeNode) model.getRoot();
            assertEquals(2, root.getChildCount());
            DefaultMutableTreeNode config = childNamed(root, "config");
            assertEquals(0, config.getChildCount());
            assertFalse(config.isLeaf());
            configReference.set(config);

            DefaultMutableTreeNode topLevel = childNamed(root, "top-level.txt");
            tree.setSelectionPath(new TreePath(topLevel.getPath()));
            JComboBox<?> formatBox = findNamed(panel, "modpackExportFormat", JComboBox.class);
            JButton exportButton = findNamed(panel, "modpackExportStart", JButton.class);
            JTextField versionField = findNamed(panel, "modpackExportVersion", JTextField.class);
            JButton chooseOutputButton = findNamed(panel, "modpackExportChooseOutput", JButton.class);
            assertEquals(4, formatBox.getItemCount());
            assertFalse(exportButton.isEnabled());
            versionField.setText("1.0.0");
            assertFalse(exportButton.isEnabled());
            chooseOutputButton.doClick();
            assertTrue(exportButton.isEnabled());
        });

        EdtDispatcher.executeAndWait(() -> {
            JTree tree = Objects.requireNonNull(treeReference.get(), "tree");
            DefaultMutableTreeNode config = Objects.requireNonNull(configReference.get(), "config");
            tree.expandPath(new TreePath(config.getPath()));
            assertEquals(1, executor.pendingCount());
        });

        executor.runNext();
        EdtDispatcher.executeAndWait(() -> {
            DefaultMutableTreeNode config = Objects.requireNonNull(configReference.get(), "config");
            assertEquals(1, config.getChildCount());
            Objects.requireNonNull(panelReference.get(), "panel").close();
        });
        assertEquals(1, resolverCalls.get());
    }

    /// Closing before a queued root scan begins prevents resolver access and releases the pending page work.
    @Test
    void closePreventsQueuedRootEnumeration() {
        AtomicInteger resolverCalls = new AtomicInteger();
        QueuedExecutor executor = new QueuedExecutor();
        AtomicReference<@Nullable ModpackExportPanel> panelReference = new AtomicReference<>();

        EdtDispatcher.executeAndWait(() -> {
            ModpackExportPanel panel = new ModpackExportPanel(
                    ignored -> {
                        resolverCalls.incrementAndGet();
                        return runDirectory;
                    },
                    "instance",
                    unusedTaskFactory(),
                    fixedOutputChooser(runDirectory.resolve("bundle")),
                    executor,
                    TaskProgressStrings.english(),
                    null,
                    Duration.ZERO);
            panelReference.set(panel);
            panel.activate();
            assertEquals(1, executor.pendingCount());
            panel.close();
        });

        executor.runNext();
        EdtDispatcher.executeAndWait(() -> { });
        assertEquals(0, resolverCalls.get());
        assertEquals(0, executor.pendingCount());
        assertNotNull(panelReference.get());
    }

    /// Keeps output selection reachable when the host leaves only a short export content area.
    @Test
    void constrainedHeightScrollsTheCompleteMetadataForm() {
        QueuedExecutor executor = new QueuedExecutor();
        AtomicReference<@Nullable ModpackExportPanel> panelReference = new AtomicReference<>();

        EdtDispatcher.executeAndWait(() -> {
            ModpackExportPanel panel = new ModpackExportPanel(
                    ignored -> runDirectory,
                    "instance",
                    unusedTaskFactory(),
                    fixedOutputChooser(runDirectory.resolve("bundle")),
                    executor,
                    TaskProgressStrings.english(),
                    null,
                    Duration.ZERO);
            panelReference.set(panel);
            panel.setSize(800, 300);
            layoutRecursively(panel);

            JScrollPane scroll = findNamed(panel, "modpackExportMetadataScroll", JScrollPane.class);
            JPanel metadata = findNamed(panel, "modpackExportMetadata", JPanel.class);
            JButton chooseOutput = findNamed(panel, "modpackExportChooseOutput", JButton.class);
            assertEquals(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER, scroll.getHorizontalScrollBarPolicy());
            assertFalse(scroll.isOpaque());
            assertFalse(scroll.getViewport().isOpaque());
            assertTrue(
                    scroll.getVerticalScrollBar().getMaximum()
                            > scroll.getVerticalScrollBar().getVisibleAmount());

            int bottom = scroll.getVerticalScrollBar().getMaximum()
                    - scroll.getVerticalScrollBar().getVisibleAmount();
            scroll.getVerticalScrollBar().setValue(bottom);
            Rectangle outputBounds = SwingUtilities.convertRectangle(
                    chooseOutput.getParent(),
                    chooseOutput.getBounds(),
                    metadata);
            assertTrue(scroll.getViewport().getViewRect().intersects(outputBounds));
            panel.close();
        });
        assertNotNull(panelReference.get());
        assertEquals(0, executor.pendingCount());
    }

    /// Recursively lays out one detached Swing component tree for geometry assertions.
    ///
    /// @param container root or nested component container
    private static void layoutRecursively(Container container) {
        container.doLayout();
        for (Component component : container.getComponents()) {
            if (component instanceof Container child) {
                layoutRecursively(child);
            }
        }
    }

    /// Creates a task factory that must not be called by this lazy-tree test.
    ///
    /// @return task factory failing the test if an export task is requested
    private static ModpackExportTaskFactory unusedTaskFactory() {
        return request -> {
            throw new AssertionError("Lazy file-tree verification must not create an export task");
        };
    }

    /// Creates a deterministic local target chooser that never opens a native dialog during a unit test.
    ///
    /// @param outputFile selected local archive path
    /// @return output chooser returning the supplied path
    private static ModpackExportPanel.OutputFileChooser fixedOutputChooser(Path outputFile) {
        Path selected = Objects.requireNonNull(outputFile, "outputFile");
        return (owner, format, suggestedFile) -> selected;
    }

    /// Finds one named child component recursively.
    ///
    /// @param root root component to search
    /// @param name deterministic component name
    /// @param type expected component type
    /// @param <T> expected component type
    /// @return named child component
    private static <T extends Component> T findNamed(Container root, String name, Class<T> type) {
        for (Component component : Objects.requireNonNull(root, "root").getComponents()) {
            if (name.equals(component.getName()) && type.isInstance(component)) {
                return type.cast(component);
            }
            if (component instanceof Container child) {
                try {
                    return findNamed(child, name, type);
                } catch (IllegalArgumentException ignored) {
                    // Continue with sibling components when this branch does not contain the named child.
                }
            }
        }
        throw new IllegalArgumentException("No component named " + name);
    }

    /// Returns one direct child node with an exact display name.
    ///
    /// @param parent parent node to inspect
    /// @param expectedName required node display name
    /// @return matching direct child node
    private static DefaultMutableTreeNode childNamed(DefaultMutableTreeNode parent, String expectedName) {
        for (int index = 0; index < parent.getChildCount(); index++) {
            DefaultMutableTreeNode child = (DefaultMutableTreeNode) parent.getChildAt(index);
            if (Objects.requireNonNull(expectedName, "expectedName").equals(child.toString())) {
                return child;
            }
        }
        throw new IllegalArgumentException("No child named " + expectedName);
    }

    /// Queues background tasks until the test explicitly runs them away from the EDT.
    @NotNullByDefault
    private static final class QueuedExecutor implements Executor {
        /// FIFO worker queue controlled by the test.
        private final ArrayDeque<Runnable> pending = new ArrayDeque<>();

        /// Adds one task without running it immediately.
        ///
        /// @param command queued background command
        @Override
        public void execute(Runnable command) {
            pending.add(Objects.requireNonNull(command, "command"));
        }

        /// Returns the number of queued background commands.
        ///
        /// @return pending task count
        int pendingCount() {
            return pending.size();
        }

        /// Runs one queued command on the calling non-EDT test thread.
        void runNext() {
            Objects.requireNonNull(pending.poll(), "pending command").run();
        }
    }
}
