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
package space.minecraftstl.xyml.ui.swing.page.home;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import space.minecraftstl.xyml.game.launch.LaunchSession;
import space.minecraftstl.xyml.observable.Subscription;
import space.minecraftstl.xyml.observable.ValueChangeListener;
import space.minecraftstl.xyml.observable.ValueChangeSupport;
import space.minecraftstl.xyml.observable.property.ReadOnlyProperty;
import space.minecraftstl.xyml.observable.property.SimpleObjectProperty;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;

import javax.swing.AbstractButton;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests home commands, worker-thread state updates, and off-screen layout rendering.
@NotNullByDefault
public final class HomePanelTest {
    /// Localized strings used by the focused page tests.
    private static final HomeStrings STRINGS = new HomeStrings(
            "Play", "Account", "No account", "Instance", "No instance",
            "Add instance", "Launch", "Launching");

    /// Every visible command invokes its corresponding model command exactly once.
    @Test
    public void delegatesSelectionAndLaunchCommands() {
        FakeHomeModel model = new FakeHomeModel(readySnapshot());
        HomePanel panel = onEventDispatchThread(() -> new HomePanel(model, STRINGS));

        onEventDispatchThread(() -> {
            findButton(panel, "homeAccount").doClick();
            findButton(panel, "homeInstance").doClick();
            findButton(panel, "homeAddInstance").doClick();
            findButton(panel, "homeLaunch").doClick();

            assertAll(
                    () -> assertEquals(1, model.accountSelections.get()),
                    () -> assertEquals(1, model.instanceSelections.get()),
                    () -> assertEquals(1, model.instanceAdditions.get()),
                    () -> assertEquals(1, model.launches.get()));
            panel.close();
        });
    }

    /// A worker-published launching snapshot disables duplicate launch and selection commands on the EDT.
    @Test
    public void appliesWorkerPublishedLaunchingState() throws InterruptedException {
        FakeHomeModel model = new FakeHomeModel(readySnapshot());
        HomePanel panel = onEventDispatchThread(() -> new HomePanel(model, STRINGS));
        HomeSnapshot launching = new HomeSnapshot(
                "Alex", "Microsoft", "Long Modded Instance Name", "1.21.1 / Fabric",
                "Preparing game", false, true, false);

        Thread publisher = new Thread(() -> model.publish(launching), "home-panel-test-publisher");
        publisher.start();
        publisher.join();
        EdtDispatcher.executeAndWait(() -> { });

        onEventDispatchThread(() -> {
            AbstractButton launchButton = findButton(panel, "homeLaunch");
            assertAll(
                    () -> assertEquals(launching, panel.displayedSnapshot()),
                    () -> assertEquals("Launching", launchButton.getText()),
                    () -> assertFalse(launchButton.isEnabled()),
                    () -> assertFalse(findButton(panel, "homeAccount").isEnabled()),
                    () -> assertFalse(findButton(panel, "homeInstance").isEnabled()));
            panel.close();
        });
    }

    /// The page paints an opaque, varied surface at a constrained shell content size.
    @Test
    public void paintsNonBlankSurfaceWithLongSelectionText() {
        FakeHomeModel model = new FakeHomeModel(new HomeSnapshot(
                "A very long player account name that must remain inside the selector",
                "External authentication provider with long status",
                "A very long modded instance name that must be truncated by pixel width",
                "Minecraft 1.21.1 with a long loader description",
                "Ready", true, false, true));
        HomePanel panel = onEventDispatchThread(() -> new HomePanel(model, STRINGS));

        BufferedImage image = onEventDispatchThread(() -> {
            Dimension size = new Dimension(820, 520);
            panel.setSize(size);
            layoutRecursively(panel);
            BufferedImage rendered = new BufferedImage(size.width, size.height, BufferedImage.TYPE_INT_ARGB);
            Graphics2D graphics = rendered.createGraphics();
            try {
                panel.printAll(graphics);
            } finally {
                graphics.dispose();
            }
            panel.close();
            return rendered;
        });

        assertTrue(distinctColors(image).size() > 4);
    }

    /// Creates the normal selected-account and selected-instance launch state.
    ///
    /// @return ready home snapshot
    private static HomeSnapshot readySnapshot() {
        return new HomeSnapshot(
                "Steve", "Offline", "Minecraft 1.21", "Vanilla", "Ready", true, false, true);
    }

    /// Finds a named button in a Swing hierarchy.
    ///
    /// @param root hierarchy root
    /// @param name stable component name
    /// @return matching command button
    private static AbstractButton findButton(Container root, String name) {
        @Nullable AbstractButton result = findOptionalButton(root, name);
        if (result == null) {
            throw new IllegalArgumentException("Missing button: " + name);
        }
        return result;
    }

    /// Searches a hierarchy for a named button.
    ///
    /// @param root hierarchy root
    /// @param name stable component name
    /// @return matching button, or null when absent
    private static @Nullable AbstractButton findOptionalButton(Container root, String name) {
        for (Component child : root.getComponents()) {
            if (child instanceof AbstractButton button && Objects.equals(name, button.getName())) {
                return button;
            }
            if (child instanceof Container container) {
                @Nullable AbstractButton nested = findOptionalButton(container, name);
                if (nested != null) {
                    return nested;
                }
            }
        }
        return null;
    }

    /// Runs a value-producing operation synchronously on the EDT.
    ///
    /// @param operation operation to run
    /// @param <T> non-null result type
    /// @return operation result
    private static <T extends Object> T onEventDispatchThread(Supplier<T> operation) {
        AtomicReference<@Nullable T> result = new AtomicReference<>();
        EdtDispatcher.executeAndWait(() -> result.set(operation.get()));
        return Objects.requireNonNull(result.get(), "EDT operation did not return a result");
    }

    /// Runs an operation synchronously on the EDT.
    ///
    /// @param operation operation to run
    private static void onEventDispatchThread(Runnable operation) {
        EdtDispatcher.executeAndWait(operation);
    }

    /// Recursively lays out a component hierarchy before off-screen painting.
    ///
    /// @param container hierarchy root
    private static void layoutRecursively(Container container) {
        container.doLayout();
        for (Component child : container.getComponents()) {
            if (child instanceof Container nested) {
                layoutRecursively(nested);
            }
        }
    }

    /// Collects all pixel colors painted into an image.
    ///
    /// @param image rendered home page
    /// @return mutable distinct-color set
    private static Set<Integer> distinctColors(BufferedImage image) {
        Set<Integer> colors = new HashSet<>();
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                colors.add(image.getRGB(x, y));
            }
        }
        return colors;
    }

    /// Thread-safe fake home model with explicit command counters.
    @NotNullByDefault
    private static final class FakeHomeModel implements HomeModel {
        /// Latest immutable home state.
        private final AtomicReference<HomeSnapshot> current;

        /// Home snapshot transition publisher.
        private final ValueChangeSupport<HomeSnapshot> changes = new ValueChangeSupport<>(this);

        /// Optional fake launch session retained for the home-view contract.
        private final SimpleObjectProperty<Optional<LaunchSession>> launchSession =
                new SimpleObjectProperty<>(this, "launchSession", Optional.empty());

        /// Account-selection command count.
        private final AtomicInteger accountSelections = new AtomicInteger();

        /// Instance-selection command count.
        private final AtomicInteger instanceSelections = new AtomicInteger();

        /// New-instance command count.
        private final AtomicInteger instanceAdditions = new AtomicInteger();

        /// Launch command count.
        private final AtomicInteger launches = new AtomicInteger();

        /// Creates a fake model with initial state.
        ///
        /// @param initialSnapshot initial home state
        private FakeHomeModel(HomeSnapshot initialSnapshot) {
            current = new AtomicReference<>(initialSnapshot);
        }

        /// Returns the latest fake home state.
        @Override
        public HomeSnapshot snapshot() {
            return current.get();
        }

        /// Registers a fake home-state listener.
        @Override
        public Subscription subscribe(ValueChangeListener<HomeSnapshot> listener) {
            return changes.subscribe(listener);
        }

        /// Returns the optional fake launch-session property.
        @Override
        public ReadOnlyProperty<Optional<LaunchSession>> launchSessionProperty() {
            return launchSession;
        }

        /// Records account-selection invocation.
        @Override
        public void selectAccount() {
            accountSelections.incrementAndGet();
        }

        /// Records instance-selection invocation.
        @Override
        public void selectInstance() {
            instanceSelections.incrementAndGet();
        }

        /// Records new-instance invocation.
        @Override
        public void addInstance() {
            instanceAdditions.incrementAndGet();
        }

        /// Records launch invocation.
        @Override
        public void launch() {
            launches.incrementAndGet();
        }

        /// Publishes one replacement snapshot on the calling thread.
        ///
        /// @param replacement new home state
        private void publish(HomeSnapshot replacement) {
            HomeSnapshot previous = current.getAndSet(replacement);
            changes.fireChange(previous, replacement);
        }
    }
}
