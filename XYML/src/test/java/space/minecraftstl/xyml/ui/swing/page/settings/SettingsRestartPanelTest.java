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
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;
import space.minecraftstl.xyml.util.i18n.SupportedLocale;

import javax.swing.JButton;
import javax.swing.JLabel;
import java.awt.Component;
import java.awt.Container;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests restart-sensitive baseline tracking and retryable button interaction.
@NotNullByDefault
public final class SettingsRestartPanelTest {
    /// Deterministic text used by focused restart-control tests.
    private static final SettingsRestartStrings STRINGS = new SettingsRestartStrings(
            "Restart after changing language or April Fools",
            "Restart required",
            "Restart now",
            "Restarting",
            "Restart failed");

    /// Language and April Fools changes enable restart, while restoring the baseline disables it again.
    @Test
    public void tracksOnlyDifferencesFromTheActiveProcessBaseline() {
        RecordingRestartCommand command = new RecordingRestartCommand();
        SettingsRestartPanel panel = onEventDispatchThread(
                () -> new SettingsRestartPanel(STRINGS, command, active -> { }));

        onEventDispatchThread(() -> {
            JButton restart = findComponent(panel, "settingsRestartAction", JButton.class);
            JLabel status = findComponent(panel, "settingsRestartStatus", JLabel.class);
            panel.updateSettings(SupportedLocale.DEFAULT, false);
            assertAll(
                    () -> assertFalse(panel.isRestartRequired()),
                    () -> assertFalse(restart.isEnabled()),
                    () -> assertEquals(STRINGS.promptText(), status.getText()));

            panel.updateSettings(SupportedLocale.getLocale(Locale.SIMPLIFIED_CHINESE), false);
            assertAll(
                    () -> assertTrue(panel.isRestartRequired()),
                    () -> assertTrue(restart.isEnabled()),
                    () -> assertEquals(STRINGS.requiredText(), status.getText()));

            panel.updateSettings(SupportedLocale.DEFAULT, false);
            assertFalse(restart.isEnabled());

            panel.updateSettings(SupportedLocale.DEFAULT, true);
            assertAll(
                    () -> assertTrue(panel.isRestartRequired()),
                    () -> assertTrue(restart.isEnabled()));
            panel.close();
        });
    }

    /// Corner-radius rows use the same baseline and enabled-state contract as the general restart row.
    @Test
    public void tracksCornerRadiusBaseline() {
        RecordingRestartCommand command = new RecordingRestartCommand();
        SettingsRestartPanel panel = onEventDispatchThread(
                () -> new SettingsRestartPanel(STRINGS, command, active -> { }));

        onEventDispatchThread(() -> {
            JButton restart = findComponent(panel, "settingsRestartAction", JButton.class);
            JLabel status = findComponent(panel, "settingsRestartStatus", JLabel.class);
            panel.updateCornerRadius(6);
            assertAll(
                    () -> assertFalse(panel.isRestartRequired()),
                    () -> assertFalse(restart.isEnabled()),
                    () -> assertEquals(STRINGS.promptText(), status.getText()));

            panel.updateCornerRadius(9);
            assertAll(
                    () -> assertTrue(panel.isRestartRequired()),
                    () -> assertTrue(restart.isEnabled()),
                    () -> assertEquals(STRINGS.requiredText(), status.getText()));

            panel.updateCornerRadius(6);
            assertFalse(restart.isEnabled());
            panel.close();
        });
    }

    /// A failed injected command restores the restart action and never invokes a real process launcher.
    @Test
    public void exposesProgressAndAllowsRetryAfterFailure() {
        RecordingRestartCommand command = new RecordingRestartCommand();
        List<Boolean> activity = new ArrayList<>();
        SettingsRestartPanel panel = onEventDispatchThread(
                () -> new SettingsRestartPanel(STRINGS, command, activity::add));

        onEventDispatchThread(() -> {
            panel.updateSettings(SupportedLocale.DEFAULT, false);
            panel.updateSettings(SupportedLocale.DEFAULT, true);
            JButton restart = findComponent(panel, "settingsRestartAction", JButton.class);
            JLabel status = findComponent(panel, "settingsRestartStatus", JLabel.class);
            restart.doClick();

            assertAll(
                    () -> assertSame(panel, command.owner),
                    () -> assertFalse(restart.isEnabled()),
                    () -> assertEquals(STRINGS.inProgressText(), status.getText()),
                    () -> assertEquals(List.of(true), activity));
        });

        command.completion.completeExceptionally(new IOException("expected test failure"));
        EdtDispatcher.executeAndWait(() -> { });

        onEventDispatchThread(() -> {
            JButton restart = findComponent(panel, "settingsRestartAction", JButton.class);
            JLabel status = findComponent(panel, "settingsRestartStatus", JLabel.class);
            assertAll(
                    () -> assertTrue(restart.isEnabled()),
                    () -> assertEquals(STRINGS.failedText(), status.getText()),
                    () -> assertEquals(List.of(true, false), activity));
            panel.close();
        });
    }

    /// Finds one named component in the restart-control hierarchy.
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

    /// Searches a nested component hierarchy without throwing on a miss.
    ///
    /// @param root current hierarchy root
    /// @param name stable component name
    /// @param type expected component type
    /// @param <T> component type
    /// @return matching component, or null when absent
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

    /// Runs one value-producing operation synchronously on the Swing event dispatch thread.
    ///
    /// @param action EDT-confined action
    /// @param <T> returned value type
    /// @return action result
    private static <T extends Object> T onEventDispatchThread(Supplier<T> action) {
        AtomicReference<@Nullable T> result = new AtomicReference<>();
        EdtDispatcher.executeAndWait(() -> result.set(Objects.requireNonNull(action, "action").get()));
        return Objects.requireNonNull(result.get());
    }

    /// Runs one operation synchronously on the Swing event dispatch thread.
    ///
    /// @param action EDT-confined operation
    private static void onEventDispatchThread(Runnable action) {
        EdtDispatcher.executeAndWait(Objects.requireNonNull(action, "action"));
    }

    /// Restart command fake that exposes one manually controlled completion.
    @NotNullByDefault
    private static final class RecordingRestartCommand implements SettingsRestartCommand {
        /// Completion returned to the control under test.
        private final CompletableFuture<@Nullable Void> completion = new CompletableFuture<>();

        /// Exact owner supplied by the restart control, or null before invocation.
        private @Nullable Component owner;

        /// Records the owner without performing process or window operations.
        ///
        /// @param owner restart-control owner
        /// @return manually controlled completion
        @Override
        public CompletionStage<@Nullable Void> restart(Component owner) {
            this.owner = Objects.requireNonNull(owner, "owner");
            return completion;
        }
    }
}
