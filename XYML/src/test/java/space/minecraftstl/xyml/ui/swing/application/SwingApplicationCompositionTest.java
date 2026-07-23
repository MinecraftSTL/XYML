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
package space.minecraftstl.xyml.ui.swing.application;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import space.minecraftstl.xyml.ui.swing.MotionPolicy;
import space.minecraftstl.xyml.ui.swing.SwingAnimator;
import space.minecraftstl.xyml.ui.swing.SwingDesignTokens;
import space.minecraftstl.xyml.ui.swing.SwingThemeManager;
import space.minecraftstl.xyml.ui.swing.SystemThemeDetector;
import space.minecraftstl.xyml.ui.swing.ThemeMode;
import space.minecraftstl.xyml.ui.swing.page.accounts.AccountsModel;
import space.minecraftstl.xyml.ui.swing.page.accounts.AccountsStrings;
import space.minecraftstl.xyml.ui.swing.page.home.HomeModel;
import space.minecraftstl.xyml.ui.swing.page.home.HomeStatusStrings;
import space.minecraftstl.xyml.ui.swing.page.home.HomeStrings;
import space.minecraftstl.xyml.ui.swing.page.instances.InstancesModel;
import space.minecraftstl.xyml.ui.swing.page.instances.InstancesStrings;
import space.minecraftstl.xyml.ui.swing.page.instances.RepositoryInstancesStatusStrings;
import space.minecraftstl.xyml.ui.swing.page.settings.AppearanceSettingsModel;
import space.minecraftstl.xyml.ui.swing.page.settings.AppearanceSettingsStrings;
import space.minecraftstl.xyml.ui.swing.shell.ShellPageFactory;
import space.minecraftstl.xyml.ui.swing.shell.ShellPageId;
import space.minecraftstl.xyml.ui.swing.shell.ShellPagePresentations;
import space.minecraftstl.xyml.ui.swing.task.TaskProgressStrings;

import javax.swing.JComponent;
import javax.swing.JPanel;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies composition factories and lifecycle without initializing JavaFX or creating a native frame.
@NotNullByDefault
class SwingApplicationCompositionTest {
    /// Confirms that pages stay factory-backed and navigation resolves through the created window.
    @Test
    void keepsPagesLazyAndRoutesNavigationThroughWindowReference() {
        AtomicInteger downloadsCreated = new AtomicInteger();
        AtomicReference<@Nullable Consumer<ShellPageId>> navigation = new AtomicReference<>();
        List<String> closeOrder = new ArrayList<>();
        List<CountingCloseable> resources = createResources(closeOrder);
        RecordingWindowFactory windowFactory = new RecordingWindowFactory();

        SwingApplicationComposition composition = SwingApplicationComposition.createForCollaborators(
                navigateCommand -> {
                    navigation.set(navigateCommand);
                    return createModels(resources);
                },
                presentation(),
                () -> {
                    downloadsCreated.incrementAndGet();
                    return new JPanel();
                },
                themeManager(),
                new SwingAnimator(MotionPolicy.OFF, 16),
                windowFactory);

        RecordingWindow window = windowFactory.window();
        assertEquals(EnumSet.allOf(ShellPageId.class), window.pageFactories().keySet());
        assertEquals(0, downloadsCreated.get());

        composition.open();
        assertEquals(1, window.openCount());
        Objects.requireNonNull(navigation.get()).accept(ShellPageId.ACCOUNTS);
        assertEquals(List.of(ShellPageId.ACCOUNTS), window.navigations());

        JComponent downloads = window.createPage(ShellPageId.DOWNLOADS);
        assertTrue(downloads instanceof JPanel);
        assertEquals(1, downloadsCreated.get());

        composition.close();
        composition.close();
        assertTrue(composition.isClosed());
        assertEquals(1, window.closeCount());
        assertEquals(expectedCloseOrder(), closeOrder);
        for (CountingCloseable resource : resources) {
            assertEquals(1, resource.closeCount());
        }
        assertThrows(IllegalStateException.class, composition::open);
    }

    /// Confirms that native disposal triggers the same idempotent owned-resource lifecycle.
    @Test
    void nativeWindowClosureClosesOwnedResourcesOnce() {
        List<String> closeOrder = new ArrayList<>();
        List<CountingCloseable> resources = createResources(closeOrder);
        RecordingWindowFactory windowFactory = new RecordingWindowFactory();
        SwingApplicationComposition composition = SwingApplicationComposition.createForCollaborators(
                navigateCommand -> createModels(resources),
                presentation(),
                JPanel::new,
                themeManager(),
                new SwingAnimator(MotionPolicy.OFF, 16),
                windowFactory);

        windowFactory.window().close();
        composition.close();

        assertTrue(composition.isClosed());
        assertEquals(1, windowFactory.window().closeCount());
        assertEquals(expectedCloseOrder(), closeOrder);
        assertFalse(resources.stream().anyMatch(resource -> resource.closeCount() != 1));
    }

    /// Creates no-call proxies that fail if composition accidentally instantiates a page.
    ///
    /// @param resources ordered lifecycle probes
    /// @return model bundle containing proxy contracts and explicit resources
    private static SwingApplicationPageModels createModels(List<? extends AutoCloseable> resources) {
        return new SwingApplicationPageModels(
                noCallModel(HomeModel.class),
                noCallModel(InstancesModel.class),
                noCallModel(AccountsModel.class),
                noCallModel(AppearanceSettingsModel.class),
                resources);
    }

    /// Creates an interface proxy that reports any unexpected page-model invocation.
    ///
    /// @param modelType page-model interface
    /// @param <T> page-model contract type
    /// @return proxy that fails on every method invocation
    private static <T> T noCallModel(Class<T> modelType) {
        Object proxy = Proxy.newProxyInstance(
                modelType.getClassLoader(),
                new Class<?>[]{modelType},
                (ignoredProxy, method, ignoredArguments) -> {
                    throw new AssertionError("Page model was used eagerly: " + method.getName());
                });
        return modelType.cast(proxy);
    }

    /// Creates the explicit test-only localized presentation.
    ///
    /// @return complete presentation fixture
    private static SwingApplicationPresentation presentation() {
        return new SwingApplicationPresentation(
                "XYML test",
                ShellPagePresentations.englishFallback(),
                new HomeStrings(
                        "Home", "Account", "None", "Instance", "None", "Add", "Launch", "Launching", "Back"),
                new HomeStatusStrings("Ready", "Select account", "Select instance"),
                new InstancesStrings("Instances", "Refresh", "Refreshing", "Add", "Manage", "Empty"),
                new RepositoryInstancesStatusStrings("Loading", "Ready", "Refreshing", "Failed", "Unknown"),
                new AccountsStrings("Accounts", "Add", "Empty"),
                new AppearanceSettingsStrings(
                        "Appearance", "Theme", "System", "Light", "Dark", "Radius", "Animations"),
                Duration.ZERO,
                new TaskProgressStrings(
                        "Waiting", "Running", "Completed", "Failed", "Cancelled",
                        "Task progress", "Cancel", "Show details", "Hide details"),
                Duration.ZERO);
    }

    /// Creates a non-initialized theme manager suitable for the fake window.
    ///
    /// @return explicit test theme manager
    private static SwingThemeManager themeManager() {
        return new SwingThemeManager(
                ThemeMode.SYSTEM,
                new SwingDesignTokens(8),
                SystemThemeDetector.lightFallback());
    }

    /// Creates lifecycle probes in production model-before-store close order.
    ///
    /// @param closeOrder shared close-order recorder
    /// @return seven distinct model and store probes
    private static List<CountingCloseable> createResources(List<String> closeOrder) {
        return List.of(
                new CountingCloseable("home-model", closeOrder),
                new CountingCloseable("instances-model", closeOrder),
                new CountingCloseable("accounts-model", closeOrder),
                new CountingCloseable("appearance-model", closeOrder),
                new CountingCloseable("home-store", closeOrder),
                new CountingCloseable("accounts-store", closeOrder),
                new CountingCloseable("appearance-store", closeOrder));
    }

    /// Returns the expected dependency-safe lifecycle order.
    ///
    /// @return immutable expected close order
    private static @Unmodifiable List<String> expectedCloseOrder() {
        return List.of(
                "home-model",
                "instances-model",
                "accounts-model",
                "appearance-model",
                "home-store",
                "accounts-store",
                "appearance-store");
    }

    /// Records the factories supplied by the composition without creating a native frame.
    @NotNullByDefault
    private static final class RecordingWindowFactory implements SwingApplicationWindowFactory {
        /// Window created by the factory, or null before composition.
        private @Nullable RecordingWindow window;

        /// Captures complete lazy factories without invoking them.
        ///
        /// @param themeManager unused explicit theme collaborator
        /// @param pageFactories immutable complete lazy page table
        /// @param presentation unused explicit presentation collaborator
        /// @param animator unused explicit animator collaborator
        /// @return recording window
        @Override
        public SwingApplicationWindow createWindow(
                SwingThemeManager themeManager,
                @Unmodifiable Map<ShellPageId, ShellPageFactory<? extends JComponent>> pageFactories,
                SwingApplicationPresentation presentation,
                SwingAnimator animator) {
            Objects.requireNonNull(themeManager, "themeManager");
            Objects.requireNonNull(presentation, "presentation");
            Objects.requireNonNull(animator, "animator");
            window = new RecordingWindow(pageFactories);
            return window;
        }

        /// Returns the window created during composition.
        ///
        /// @return recording window
        private RecordingWindow window() {
            return Objects.requireNonNull(window, "window was not created");
        }
    }

    /// Provides a deterministic headless application-window lifecycle.
    @NotNullByDefault
    private static final class RecordingWindow implements SwingApplicationWindow {
        /// Complete immutable page factory table.
        private final @Unmodifiable Map<ShellPageId, ShellPageFactory<? extends JComponent>> pageFactories;

        /// Destinations requested through the shell navigation reference.
        private final List<ShellPageId> navigations = new ArrayList<>();

        /// Number of successful open calls.
        private final AtomicInteger openCount = new AtomicInteger();

        /// Number of first close transitions.
        private final AtomicInteger closeCount = new AtomicInteger();

        /// Idempotent closed state.
        private final AtomicBoolean closed = new AtomicBoolean();

        /// Composition cleanup callback, or null before registration.
        private @Nullable Runnable closedHandler;

        /// Creates a fake window without invoking any page factory.
        ///
        /// @param pageFactories complete lazy page table
        private RecordingWindow(
                @Unmodifiable Map<ShellPageId, ShellPageFactory<? extends JComponent>> pageFactories) {
            this.pageFactories = Map.copyOf(pageFactories);
        }

        /// Installs the composition cleanup callback.
        ///
        /// @param handler callback invoked after closure
        @Override
        public void setClosedHandler(Runnable handler) {
            if (closedHandler != null) {
                throw new IllegalStateException("closed handler already installed");
            }
            closedHandler = Objects.requireNonNull(handler, "handler");
        }

        /// Records one open request.
        @Override
        public void open() {
            if (closed.get()) {
                throw new IllegalStateException("window is closed");
            }
            openCount.incrementAndGet();
        }

        /// Records a shell-backed navigation request.
        ///
        /// @param page requested destination
        @Override
        public void navigateTo(ShellPageId page) {
            if (closed.get()) {
                throw new IllegalStateException("window is closed");
            }
            navigations.add(Objects.requireNonNull(page, "page"));
        }

        /// Closes once and reports the native-close event to the composition.
        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                closeCount.incrementAndGet();
                @Nullable Runnable handler = closedHandler;
                if (handler != null) {
                    handler.run();
                }
            }
        }

        /// Creates one page only when the test explicitly asks for it.
        ///
        /// @param page requested page
        /// @return component created by the captured lazy factory
        private JComponent createPage(ShellPageId page) {
            return Objects.requireNonNull(pageFactories.get(page), "missing page factory").createPage();
        }

        /// Returns the immutable page factory table.
        ///
        /// @return page factory table
        private @Unmodifiable Map<ShellPageId, ShellPageFactory<? extends JComponent>> pageFactories() {
            return pageFactories;
        }

        /// Returns recorded navigation requests.
        ///
        /// @return immutable navigation snapshot
        private @Unmodifiable List<ShellPageId> navigations() {
            return List.copyOf(navigations);
        }

        /// Returns the number of successful open requests.
        ///
        /// @return open count
        private int openCount() {
            return openCount.get();
        }

        /// Returns the number of first close transitions.
        ///
        /// @return close count
        private int closeCount() {
            return closeCount.get();
        }
    }

    /// Records whether one owned model or store is closed more than once.
    @NotNullByDefault
    private static final class CountingCloseable implements AutoCloseable {
        /// Stable resource name appended to the close-order recorder.
        private final String name;

        /// Shared close-order recorder.
        private final List<String> closeOrder;

        /// Number of close invocations.
        private final AtomicInteger closeCount = new AtomicInteger();

        /// Creates one lifecycle probe.
        ///
        /// @param name stable resource name
        /// @param closeOrder shared close-order recorder
        private CountingCloseable(String name, List<String> closeOrder) {
            this.name = Objects.requireNonNull(name, "name");
            this.closeOrder = Objects.requireNonNull(closeOrder, "closeOrder");
        }

        /// Records one close invocation and its ordering.
        @Override
        public void close() {
            closeCount.incrementAndGet();
            closeOrder.add(name);
        }

        /// Returns the number of close invocations.
        ///
        /// @return close count
        private int closeCount() {
            return closeCount.get();
        }
    }
}
