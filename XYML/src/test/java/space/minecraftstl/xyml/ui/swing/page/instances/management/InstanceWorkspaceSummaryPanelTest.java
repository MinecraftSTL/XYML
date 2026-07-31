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
package space.minecraftstl.xyml.ui.swing.page.instances.management;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import space.minecraftstl.xyml.game.launch.LaunchSession;
import space.minecraftstl.xyml.observable.Subscription;
import space.minecraftstl.xyml.observable.ValueChange;
import space.minecraftstl.xyml.observable.ValueChangeListener;
import space.minecraftstl.xyml.observable.property.ReadOnlyProperty;
import space.minecraftstl.xyml.observable.property.SimpleObjectProperty;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;
import space.minecraftstl.xyml.ui.swing.page.home.HomeModel;
import space.minecraftstl.xyml.ui.swing.page.home.HomeSnapshot;

import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import java.awt.Component;
import java.awt.Container;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies real summary data flow, shared overview projection, commands, and subscription cleanup.
@NotNullByDefault
final class InstanceWorkspaceSummaryPanelTest {
    /// Temporary overview paths.
    @TempDir
    private Path temporaryDirectory;

    /// Home and overview changes update the header while close releases only the borrowed subscription.
    @Test
    void appliesHomeAndOverviewStateAndReleasesSubscription() {
        RecordingHomeModel homeModel = new RecordingHomeModel();
        AtomicInteger refreshes = new AtomicInteger();
        AtomicInteger folderOpens = new AtomicInteger();
        AtomicReference<@Nullable Component> menuInvoker = new AtomicReference<>();
        AtomicReference<@Nullable InstanceWorkspaceSummaryPanel> panelReference = new AtomicReference<>();
        try {
            EdtDispatcher.executeAndWait(() -> panelReference.set(new InstanceWorkspaceSummaryPanel(
                    homeModel,
                    "instance",
                    refreshes::incrementAndGet,
                    folderOpens::incrementAndGet,
                    menuInvoker::set)));
            InstanceWorkspaceSummaryPanel panel = Objects.requireNonNull(panelReference.get());

            EdtDispatcher.executeAndWait(() -> {
                JLabel name = requireNamed(panel, "instanceWorkspaceName", JLabel.class);
                JLabel version = requireNamed(panel, "instanceWorkspaceVersion", JLabel.class);
                JLabel status = requireNamed(panel, "instanceWorkspaceStatus", JLabel.class);
                JButton refresh = requireNamed(panel, "instanceWorkspaceRefresh", JButton.class);
                JButton open = requireNamed(panel, "instanceWorkspaceOpenFolder", JButton.class);
                JButton more = requireNamed(panel, "instanceWorkspaceMore", JButton.class);
                assertEquals("Test instance", name.getText());
                assertEquals("Ready", status.getText());
                assertFalse(version.getText().isBlank());
                assertTrue(refresh.isEnabled());
                assertFalse(open.isEnabled());
                assertFalse(more.isEnabled());

                ImageIcon icon = new ImageIcon(new BufferedImage(40, 40, BufferedImage.TYPE_INT_ARGB));
                panel.applyOverviewSummary(new InstanceOverviewSummary(
                        "instance",
                        "1.21.1",
                        temporaryDirectory.resolve("versions/instance"),
                        temporaryDirectory.resolve("game"),
                        icon));
                assertEquals("1.21.1", version.getText());
                assertSame(icon, requireNamed(panel, "instanceWorkspaceIcon", JLabel.class).getIcon());
                assertTrue(open.isEnabled());
                assertTrue(more.isEnabled());

                refresh.doClick();
                open.doClick();
                more.doClick();
                assertEquals(1, refreshes.get());
                assertEquals(1, folderOpens.get());
                assertSame(more, menuInvoker.get());
            });

            homeModel.publish(new HomeSnapshot(
                    "Player",
                    "Offline",
                    "Renamed instance",
                    "Directory",
                    "Launching",
                    false,
                    true,
                    false));
            EdtDispatcher.executeAndWait(() -> {
                assertEquals(
                        "Renamed instance",
                        requireNamed(panel, "instanceWorkspaceName", JLabel.class).getText());
                assertEquals(
                        "Launching",
                        requireNamed(panel, "instanceWorkspaceStatus", JLabel.class).getText());
            });

            panel.close();
            panel.close();
            EdtDispatcher.executeAndWait(() -> { });
            assertFalse(homeModel.subscriptionActive.get());
            assertFalse(homeModel.closed.get());
        } finally {
            @Nullable InstanceWorkspaceSummaryPanel panel = panelReference.get();
            if (panel != null) {
                panel.close();
            }
        }
    }

    /// Finds one named descendant or fails immediately.
    ///
    /// @param root component tree root
    /// @param name stable component name
    /// @param type required component type
    /// @param <T> component type
    /// @return required matching component
    private static <T extends JComponent> T requireNamed(
            Container root,
            String name,
            Class<T> type) {
        @Nullable T component = findNamed(root, name, type);
        assertNotNull(component);
        return Objects.requireNonNull(component);
    }

    /// Finds one named descendant of the requested Swing component type.
    ///
    /// @param root component tree root
    /// @param name stable component name
    /// @param type required component type
    /// @param <T> component type
    /// @return matching component, or `null` when absent
    private static <T extends JComponent> @Nullable T findNamed(
            Container root,
            String name,
            Class<T> type) {
        for (Component component : root.getComponents()) {
            if (type.isInstance(component) && name.equals(component.getName())) {
                return type.cast(component);
            }
            if (component instanceof Container child) {
                @Nullable T nested = findNamed(child, name, type);
                if (nested != null) {
                    return nested;
                }
            }
        }
        return null;
    }

    /// Mutable home model publishing test invalidations from arbitrary threads.
    @NotNullByDefault
    private static final class RecordingHomeModel implements HomeModel, AutoCloseable {
        /// Current immutable snapshot.
        private final AtomicReference<HomeSnapshot> snapshot = new AtomicReference<>(new HomeSnapshot(
                "Player",
                "Offline",
                "Test instance",
                "Directory",
                "Ready",
                true,
                false,
                true));

        /// Current listener, or `null` outside a live subscription.
        private final AtomicReference<@Nullable ValueChangeListener<HomeSnapshot>> listener = new AtomicReference<>();

        /// Stable empty launch-session property.
        private final SimpleObjectProperty<Optional<LaunchSession>> launchSession =
                new SimpleObjectProperty<>(this, "launchSession", Optional.empty());

        /// Whether the summary subscription is still active.
        private final AtomicBoolean subscriptionActive = new AtomicBoolean();

        /// Whether application ownership explicitly closed this model.
        private final AtomicBoolean closed = new AtomicBoolean();

        /// Returns the latest test snapshot.
        ///
        /// @return current immutable snapshot
        @Override
        public HomeSnapshot snapshot() {
            return snapshot.get();
        }

        /// Installs the one test listener.
        ///
        /// @param newListener listener receiving future invalidations
        /// @return independently cancellable registration
        @Override
        public Subscription subscribe(ValueChangeListener<HomeSnapshot> newListener) {
            ValueChangeListener<HomeSnapshot> requiredListener =
                    Objects.requireNonNull(newListener, "listener");
            if (!listener.compareAndSet(null, requiredListener)) {
                throw new IllegalStateException("Listener already registered");
            }
            subscriptionActive.set(true);
            return Subscription.create(() -> {
                listener.compareAndSet(requiredListener, null);
                subscriptionActive.set(false);
            });
        }

        /// Returns the stable empty launch session.
        ///
        /// @return empty launch-session property
        @Override
        public ReadOnlyProperty<Optional<LaunchSession>> launchSessionProperty() {
            return launchSession;
        }

        /// Ignores unused account selection.
        @Override
        public void selectAccount() {
        }

        /// Ignores unused instance selection.
        @Override
        public void selectInstance() {
        }

        /// Ignores unused instance creation.
        @Override
        public void addInstance() {
        }

        /// Ignores unused launch commands.
        @Override
        public void launch() {
        }

        /// Replaces the current snapshot and synchronously invalidates the registered listener.
        ///
        /// @param replacement new immutable snapshot
        private void publish(HomeSnapshot replacement) {
            HomeSnapshot previous = snapshot.getAndSet(Objects.requireNonNull(replacement, "replacement"));
            @Nullable ValueChangeListener<HomeSnapshot> currentListener = listener.get();
            if (currentListener != null) {
                currentListener.onChange(new ValueChange<>(this, previous, replacement));
            }
        }

        /// Records application-owned model closure.
        @Override
        public void close() {
            closed.set(true);
        }
    }
}
