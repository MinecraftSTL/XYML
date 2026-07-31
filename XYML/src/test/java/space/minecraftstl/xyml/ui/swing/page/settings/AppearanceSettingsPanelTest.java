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
import space.minecraftstl.xyml.setting.BackgroundType;
import space.minecraftstl.xyml.theme.BuiltinBackground;
import space.minecraftstl.xyml.theme.NetworkBackgroundImageCachePolicy;
import space.minecraftstl.xyml.theme.ThemeBrightnessPreference;
import space.minecraftstl.xyml.theme.ThemeColor;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;

import javax.swing.AbstractButton;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JSlider;
import javax.swing.SwingUtilities;
import javax.swing.JTextField;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.image.BufferedImage;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
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
            "Appearance", "Theme mode", "Theme", "System", "Light", "Dark", "Corner radius", "Animations",
            AppearanceBackgroundStrings.englishFallback());

    /// User controls persist theme, aligned radius, and animation changes through the model.
    @Test
    public void writesUserChangesWithoutInventingRadiusValues() {
        FakeAppearanceSettingsModel model = new FakeAppearanceSettingsModel(snapshot(
                ThemeBrightnessPreference.SYSTEM, 6, true, true));
        AppearanceSettingsPanel panel = onEventDispatchThread(() -> new AppearanceSettingsPanel(model, STRINGS));

        onEventDispatchThread(() -> {
            findComponent(panel, "appearanceThemeDARK", AbstractButton.class).doClick();
            findComponent(panel, "appearanceCornerRadius", JSlider.class).setValue(10);
            findComponent(panel, "appearanceAnimations", AbstractButton.class).doClick();

            assertAll(
                    () -> assertEquals(
                            ThemeBrightnessPreference.DARK,
                            panel.selectedBrightnessPreference()),
                    () -> assertEquals(9, panel.displayedCornerRadius()),
                    () -> assertFalse(panel.areAnimationsEnabled()),
                    () -> assertEquals(
                            ThemeBrightnessPreference.DARK,
                            model.snapshot().brightnessPreference()),
                    () -> assertEquals(9, model.snapshot().cornerRadius()),
                    () -> assertFalse(model.snapshot().animationsEnabled()));
            panel.close();
        });
    }

    /// Slider dragging previews the aligned value but persists only when adjustment finishes.
    @Test
    public void commitsCornerRadiusAfterSliderAdjustmentFinishes() {
        FakeAppearanceSettingsModel model = new FakeAppearanceSettingsModel(snapshot(
                ThemeBrightnessPreference.SYSTEM, 6, true, true));
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

    /// The appearance restart row sits below the slider and activates only after the radius leaves its baseline.
    @Test
    public void showsCornerRadiusRestartActionBelowSlider() {
        FakeAppearanceSettingsModel model = new FakeAppearanceSettingsModel(snapshot(
                ThemeBrightnessPreference.SYSTEM, 6, true, true));
        AppearanceSettingsPanel panel = onEventDispatchThread(() -> new AppearanceSettingsPanel(model, STRINGS));
        SettingsRestartStrings restartStrings = new SettingsRestartStrings(
                "Applies After Restart",
                "Changes saved. Restart to apply them.",
                "Restart Now",
                "Restarting",
                "Restart failed");
        CompletableFuture<@Nullable Void> restartCompletion = new CompletableFuture<>();

        onEventDispatchThread(() -> {
            panel.attachCornerRadiusRestartPanel(
                    restartStrings,
                    owner -> restartCompletion,
                    active -> { });
            panel.setSize(new Dimension(760, 900));
            layoutRecursively(panel);

            SettingsRestartPanel restartPanel = findComponent(
                    panel,
                    "appearanceCornerRadiusRestart",
                    SettingsRestartPanel.class);
            JSlider slider = findComponent(panel, "appearanceCornerRadius", JSlider.class);
            JButton restart = findComponent(restartPanel, "settingsRestartAction", JButton.class);
            JLabel status = findComponent(restartPanel, "settingsRestartStatus", JLabel.class);
            Point restartLocation = SwingUtilities.convertPoint(
                    restartPanel.getParent(),
                    restartPanel.getLocation(),
                    panel);
            Point sliderLocation = SwingUtilities.convertPoint(
                    slider.getParent(),
                    slider.getLocation(),
                    panel);
            assertAll(
                    () -> assertTrue(
                            restartLocation.y >= sliderLocation.y + slider.getHeight(),
                            () -> "restart=" + restartLocation + ", slider=" + sliderLocation),
                    () -> assertEquals(sliderLocation.x, restartLocation.x),
                    () -> assertFalse(restart.isEnabled()),
                    () -> assertEquals(restartStrings.promptText(), status.getText()));

            slider.setValue(9);
            assertAll(
                    () -> assertTrue(restart.isEnabled()),
                    () -> assertEquals(restartStrings.requiredText(), status.getText()));

            restart.doClick();
            assertAll(
                    () -> assertFalse(slider.isEnabled()),
                    () -> assertFalse(restart.isEnabled()),
                    () -> assertEquals(restartStrings.inProgressText(), status.getText()));
            restartCompletion.completeExceptionally(new IllegalStateException("expected restart failure"));
            assertAll(
                    () -> assertTrue(slider.isEnabled()),
                    () -> assertTrue(restart.isEnabled()),
                    () -> assertEquals(restartStrings.failedText(), status.getText()));
            panel.close();
        });
    }

    /// The dedicated theme segment removes the brightness override instead of masquerading as system mode.
    @Test
    public void selectsThemeBrightnessInheritance() {
        FakeAppearanceSettingsModel model = new FakeAppearanceSettingsModel(snapshot(
                ThemeBrightnessPreference.SYSTEM, 6, true, true));
        AppearanceSettingsPanel panel = onEventDispatchThread(() -> new AppearanceSettingsPanel(model, STRINGS));

        onEventDispatchThread(() -> {
            findComponent(panel, "appearanceThemeTHEME", AbstractButton.class).doClick();

            assertAll(
                    () -> assertEquals(
                            ThemeBrightnessPreference.THEME,
                            panel.selectedBrightnessPreference()),
                    () -> assertEquals(
                            ThemeBrightnessPreference.THEME,
                            model.snapshot().brightnessPreference()));
            panel.close();
        });
    }

    /// Worker-published settings are coalesced to the latest snapshot and applied on the EDT.
    @Test
    public void appliesWorkerPublishedSnapshot() throws InterruptedException {
        FakeAppearanceSettingsModel model = new FakeAppearanceSettingsModel(snapshot(
                ThemeBrightnessPreference.LIGHT, 3, true, true));
        AppearanceSettingsPanel panel = onEventDispatchThread(() -> new AppearanceSettingsPanel(model, STRINGS));
        AppearanceSettingsSnapshot replacement = snapshot(
                ThemeBrightnessPreference.DARK,
                15,
                false,
                false);

        Thread publisher = new Thread(() -> model.publish(replacement), "appearance-settings-test-publisher");
        publisher.start();
        publisher.join();
        EdtDispatcher.executeAndWait(() -> { });

        onEventDispatchThread(() -> {
            assertAll(
                    () -> assertEquals(replacement, panel.displayedSnapshot()),
                    () -> assertEquals(
                            ThemeBrightnessPreference.DARK,
                            panel.selectedBrightnessPreference()),
                    () -> assertEquals(15, panel.displayedCornerRadius()),
                    () -> assertFalse(panel.areAnimationsEnabled()),
                    () -> assertFalse(findComponent(
                            panel, "appearanceCornerRadius", JSlider.class).isEnabled()));
            panel.close();
        });
    }

    /// A complete network-background snapshot is reflected exactly without generating persistence echoes.
    @Test
    public void reflectsCompleteBackgroundSnapshotAndDependencies() {
        BackgroundAppearanceSettings background = new BackgroundAppearanceSettings(
                BackgroundType.NETWORK,
                BuiltinBackground.WALLPAPER_2016_02_25.id(),
                "C:/wallpapers",
                "https://example.invalid/background.png",
                "#123456",
                0.72,
                NetworkBackgroundImageCachePolicy.DISABLED,
                true,
                true,
                false);
        FakeAppearanceSettingsModel model = new FakeAppearanceSettingsModel(snapshot(
                ThemeBrightnessPreference.SYSTEM,
                6,
                true,
                background,
                true));
        AppearanceSettingsPanel panel = onEventDispatchThread(() -> new AppearanceSettingsPanel(model, STRINGS));

        onEventDispatchThread(() -> {
            assertAll(
                    () -> assertEquals(background, panel.displayedBackground()),
                    () -> assertEquals(
                            BackgroundType.NETWORK,
                            findComponent(panel, "appearanceBackgroundType", JComboBox.class).getSelectedItem()),
                    () -> assertEquals(
                            background.networkImageUrl(),
                            findComponent(panel, "appearanceNetworkBackgroundUrl", JTextField.class).getText()),
                    () -> assertTrue(findComponent(
                            panel, "appearanceNetworkBackgroundUrl", JTextField.class).isEnabled()),
                    () -> assertFalse(findComponent(
                            panel, "appearanceLocalBackgroundPath", JTextField.class).isEnabled()),
                    () -> assertFalse(findComponent(
                            panel, "appearanceBackgroundOpacity", JSlider.class).isEnabled()),
                    () -> assertTrue(findComponent(
                            panel, "appearanceWindowTransparent", AbstractButton.class).isEnabled()),
                    () -> assertEquals(0, model.backgroundWriteCount()));
            panel.close();
        });
    }

    /// Direct path input and every background option persist complete replacement objects without losing inactive data.
    @Test
    public void commitsCompleteBackgroundControlsAtomically() {
        BackgroundAppearanceSettings initialBackground = defaultBackground();
        initialBackground = new BackgroundAppearanceSettings(
                BackgroundType.BUILTIN,
                initialBackground.builtinBackgroundId(),
                initialBackground.customImagePath(),
                initialBackground.networkImageUrl(),
                initialBackground.customPaint(),
                initialBackground.opacity(),
                initialBackground.networkCachePolicy(),
                initialBackground.windowTransparent(),
                true,
                false);
        FakeAppearanceSettingsModel model = new FakeAppearanceSettingsModel(snapshot(
                ThemeBrightnessPreference.SYSTEM,
                6,
                true,
                initialBackground,
                true));
        AppearanceSettingsPanel panel = onEventDispatchThread(() -> new AppearanceSettingsPanel(model, STRINGS));

        onEventDispatchThread(() -> {
            findComponent(panel, "appearanceBuiltinBackground", JComboBox.class)
                    .setSelectedItem(BuiltinBackground.WALLPAPER_2015_06_22.id());

            findComponent(panel, "appearanceBackgroundType", JComboBox.class)
                    .setSelectedItem(BackgroundType.PAINT);
            JTextField primaryPaint = findComponent(
                    panel, "appearanceBackgroundPaint", JTextField.class);
            primaryPaint.setText("#315A77");
            primaryPaint.postActionEvent();

            findComponent(panel, "appearanceBackgroundType", JComboBox.class)
                    .setSelectedItem(BackgroundType.CUSTOM);
            JTextField localPath = findComponent(
                    panel, "appearanceLocalBackgroundPath", JTextField.class);
            localPath.setText("D:/Pictures/launcher backgrounds");
            int writesBeforePathCommit = model.backgroundWriteCount();
            localPath.postActionEvent();
            assertEquals(writesBeforePathCommit + 1, model.backgroundWriteCount());

            findComponent(panel, "appearanceBackgroundType", JComboBox.class)
                    .setSelectedItem(BackgroundType.NETWORK);
            JTextField networkUrl = findComponent(
                    panel, "appearanceNetworkBackgroundUrl", JTextField.class);
            networkUrl.setText("https://cdn.example.invalid/xyml.png");
            networkUrl.postActionEvent();
            findComponent(panel, "appearanceNetworkBackgroundCache", JComboBox.class)
                    .setSelectedItem(NetworkBackgroundImageCachePolicy.DISABLED);

            findComponent(panel, "appearanceBackgroundOpacityOverridden", AbstractButton.class).doClick();
            findComponent(panel, "appearanceBackgroundOpacity", JSlider.class).setValue(35);
            findComponent(panel, "appearanceWindowTransparent", AbstractButton.class).doClick();

            BackgroundAppearanceSettings replacement = model.snapshot().background();
            assertAll(
                    () -> assertEquals(BackgroundType.NETWORK, replacement.type()),
                    () -> assertEquals(
                            BuiltinBackground.WALLPAPER_2015_06_22.id(),
                            replacement.builtinBackgroundId()),
                    () -> assertEquals("D:/Pictures/launcher backgrounds", replacement.customImagePath()),
                    () -> assertEquals(
                            "https://cdn.example.invalid/xyml.png",
                            replacement.networkImageUrl()),
                    () -> assertEquals("#315A77", replacement.customPaint()),
                    () -> assertEquals(0.35, replacement.opacity()),
                    () -> assertEquals(
                            NetworkBackgroundImageCachePolicy.DISABLED,
                            replacement.networkCachePolicy()),
                    () -> assertTrue(replacement.windowTransparent()),
                    () -> assertTrue(replacement.sourceOverridden()),
                    () -> assertTrue(replacement.opacityOverridden()),
                    () -> assertEquals(replacement, panel.displayedBackground()));
            panel.close();
        });
    }

    /// A custom launcher theme color and its selected-theme override persist as complete replacements.
    @Test
    public void commitsCompleteThemeColorControlsAtomically() {
        FakeAppearanceSettingsModel model = new FakeAppearanceSettingsModel(snapshot(
                ThemeBrightnessPreference.SYSTEM, 6, true, true));
        AppearanceSettingsPanel panel = onEventDispatchThread(() -> new AppearanceSettingsPanel(model, STRINGS));

        onEventDispatchThread(() -> {
            findComponent(panel, "appearanceThemeColorOverridden", AbstractButton.class).doClick();
            JTextField customColor = findComponent(
                    panel, "appearanceCustomThemeColor", JTextField.class);
            customColor.setText("#2E7D32");
            customColor.postActionEvent();

            ThemeColorAppearanceSettings replacement = model.snapshot().themeColor();
            assertAll(
                    () -> assertEquals("#2E7D32", replacement.customColor().color()),
                    () -> assertTrue(replacement.overridden()),
                    () -> assertEquals(replacement, panel.displayedThemeColor()),
                    () -> assertTrue(model.themeColorWriteCount() >= 2));
            panel.close();
        });
    }

    /// Editing a neighboring theme-color control preserves the persisted name of an unchanged standard seed.
    @Test
    public void preservesUnchangedNamedThemeColor() {
        FakeAppearanceSettingsModel model = new FakeAppearanceSettingsModel(snapshot(
                ThemeBrightnessPreference.SYSTEM, 6, true, true));
        AppearanceSettingsPanel panel = onEventDispatchThread(() -> new AppearanceSettingsPanel(model, STRINGS));

        onEventDispatchThread(() -> {
            findComponent(panel, "appearanceThemeColorOverridden", AbstractButton.class).doClick();

            ThemeColor customColor = model.snapshot().themeColor().customColor();
            assertAll(
                    () -> assertEquals(ThemeColor.DEFAULT.name(), customColor.name()),
                    () -> assertEquals(ThemeColor.DEFAULT.color(), customColor.color()));
            panel.close();
        });
    }

    /// Disabling a custom-color override remains possible while the inactive text field contains invalid text.
    @Test
    public void invalidCustomColorDoesNotBlockReturningToThemeColor() {
        AppearanceSettingsSnapshot initial = new AppearanceSettingsSnapshot(
                ThemeBrightnessPreference.SYSTEM,
                6,
                0,
                18,
                3,
                true,
                new ThemeColorAppearanceSettings(ThemeColor.DEFAULT, true),
                defaultBackground(),
                true);
        FakeAppearanceSettingsModel model = new FakeAppearanceSettingsModel(initial);
        AppearanceSettingsPanel panel = onEventDispatchThread(() -> new AppearanceSettingsPanel(model, STRINGS));

        onEventDispatchThread(() -> {
            JTextField customColor = findComponent(panel, "appearanceCustomThemeColor", JTextField.class);
            customColor.setText("not-a-color");
            customColor.postActionEvent();
            findComponent(panel, "appearanceThemeColorOverridden", AbstractButton.class).doClick();

            assertAll(
                    () -> assertFalse(model.snapshot().themeColor().overridden()),
                    () -> assertEquals(ThemeColor.DEFAULT, model.snapshot().themeColor().customColor()));
            panel.close();
        });
    }

    /// The complete settings surface paints visible structure at a constrained desktop size.
    @Test
    public void paintsNonBlankResponsiveSurface() {
        FakeAppearanceSettingsModel model = new FakeAppearanceSettingsModel(snapshot(
                ThemeBrightnessPreference.SYSTEM, 6, true, true));
        AppearanceSettingsPanel panel = onEventDispatchThread(() -> new AppearanceSettingsPanel(model, STRINGS));

        BufferedImage image = onEventDispatchThread(() -> {
            Dimension size = new Dimension(760, 900);
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
    /// @param preference selected brightness preference
    /// @param radius current aligned radius
    /// @param animations whether animations are enabled
    /// @param writable whether controls are writable
    /// @return immutable settings snapshot
    private static AppearanceSettingsSnapshot snapshot(
            ThemeBrightnessPreference preference,
            int radius,
            boolean animations,
            boolean writable) {
        return snapshot(preference, radius, animations, defaultBackground(), writable);
    }

    /// Creates a snapshot with an explicit complete background replacement.
    ///
    /// @param preference selected brightness preference
    /// @param radius current aligned radius
    /// @param animations whether animations are enabled
    /// @param background complete background settings
    /// @param writable whether controls are writable
    /// @return immutable settings snapshot
    private static AppearanceSettingsSnapshot snapshot(
            ThemeBrightnessPreference preference,
            int radius,
            boolean animations,
            BackgroundAppearanceSettings background,
            boolean writable) {
        return new AppearanceSettingsSnapshot(
                preference,
                radius,
                0,
                18,
                3,
                animations,
                background,
                writable);
    }

    /// Creates explicit test values matching launcher persistence defaults.
    ///
    /// @return complete background settings fixture
    private static BackgroundAppearanceSettings defaultBackground() {
        return new BackgroundAppearanceSettings(
                BackgroundType.DEFAULT,
                BuiltinBackground.FALLBACK.id(),
                "",
                "",
                null,
                1.0,
                NetworkBackgroundImageCachePolicy.ENABLED,
                false,
                false,
                false);
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

        /// Number of complete background replacement calls received from the panel.
        private int backgroundWrites;

        /// Number of complete theme-color replacement calls received from the panel.
        private int themeColorWrites;

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

        /// Replaces the four-state preference while preserving all other fields.
        @Override
        public void setThemeBrightnessPreference(ThemeBrightnessPreference preference) {
            AppearanceSettingsSnapshot value = snapshot();
            publish(new AppearanceSettingsSnapshot(
                    preference,
                    value.cornerRadius(),
                    value.minimumCornerRadius(),
                    value.maximumCornerRadius(),
                    value.cornerRadiusStep(),
                    value.animationsEnabled(),
                    value.themeColor(),
                    value.background(),
                    value.writable()));
        }

        /// Replaces the aligned corner radius while preserving all other fields.
        @Override
        public void setCornerRadius(int cornerRadius) {
            AppearanceSettingsSnapshot value = snapshot();
            publish(new AppearanceSettingsSnapshot(
                    value.brightnessPreference(),
                    cornerRadius,
                    value.minimumCornerRadius(),
                    value.maximumCornerRadius(),
                    value.cornerRadiusStep(),
                    value.animationsEnabled(),
                    value.themeColor(),
                    value.background(),
                    value.writable()));
        }

        /// Replaces the animation preference while preserving all other fields.
        @Override
        public void setAnimationsEnabled(boolean enabled) {
            AppearanceSettingsSnapshot value = snapshot();
            publish(new AppearanceSettingsSnapshot(
                    value.brightnessPreference(),
                    value.cornerRadius(),
                    value.minimumCornerRadius(),
                    value.maximumCornerRadius(),
                    value.cornerRadiusStep(),
                    enabled,
                    value.themeColor(),
                    value.background(),
                    value.writable()));
        }

        /// Replaces the complete theme-color configuration in one published snapshot.
        ///
        /// @param themeColor complete replacement theme-color settings
        @Override
        public void setThemeColorAppearance(ThemeColorAppearanceSettings themeColor) {
            AppearanceSettingsSnapshot value = snapshot();
            themeColorWrites++;
            publish(new AppearanceSettingsSnapshot(
                    value.brightnessPreference(),
                    value.cornerRadius(),
                    value.minimumCornerRadius(),
                    value.maximumCornerRadius(),
                    value.cornerRadiusStep(),
                    value.animationsEnabled(),
                    Objects.requireNonNull(themeColor, "themeColor"),
                    value.background(),
                    value.writable()));
        }

        /// Replaces the complete background in one published snapshot.
        ///
        /// @param background complete replacement background
        @Override
        public void setBackgroundAppearance(BackgroundAppearanceSettings background) {
            AppearanceSettingsSnapshot value = snapshot();
            backgroundWrites++;
            publish(new AppearanceSettingsSnapshot(
                    value.brightnessPreference(),
                    value.cornerRadius(),
                    value.minimumCornerRadius(),
                    value.maximumCornerRadius(),
                    value.cornerRadiusStep(),
                    value.animationsEnabled(),
                    value.themeColor(),
                    Objects.requireNonNull(background, "background"),
                    value.writable()));
        }

        /// Returns the number of complete background writes accepted by this fake.
        ///
        /// @return background replacement count
        private int backgroundWriteCount() {
            return backgroundWrites;
        }

        /// Returns the number of complete theme-color writes accepted by this fake.
        ///
        /// @return theme-color replacement count
        private int themeColorWriteCount() {
            return themeColorWrites;
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
