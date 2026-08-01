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
package space.minecraftstl.xyml.ui.swing.page.settings;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import space.minecraftstl.xyml.observable.Subscription;
import space.minecraftstl.xyml.observable.ValueChangeListener;
import space.minecraftstl.xyml.observable.ValueChangeSupport;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;
import space.minecraftstl.xyml.ui.swing.FontAntialiasingMode;

import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;
import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies lazy local-font loading and complete font-settings persistence without native windows.
@NotNullByDefault
public final class FontSettingsPanelTest {
    /// Restart text shared by focused font settings tests.
    private static final SettingsRestartStrings RESTART_STRINGS = new SettingsRestartStrings(
            "Applies after restart",
            "Restart required",
            "Restart now",
            "Restarting",
            "Restart failed");

    /// Installed fonts are enumerated only after first expansion and never on the event dispatch thread.
    @Test
    public void loadsInstalledFamiliesLazilyOffTheEventDispatchThread() {
        RecordingFontStore store = new RecordingFontStore(snapshot(null, null, 12.0, FontAntialiasingMode.AUTO));
        QueuedExecutor executor = new QueuedExecutor();
        AtomicInteger catalogCalls = new AtomicInteger();
        List<@Nullable String> appliedFamilies = new ArrayList<>();
        FontSettingsPanel panel = onEventDispatchThread(() -> new FontSettingsPanel(
                store,
                appliedFamilies::add,
                () -> {
                    assertFalse(SwingUtilities.isEventDispatchThread());
                    catalogCalls.incrementAndGet();
                    return List.of("Serif", "Monospaced");
                },
                executor,
                RESTART_STRINGS,
                owner -> CompletableFuture.completedFuture(null),
                active -> { }));

        assertEquals(1, appliedFamilies.size());
        assertNull(appliedFamilies.get(0));
        assertEquals(0, catalogCalls.get());
        onEventDispatchThread(() -> openPopup(panel.launcherFontControl()));
        assertEquals(0, catalogCalls.get());
        assertTrue(executor.hasQueuedTask());

        executor.runNext();
        EdtDispatcher.executeAndWait(() -> { });

        onEventDispatchThread(() -> {
            assertEquals(3, panel.launcherFontControl().getItemCount());
            assertEquals(3, panel.logFontControl().getItemCount());
            openPopup(panel.logFontControl());
            assertEquals(1, catalogCalls.get());
            panel.close();
        });
        assertTrue(store.closed);
    }

    /// Family, size, reset, and antialiasing controls persist their exact established semantics.
    @Test
    public void persistsFontSelectionsAndTracksAntialiasingRestart() {
        RecordingFontStore store = new RecordingFontStore(snapshot("Dialog", null, 12.0, FontAntialiasingMode.AUTO));
        List<@Nullable String> appliedFamilies = new ArrayList<>();
        FontSettingsPanel panel = onEventDispatchThread(() -> new FontSettingsPanel(
                store,
                appliedFamilies::add,
                () -> List.of("Serif", "Monospaced"),
                Runnable::run,
                RESTART_STRINGS,
                owner -> CompletableFuture.completedFuture(null),
                active -> { }));

        onEventDispatchThread(() -> {
            openPopup(panel.launcherFontControl());
            panel.launcherFontControl().setSelectedItem("Serif");
            panel.logFontControl().setSelectedItem("Monospaced");
            panel.logFontSizeControl().setValue(15.5);
            panel.antialiasingControl().setSelectedItem(FontAntialiasingMode.GRAY);

            FontSettingsSnapshot changed = store.snapshot();
            assertEquals("Serif", changed.launcherFontFamily());
            assertEquals("Monospaced", changed.logFontFamily());
            assertEquals(15.5, changed.logFontSize());
            assertEquals(FontAntialiasingMode.GRAY, changed.antialiasingMode());
            assertEquals(List.of("Dialog", "Serif"), appliedFamilies);
            assertEquals("Serif", findComponent(
                    panel,
                    "fontSettingsLauncherPreview",
                    JLabel.class).getFont().getFamily());
            assertEquals(15.5F, findComponent(
                    panel,
                    "fontSettingsLogPreview",
                    JLabel.class).getFont().getSize2D());
            assertTrue(findComponent(panel, "settingsRestartAction", JButton.class).isEnabled());

            findComponent(panel, "fontSettingsLauncherReset", JButton.class).doClick();
            findComponent(panel, "fontSettingsLogReset", JButton.class).doClick();
            assertNull(store.snapshot().launcherFontFamily());
            assertNull(store.snapshot().logFontFamily());
            assertEquals(15.5, store.snapshot().logFontSize());
            assertEquals(3, appliedFamilies.size());
            assertEquals("Dialog", appliedFamilies.get(0));
            assertEquals("Serif", appliedFamilies.get(1));
            assertNull(appliedFamilies.get(2));
            panel.close();
        });
    }

    /// Creates a writable snapshot for one focused test state.
    ///
    /// @param launcherFamily launcher family, or `null`
    /// @param logFamily game-log family, or `null`
    /// @param logSize game-log size
    /// @param mode antialiasing mode
    /// @return writable snapshot
    private static FontSettingsSnapshot snapshot(
            @Nullable String launcherFamily,
            @Nullable String logFamily,
            double logSize,
            FontAntialiasingMode mode) {
        return new FontSettingsSnapshot(launcherFamily, logFamily, logSize, mode, true, true);
    }

    /// Simulates one combo popup opening without creating a native popup window.
    ///
    /// @param box combo box whose listeners are notified
    private static void openPopup(JComboBox<?> box) {
        PopupMenuEvent event = new PopupMenuEvent(box);
        for (PopupMenuListener listener : box.getPopupMenuListeners()) {
            listener.popupMenuWillBecomeVisible(event);
        }
    }

    /// Finds one named component in a nested Swing hierarchy.
    ///
    /// @param root component hierarchy root
    /// @param name stable component name
    /// @param type expected component type
    /// @param <T> component type
    /// @return matching component
    private static <T extends Component> T findComponent(Container root, String name, Class<T> type) {
        for (Component child : root.getComponents()) {
            if (Objects.equals(name, child.getName()) && type.isInstance(child)) {
                return type.cast(child);
            }
            if (child instanceof Container container) {
                @Nullable T nested = findOptionalComponent(container, name, type);
                if (nested != null) {
                    return nested;
                }
            }
        }
        throw new IllegalArgumentException("Missing component: " + name);
    }

    /// Searches a nested hierarchy without throwing when the component is absent.
    ///
    /// @param root current hierarchy root
    /// @param name stable component name
    /// @param type expected component type
    /// @param <T> component type
    /// @return matching component, or `null`
    private static <T extends Component> @Nullable T findOptionalComponent(
            Container root,
            String name,
            Class<T> type) {
        for (Component child : root.getComponents()) {
            if (Objects.equals(name, child.getName()) && type.isInstance(child)) {
                return type.cast(child);
            }
            if (child instanceof Container container) {
                @Nullable T nested = findOptionalComponent(container, name, type);
                if (nested != null) {
                    return nested;
                }
            }
        }
        return null;
    }

    /// Runs one value-producing action synchronously on the event dispatch thread.
    ///
    /// @param action EDT-confined action
    /// @param <T> result type
    /// @return action result
    private static <T extends Object> T onEventDispatchThread(Supplier<T> action) {
        AtomicReference<@Nullable T> result = new AtomicReference<>();
        EdtDispatcher.executeAndWait(() -> result.set(Objects.requireNonNull(action, "action").get()));
        return Objects.requireNonNull(result.get());
    }

    /// Runs one action synchronously on the event dispatch thread.
    ///
    /// @param action EDT-confined action
    private static void onEventDispatchThread(Runnable action) {
        EdtDispatcher.executeAndWait(Objects.requireNonNull(action, "action"));
    }

    /// Writable in-memory store publishing every replacement synchronously.
    @NotNullByDefault
    private static final class RecordingFontStore implements FontSettingsStore {
        /// Snapshot publisher owned by the fake.
        private final ValueChangeSupport<FontSettingsSnapshot> changes = new ValueChangeSupport<>(this);

        /// Current fake state.
        private FontSettingsSnapshot current;

        /// Whether the panel closed this owned fake.
        private boolean closed;

        /// Creates the fake from one initial snapshot.
        ///
        /// @param initialSnapshot initial state
        private RecordingFontStore(FontSettingsSnapshot initialSnapshot) {
            current = Objects.requireNonNull(initialSnapshot, "initialSnapshot");
        }

        /// Returns current fake state.
        ///
        /// @return current snapshot
        @Override
        public FontSettingsSnapshot snapshot() {
            return current;
        }

        /// Registers one fake snapshot listener.
        ///
        /// @param listener listener
        /// @return listener subscription
        @Override
        public Subscription subscribe(ValueChangeListener<FontSettingsSnapshot> listener) {
            return changes.subscribe(Objects.requireNonNull(listener, "listener"));
        }

        /// Replaces the launcher family.
        ///
        /// @param family selected family, or `null`
        @Override
        public void setLauncherFontFamily(@Nullable String family) {
            replace(new FontSettingsSnapshot(
                    family,
                    current.logFontFamily(),
                    current.logFontSize(),
                    current.antialiasingMode(),
                    true,
                    true));
        }

        /// Replaces the log family.
        ///
        /// @param family selected family, or `null`
        @Override
        public void setLogFontFamily(@Nullable String family) {
            replace(new FontSettingsSnapshot(
                    current.launcherFontFamily(),
                    family,
                    current.logFontSize(),
                    current.antialiasingMode(),
                    true,
                    true));
        }

        /// Replaces the log size.
        ///
        /// @param size font size
        @Override
        public void setLogFontSize(double size) {
            replace(new FontSettingsSnapshot(
                    current.launcherFontFamily(),
                    current.logFontFamily(),
                    size,
                    current.antialiasingMode(),
                    true,
                    true));
        }

        /// Replaces the antialiasing mode.
        ///
        /// @param mode selected mode
        @Override
        public void setAntialiasingMode(FontAntialiasingMode mode) {
            replace(new FontSettingsSnapshot(
                    current.launcherFontFamily(),
                    current.logFontFamily(),
                    current.logFontSize(),
                    mode,
                    true,
                    true));
        }

        /// Marks this fake closed.
        @Override
        public void close() {
            closed = true;
        }

        /// Publishes one state replacement.
        ///
        /// @param replacement replacement state
        private void replace(FontSettingsSnapshot replacement) {
            FontSettingsSnapshot previous = current;
            current = Objects.requireNonNull(replacement, "replacement");
            changes.fireChange(previous, current);
        }
    }

    /// Executor fake that lets the test run one queued task on its own non-EDT thread.
    @NotNullByDefault
    private static final class QueuedExecutor implements Executor {
        /// Single pending font enumeration task.
        private @Nullable Runnable queuedTask;

        /// Captures one background task without running it inline.
        ///
        /// @param command submitted task
        @Override
        public void execute(Runnable command) {
            if (queuedTask != null) {
                throw new IllegalStateException("Only one font task may be queued");
            }
            queuedTask = Objects.requireNonNull(command, "command");
        }

        /// Returns whether one font task is waiting.
        ///
        /// @return whether a task is queued
        private boolean hasQueuedTask() {
            return queuedTask != null;
        }

        /// Runs and removes the queued task on the caller thread.
        private void runNext() {
            Runnable task = Objects.requireNonNull(queuedTask, "No font task was queued");
            queuedTask = null;
            task.run();
        }
    }
}
