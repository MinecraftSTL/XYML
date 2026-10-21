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
package space.minecraftstl.xyml.ui.swing.shell;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import space.minecraftstl.xyml.observable.Subscription;
import space.minecraftstl.xyml.task.Task;
import space.minecraftstl.xyml.task.TaskExecutionRegistry;
import space.minecraftstl.xyml.task.TaskExecutionStatus;
import space.minecraftstl.xyml.task.TaskExecutor;
import space.minecraftstl.xyml.task.TaskResource;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;
import space.minecraftstl.xyml.ui.swing.MotionPolicy;
import space.minecraftstl.xyml.ui.swing.SwingAnimator;
import space.minecraftstl.xyml.ui.swing.page.tasks.TaskManagerPanel;
import space.minecraftstl.xyml.ui.swing.task.TaskLaunchController;
import space.minecraftstl.xyml.ui.swing.task.TaskProgressStrings;

import javax.swing.JComponent;
import javax.swing.JTabbedPane;
import java.time.Duration;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Exercises confirmed task submission through a real cached Swing shell without creating a native window.
@NotNullByDefault
public final class TaskNavigationIntegrationTest {
    /// First automatic navigation lazily creates the task page and repeated requests reuse it.
    @Test
    public void opensRunningTasksLazilyAndReusesCachedPage() {
        EdtDispatcher.executeAndWait(() -> {
            try (ShellFixture fixture = new ShellFixture()) {
                assertEquals(0, fixture.taskPageCreations.get());
                fixture.panel.openRunningTasks();
                TaskManagerPanel page = fixture.taskPage();
                JTabbedPane tabs = fixture.tabs();
                assertEquals(0, tabs.getSelectedIndex());
                for (int previousTab : new int[] {1, 2, 0, 2}) {
                    tabs.setSelectedIndex(previousTab);
                    fixture.panel.openRunningTasks();
                    assertEquals(0, tabs.getSelectedIndex());
                    assertSame(page, fixture.panel.activePage());
                    assertEquals(1, fixture.taskPageCreations.get());
                }
            }
        });
    }

    /// Confirmed launches reset history selection while sidebar navigation retains the user's choice.
    ///
    /// @throws InterruptedException when interrupted while awaiting a real executor's terminal publication
    @Test
    public void continuousLaunchAndManualNavigationPreservesOnlyManualSelection() throws InterruptedException {
        try (ShellFixture fixture = new ShellFixture()) {
            TaskLaunchController controller = new TaskLaunchController(fixture.panel::openRunningTasks);
            AtomicInteger dismissals = new AtomicInteger();
            launchAndAwait(fixture.registry, () ->
                    controller.launch(completedExecutor(), "First fixture", dismissals::incrementAndGet));
            for (int historyTab : new int[] {1, 2}) {
                EdtDispatcher.executeAndWait(() -> {
                    TaskManagerPanel page = fixture.taskPage();
                    JTabbedPane tabs = fixture.tabs();
                    assertEquals(0, tabs.getSelectedIndex());
                    tabs.setSelectedIndex(historyTab);
                    fixture.panel.navigationButton(ShellPageId.ACCOUNTS).doClick(0);
                    fixture.panel.navigationButton(ShellPageId.TASKS).doClick(0);
                    assertSame(page, fixture.panel.activePage());
                    assertEquals(historyTab, tabs.getSelectedIndex());
                });
                launchAndAwait(fixture.registry, () ->
                        controller.launch(completedExecutor(), "Next fixture", dismissals::incrementAndGet));
                EdtDispatcher.executeAndWait(() -> assertEquals(0, fixture.tabs().getSelectedIndex()));
            }
            assertEquals(3, dismissals.get());
            assertEquals(3, fixture.registry.snapshots().size());
            assertTrue(fixture.registry.snapshots().stream()
                    .allMatch(snapshot -> snapshot.status() == TaskExecutionStatus.SUCCEEDED));
            assertEquals(1, fixture.taskPageCreations.get());
        }
    }

    /// Returning home preserves the cached task page and its manually selected history tab.
    @Test
    public void homepageNavigationRetainsManualTaskTabAndSessionRecords() {
        EdtDispatcher.executeAndWait(() -> {
            try (ShellFixture fixture = new ShellFixture()) {
                fixture.panel.openRunningTasks();
                TaskManagerPanel page = fixture.taskPage();
                JTabbedPane tabs = fixture.tabs();
                ShellNavigationRail rail = assertInstanceOf(
                        ShellNavigationRail.class,
                        fixture.panel.navigationButton(ShellPageId.TASKS).getParent().getParent());
                for (int historyTab : new int[] {1, 2}) {
                    tabs.setSelectedIndex(historyTab);
                    rail.homeButton().doClick(0);
                    rail.homeButton().doClick(0);
                    assertNull(fixture.panel.selectedPage());
                    assertTrue(rail.homeButton().isSelected());
                    fixture.panel.navigationButton(ShellPageId.TASKS).doClick(0);
                    assertSame(page, fixture.panel.activePage());
                    assertEquals(historyTab, tabs.getSelectedIndex());
                    assertEquals(1, fixture.taskPageCreations.get());
                    assertTrue(fixture.registry.snapshots().isEmpty());
                }
            }
        });
    }

    /// Background completion never steals a history tab and local launches do not navigate away.
    ///
    /// @throws InterruptedException when interrupted while awaiting a real executor's terminal publication
    @Test
    public void localLaunchAndRegistryRefreshRetainSelectionAndDestination() throws InterruptedException {
        try (ShellFixture fixture = new ShellFixture()) {
            EdtDispatcher.executeAndWait(() -> {
                fixture.panel.openRunningTasks();
                fixture.panel.addNotify();
                assertTrue(fixture.taskPage().isShowing());
                fixture.tabs().setSelectedIndex(2);
            });
            TaskLaunchController controller = new TaskLaunchController(fixture.panel::openRunningTasks);
            launchAndAwait(fixture.registry, () ->
                    controller.launchWithoutNavigation(completedExecutor(), "Local fixture", () -> { }));
            EdtDispatcher.executeAndWait(() -> {
                assertEquals(2, fixture.tabs().getSelectedIndex());
                assertTrue(fixture.tabs().getTitleAt(1).endsWith(" (1)"));
                assertEquals(ShellPageId.TASKS, fixture.panel.selectedPage());
                fixture.panel.navigateTo(ShellPageId.ACCOUNTS);
            });
            launchAndAwait(fixture.registry, () ->
                    controller.launchWithoutNavigation(completedExecutor(), "Hidden fixture", () -> { }));
            EdtDispatcher.executeAndWait(() -> {
                assertEquals(ShellPageId.ACCOUNTS, fixture.panel.selectedPage());
                fixture.panel.navigateTo(ShellPageId.TASKS);
                assertEquals(2, fixture.tabs().getSelectedIndex());
                assertEquals(2, fixture.registry.snapshots().size());
                assertTrue(fixture.tabs().getTitleAt(1).endsWith(" (2)"));
            });
        }
    }

    /// A closed shell cannot recreate pages, and navigation failure leaves an already-started task intact.
    ///
    /// @throws InterruptedException when interrupted while awaiting a real executor's terminal publication
    @Test
    public void closedShellRejectsNavigationWithoutCancellingCommittedTask() throws InterruptedException {
        try (ShellFixture fixture = new ShellFixture()) {
            EdtDispatcher.executeAndWait(() -> {
                fixture.panel.openRunningTasks();
                TaskManagerPanel page = fixture.taskPage();
                fixture.panel.close();
                assertEquals(0, page.getComponentCount());
                assertThrows(IllegalStateException.class, fixture.panel::openRunningTasks);
            });
            TaskExecutor executor = completedExecutor();
            TaskLaunchController controller = new TaskLaunchController(fixture.panel::openRunningTasks);
            launchAndAwait(fixture.registry, () -> controller.launch(executor, "Committed", () -> { }));
            assertFalse(executor.isCancelled());
            assertEquals(TaskExecutionStatus.SUCCEEDED, fixture.registry.snapshots().get(0).status());
            assertEquals(1, fixture.taskPageCreations.get());
        }
    }

    /// A task that completed before its registered-task navigation still opens the running tab.
    ///
    /// @throws InterruptedException when interrupted while awaiting a real executor's terminal publication
    @Test
    public void completedRegisteredTaskStillOpensRunningTab() throws InterruptedException {
        try (ShellFixture fixture = new ShellFixture()) {
            TaskLaunchController controller = new TaskLaunchController(fixture.panel::openRunningTasks);
            launchAndAwait(fixture.registry, () ->
                    controller.launchWithoutNavigation(completedExecutor(), "Already complete", () -> { }));
            assertEquals(TaskExecutionStatus.SUCCEEDED, fixture.registry.snapshots().get(0).status());
            EdtDispatcher.executeAndWait(() -> {
                fixture.panel.navigateTo(ShellPageId.TASKS);
                fixture.tabs().setSelectedIndex(1);
                controller.openTaskManager(() -> { });
                assertEquals(0, fixture.tabs().getSelectedIndex());
                assertEquals(1, fixture.registry.snapshots().size());
            });
        }
    }

    /// Incompatible injected task pages fail explicitly without changing the current destination.
    @Test
    public void rejectsIncompatibleTaskPageBeforeNavigation() {
        EdtDispatcher.executeAndWait(() -> {
            EnumMap<ShellPageId, AtomicInteger> counts = AppShellPanelTest.creationCounts();
            try (AppShellPanel panel = createPanel(AppShellPanelTest.pageFactories(counts))) {
                @Nullable ShellPageId previousPage = panel.selectedPage();
                assertThrows(IllegalStateException.class, panel::openRunningTasks);
                assertEquals(previousPage, panel.selectedPage());
            }
        });
    }

    /// Creates a real executor with a direct empty body and non-conflicting read-only resources.
    ///
    /// @return stopped executor owned by the global execution registry after launch
    private static TaskExecutor completedExecutor() {
        return Task.runAsync("Navigation fixture", Runnable::run, () -> { })
                .setResources(TaskResource.global().readOnly())
                .executor();
    }

    /// Waits on the test thread for the real task's terminal state, never blocking the Swing EDT.
    ///
    /// @param registry session registry containing only this fixture's executions
    /// @param launch confirmed-task command to execute on the EDT
    /// @throws InterruptedException when interrupted during the bounded completion wait
    private static void launchAndAwait(TaskExecutionRegistry registry, Runnable launch) throws InterruptedException {
        int expectedRecords = registry.snapshots().size() + 1;
        CountDownLatch completed = new CountDownLatch(1);
        try (Subscription subscription = registry.subscribeVersioned(publication -> {
            if (publication.snapshots().size() >= expectedRecords
                    && publication.snapshots().stream().allMatch(snapshot -> snapshot.status().isTerminal())) {
                completed.countDown();
            }
        })) {
            EdtDispatcher.executeAndWait(launch);
            assertTrue(completed.await(10, TimeUnit.SECONDS), "Task did not reach a terminal state within 10 seconds");
            EdtDispatcher.executeAndWait(() -> { });
        }
    }

    /// Creates an EDT-confined shell with deterministic models and disabled transition animations.
    ///
    /// @param factories mutable factory table owned by this test
    /// @return shell owning any pages that it creates
    private static AppShellPanel createPanel(
            EnumMap<ShellPageId, ShellPageFactory<? extends JComponent>> factories) {
        AtomicReference<@Nullable AppShellPanel> reference = new AtomicReference<>();
        EdtDispatcher.executeAndWait(() -> reference.set(new AppShellPanel(
                "XYML",
                factories,
                ShellPagePresentations.englishFallback(),
                new ShellToolbarModels(
                        AppShellPanelTest.testHomeModel(),
                        AppShellPanelTest.testInstancesModel(),
                        AppShellPanelTest.testAccountsModel(),
                        AppShellPanelTest.testGameDirectories(),
                        ShellRecentSelections.transientSelections()),
                AppShellPanelTest.testHomeStrings(),
                TaskProgressStrings.english(),
                new SwingAnimator(MotionPolicy.OFF, 16),
                Duration.ZERO,
                Duration.ZERO)));
        return Objects.requireNonNull(reference.get(), "shell");
    }

    /// Owns a real shell, its lazy task-page counter, and session records for one isolated linear test.
    @NotNullByDefault
    private static final class ShellFixture implements AutoCloseable {
        /// Application registry cleared before and after this fixture's completed tasks.
        private final TaskExecutionRegistry registry = TaskExecutionRegistry.global();

        /// Number of task-page factory calls on the EDT.
        private final AtomicInteger taskPageCreations = new AtomicInteger();

        /// Shell owning all cached pages and their subscriptions.
        private final AppShellPanel panel;

        /// Creates a shell without eagerly instantiating the task page.
        private ShellFixture() {
            registry.clear();
            EnumMap<ShellPageId, ShellPageFactory<? extends JComponent>> factories =
                    AppShellPanelTest.pageFactories(AppShellPanelTest.creationCounts());
            factories.put(ShellPageId.TASKS, () -> {
                taskPageCreations.incrementAndGet();
                return new TaskManagerPanel(registry);
            });
            panel = createPanel(factories);
        }

        /// Returns the active cached task page after explicit task navigation.
        ///
        /// @return existing task page
        private TaskManagerPanel taskPage() {
            return (TaskManagerPanel) Objects.requireNonNull(panel.activePage(), "active task page");
        }

        /// Returns the task page's direct lifecycle tab widget for user-selection assertions.
        ///
        /// @return lifecycle tab container
        private JTabbedPane tabs() {
            return Arrays.stream(taskPage().getComponents())
                    .filter(JTabbedPane.class::isInstance)
                    .map(JTabbedPane.class::cast)
                    .findFirst()
                    .orElseThrow();
        }

        /// Releases page subscriptions and session records, including after test failures.
        @Override
        public void close() {
            try {
                EdtDispatcher.executeAndWait(() -> {
                    if (panel.isDisplayable()) {
                        panel.removeNotify();
                    }
                    panel.close();
                });
            } finally {
                registry.clear();
            }
        }
    }
}
