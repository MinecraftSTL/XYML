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
import space.minecraftstl.xyml.theme.ThemeBrightnessPreference;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;
import space.minecraftstl.xyml.ui.swing.ThemeMode;

import javax.swing.AbstractButton;
import javax.swing.JSlider;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests appearance commands, worker-thread refresh, measured radius bounds, and off-screen rendering.
@NotNullByDefault
public final class AppearanceSettingsPanelTest {
    /// Localized text used by focused panel tests.
    private static final AppearanceSettingsStrings STRINGS = new AppearanceSettingsStrings(
            "Appearance", "Theme mode", "Theme", "System", "Light", "Dark", "Corner radius", "Animations");

    /// User controls persist theme, aligned radius, and animation changes through the model.
    @Test
    public void writesUserChangesWithoutInventingRadiusValues() {
        FakeAppearanceSettingsModel model = new FakeAppearanceSettingsModel(snapshot(
                ThemeMode.SYSTEM, 6, true, true));
        AppearanceSettingsPanel panel = onEventDispatchThread(() -> new AppearanceSettingsPanel(model, STRINGS));

        onEventDispatchThread(() -> {
            findComponent(panel, "appearanceThemeDARK", AbstractButton.class).doClick();
            findComponent(panel, "appearanceCornerRadius", JSlider.class).setValue(10);
            findComponent(panel, "appearanceAnimations", AbstractButton.class).doClick();

            assertAll(
                    () -> assertEquals(ThemeMode.DARK, panel.selectedThemeMode()),
                    () -> assertEquals(9, panel.displayedCornerRadius()),
                    () -> assertFalse(panel.areAnimationsEnabled()),
                    () -> assertEquals(ThemeMode.DARK, model.snapshot().themeMode()),
                    () -> assertEquals(9, model.snapshot().cornerRadius()),
                    () -> assertFalse(model.snapshot().animationsEnabled()));
            panel.close();
        });
    }

    /// Slider dragging previews the aligned value but persists only when adjustment finishes.
    @Test
    public void commitsCornerRadiusAfterSliderAdjustmentFinishes() {
        FakeAppearanceSettingsModel model = new FakeAppearanceSettingsModel(snapshot(
                ThemeMode.SYSTEM, 6, true, true));
        AppearanceSettingsPanel panel = onEventDispatchThread(() -> new AppearanceSettingsPanel(model, STRINGS));

        onEventDispatchThread(() -> {
            JSlider slider = findComponent(panel, "appearanceCornerRadius", JSlider.class);
            slider.setValueIsAdjusting(true);
            slider.setValue(10);
            assertAll(
                    () -> assertEquals(9, panel.displayedCornerRadius()),
                    () -> assertEquals(6, model.snapshot().cornerRadius()));

            slider.setValueIsAdjusting(false);
            assertEquals(9, model.snapshot().cornerRadius());
            panel.close();
        });
    }

    /// The dedicated theme segment removes the brightness override instead of masquerading as system mode.
    @Test
    public void selectsThemeBrightnessInheritance() {
        FakeAppearanceSettingsModel model = new FakeAppearanceSettingsModel(snapshot(
                ThemeMode.SYSTEM, 6, true, true));
        AppearanceSettingsPanel panel = onEventDispatchThread(() -> new AppearanceSettingsPanel(model, STRINGS));

        onEventDispatchThread(() -> {
            findComponent(panel, "appearanceThemeTHEME", AbstractButton.class).doClick();

            assertAll(
                    () -> assertEquals(
                            ThemeBrightnessPreference.THEME,
                            panel.selectedBrightnessPreference()),
                    () -> assertEquals(
                            ThemeBrightnessPreference.THEME,
                            model.snapshot().brightnessPreference()),
                    () -> assertEquals(ThemeMode.SYSTEM, panel.selectedThemeMode()));
            panel.close();
        });
    }

    /// Worker-published settings are coalesced to the latest snapshot and applied on the EDT.
    @Test
    public void appliesWorkerPublishedSnapshot() throws InterruptedException {
        FakeAppearanceSettingsModel model = new FakeAppearanceSettingsModel(snapshot(
                ThemeMode.LIGHT, 3, true, true));
        AppearanceSettingsPanel panel = onEventDispatchThread(() -> new AppearanceSettingsPanel(model, STRINGS));
        AppearanceSettingsSnapshot replacement = snapshot(ThemeMode.DARK, 15, false, false);

        Thread publisher = new Thread(() -> model.publish(replacement), "appearance-settings-test-publisher");
        publisher.start();
        publisher.join();
        EdtDispatcher.executeAndWait(() -> { });

        onEventDispatchThread(() -> {
            assertAll(
                    () -> assertEquals(replacement, panel.displayedSnapshot()),
                    () -> assertEquals(ThemeMode.DARK, panel.selectedThemeMode()),
                    () -> assertEquals(15, panel.displayedCornerRadius()),
                    () -> assertFalse(panel.areAnimationsEnabled()),
                    () -> assertFalse(findComponent(
                            panel, "appearanceCornerRadius", JSlider.class).isEnabled()));
            panel.close();
        });
    }

    /// The complete settings surface paints visible structure at a constrained desktop size.
    @Test
    public void paintsNonBlankResponsiveSurface() {
        FakeAppearanceSettingsModel model = new FakeAppearanceSettingsModel(snapshot(
                ThemeMode.SYSTEM, 6, true, true));
        AppearanceSettingsPanel panel = onEventDispatchThread(() -> new AppearanceSettingsPanel(model, STRINGS));

        BufferedImage image = onEventDispatchThread(() -> {
            Dimension size = new Dimension(760, 420);
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

    /// Creates a snapshot with a test-only radius grid from zero through eighteen in increments of three.
    ///
    /// @param mode selected theme mode
    /// @param radius current aligned radius
    /// @param animations whether animations are enabled
    /// @param writable whether controls are writable
    /// @return immutable settings snapshot
    private static AppearanceSettingsSnapshot snapshot(
            ThemeMode mode,
            int radius,
            boolean animations,
            boolean writable) {
        return new AppearanceSettingsSnapshot(mode, radius, 0, 18, 3, animations, writable);
    }

    /// Finds a named component with the requested type in a Swing hierarchy.
    ///
    /// @param root hierarchy root
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

    /// Searches a nested hierarchy without throwing when no component matches.
    ///
    /// @param root hierarchy root
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

    /// Lays out a component hierarchy before off-screen painting.
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
    /// @param image rendered image
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

    /// Thread-safe fake model that applies commands by publishing replacement snapshots.
    @NotNullByDefault
    private static final class FakeAppearanceSettingsModel implements AppearanceSettingsModel {
        /// Latest immutable settings state.
        private final AtomicReference<AppearanceSettingsSnapshot> current;

        /// Snapshot transition publisher.
        private final ValueChangeSupport<AppearanceSettingsSnapshot> changes = new ValueChangeSupport<>(this);

        /// Creates a fake model with initial settings.
        ///
        /// @param initialSnapshot initial state
        private FakeAppearanceSettingsModel(AppearanceSettingsSnapshot initialSnapshot) {
            current = new AtomicReference<>(initialSnapshot);
        }

        /// Returns the latest fake settings state.
        @Override
        public AppearanceSettingsSnapshot snapshot() {
            return current.get();
        }

        /// Registers a fake settings listener.
        @Override
        public Subscription subscribe(ValueChangeListener<AppearanceSettingsSnapshot> listener) {
            return changes.subscribe(listener);
        }

        /// Replaces the theme mode while preserving all other fields.
        @Override
        public void setThemeMode(ThemeMode themeMode) {
            AppearanceSettingsSnapshot value = snapshot();
            publish(new AppearanceSettingsSnapshot(
                    themeMode,
                    value.cornerRadius(),
                    value.minimumCornerRadius(),
                    value.maximumCornerRadius(),
                    value.cornerRadiusStep(),
                    value.animationsEnabled(),
                    value.writable()));
        }

        /// Replaces the four-state preference while preserving all other fields.
        @Override
        public void setThemeBrightnessPreference(ThemeBrightnessPreference preference) {
            AppearanceSettingsSnapshot value = snapshot();
            publish(new AppearanceSettingsSnapshot(
                    AppearanceSettingsSnapshot.compatibilityMode(preference),
                    value.cornerRadius(),
                    value.minimumCornerRadius(),
                    value.maximumCornerRadius(),
                    value.cornerRadiusStep(),
                    value.animationsEnabled(),
                    value.writable(),
                    preference));
        }

        /// Replaces the aligned corner radius while preserving all other fields.
        @Override
        public void setCornerRadius(int cornerRadius) {
            AppearanceSettingsSnapshot value = snapshot();
            publish(new AppearanceSettingsSnapshot(
                    value.themeMode(),
                    cornerRadius,
                    value.minimumCornerRadius(),
                    value.maximumCornerRadius(),
                    value.cornerRadiusStep(),
                    value.animationsEnabled(),
                    value.writable()));
        }

        /// Replaces the animation preference while preserving all other fields.
        @Override
        public void setAnimationsEnabled(boolean enabled) {
            AppearanceSettingsSnapshot value = snapshot();
            publish(new AppearanceSettingsSnapshot(
                    value.themeMode(),
                    value.cornerRadius(),
                    value.minimumCornerRadius(),
                    value.maximumCornerRadius(),
                    value.cornerRadiusStep(),
                    enabled,
                    value.writable()));
        }

        /// Publishes one replacement snapshot on the calling thread.
        ///
        /// @param replacement new settings state
        private void publish(AppearanceSettingsSnapshot replacement) {
            AppearanceSettingsSnapshot previous = current.getAndSet(replacement);
            changes.fireChange(previous, replacement);
        }
    }
}
