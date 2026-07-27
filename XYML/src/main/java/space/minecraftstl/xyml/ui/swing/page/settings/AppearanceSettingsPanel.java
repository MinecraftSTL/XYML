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

import net.miginfocom.swing.MigLayout;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import space.minecraftstl.xyml.observable.Subscription;
import space.minecraftstl.xyml.observable.ValueChange;
import space.minecraftstl.xyml.theme.ThemeBrightnessPreference;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;
import space.minecraftstl.xyml.ui.swing.SwingUiDispatcher;
import space.minecraftstl.xyml.ui.swing.ThemeMode;
import space.minecraftstl.xyml.ui.swing.page.settings.theme.ThemePackManagementPanel;

import javax.swing.ButtonGroup;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSeparator;
import javax.swing.JSlider;
import javax.swing.JToggleButton;
import java.awt.Font;
import java.util.EnumMap;
import java.util.Objects;

/// Renders appearance preferences with segmented theme controls, a measured-range radius slider, and animation toggle.
///
/// The panel must be created on the EDT and closed when its host page is discarded. Model updates may arrive on
/// any thread and are coalesced to the model's latest snapshot before Swing components are changed.
@NotNullByDefault
public final class AppearanceSettingsPanel extends JPanel implements AutoCloseable {
    /// Settings model supplying snapshots and persistence commands.
    private final AppearanceSettingsModel model;

    /// Localized page text.
    private final AppearanceSettingsStrings strings;

    /// Optional local theme-pack surface owned with this appearance page.
    private final @Nullable ThemePackManagementPanel themePackManagementPanel;

    /// Theme buttons keyed by persisted mode.
    private final EnumMap<ThemeBrightnessPreference, JToggleButton> themeButtons =
            new EnumMap<>(ThemeBrightnessPreference.class);

    /// Slider configured from model-provided radius bounds and increment.
    private final JSlider cornerRadiusSlider = new JSlider();

    /// Current radius value displayed beside the slider.
    private final JLabel cornerRadiusValue = new JLabel();

    /// Binary persisted animation preference.
    private final JCheckBox animationsEnabled = new JCheckBox();

    /// Subscription owned by this panel.
    private final Subscription modelSubscription;

    /// Snapshot currently represented by controls, or null before initial application.
    private @Nullable AppearanceSettingsSnapshot displayedSnapshot;

    /// Prevents programmatic component updates from writing back to the model.
    private boolean applyingSnapshot;

    /// Whether model resources have been released.
    private boolean closed;

    /// Creates an appearance settings panel on the EDT.
    ///
    /// @param model the toolkit-neutral settings model
    /// @param strings localized page text
    public AppearanceSettingsPanel(AppearanceSettingsModel model, AppearanceSettingsStrings strings) {
        this(model, strings, null);
    }

    /// Creates an appearance settings panel with an optional local theme-pack manager.
    ///
    /// @param model the toolkit-neutral settings model
    /// @param strings localized page text
    /// @param themePackManagementPanel local theme-pack surface to embed and own, or `null`
    public AppearanceSettingsPanel(
            AppearanceSettingsModel model,
            AppearanceSettingsStrings strings,
            @Nullable ThemePackManagementPanel themePackManagementPanel) {
        super(new MigLayout(
                "insets 20 24 24 24, fill, wrap 1",
                "[grow,fill]",
                "[]22[]18[]18[]18[]18[]18[]18[grow,fill]"));
        EdtDispatcher.requireEventDispatchThread();
        this.model = Objects.requireNonNull(model, "model");
        this.strings = Objects.requireNonNull(strings, "strings");
        this.themePackManagementPanel = themePackManagementPanel;

        configureComponents();
        modelSubscription = model.subscribe(this::modelChanged);
        applySnapshot(model.snapshot());
    }

    /// Returns the snapshot currently represented by the controls.
    ///
    /// @return displayed immutable settings state
    public AppearanceSettingsSnapshot displayedSnapshot() {
        EdtDispatcher.requireEventDispatchThread();
        return Objects.requireNonNull(displayedSnapshot, "initial settings snapshot was not applied");
    }

    /// Returns the selected theme mode for focused integration checks.
    ///
    /// @return selected light, dark, or system mode
    public ThemeMode selectedThemeMode() {
        EdtDispatcher.requireEventDispatchThread();
        return switch (selectedBrightnessPreference()) {
            case THEME, SYSTEM -> ThemeMode.SYSTEM;
            case LIGHT -> ThemeMode.LIGHT;
            case DARK -> ThemeMode.DARK;
        };
    }

    /// Returns the selected four-state brightness preference.
    ///
    /// @return theme, system, light, or dark preference
    public ThemeBrightnessPreference selectedBrightnessPreference() {
        EdtDispatcher.requireEventDispatchThread();
        for (ThemeBrightnessPreference preference : ThemeBrightnessPreference.values()) {
            if (themeButtons.get(preference).isSelected()) {
                return preference;
            }
        }
        throw new IllegalStateException("No brightness preference is selected");
    }

    /// Returns the radius currently represented by the slider.
    ///
    /// @return current logical-pixel radius
    public int displayedCornerRadius() {
        EdtDispatcher.requireEventDispatchThread();
        return cornerRadiusSlider.getValue();
    }

    /// Returns whether the animation control is selected.
    ///
    /// @return current animation preference
    public boolean areAnimationsEnabled() {
        EdtDispatcher.requireEventDispatchThread();
        return animationsEnabled.isSelected();
    }

    /// Releases the model subscription from any caller thread.
    @Override
    public void close() {
        SwingUiDispatcher.INSTANCE.dispatchOrRun(() -> {
            if (!closed) {
                closed = true;
                modelSubscription.unsubscribe();
                if (themePackManagementPanel != null) {
                    themePackManagementPanel.close();
                }
            }
        });
    }

    /// Builds the stable unframed settings layout.
    private void configureComponents() {
        JLabel heading = new JLabel(strings.pageTitle());
        heading.setName("appearancePageTitle");
        heading.setFont(heading.getFont().deriveFont(Font.BOLD, 24.0F));
        add(heading);

        add(createThemeRow());
        add(new JSeparator(), "growx");
        add(createRadiusRow());
        add(new JSeparator(), "growx");
        add(createAnimationRow());
        if (themePackManagementPanel != null) {
            add(new JSeparator(), "growx");
            add(themePackManagementPanel, "grow, hmin 320");
        }
    }

    /// Creates the theme field with one keyboard-accessible segmented control.
    ///
    /// @return configured theme field row
    private JPanel createThemeRow() {
        JPanel row = fieldRow();
        JLabel label = new JLabel(strings.themeModeLabel());
        JPanel segments = new JPanel(new MigLayout("insets 0, gap 0", "[][][][]", "[]"));
        segments.setOpaque(false);
        ButtonGroup group = new ButtonGroup();

        addThemeButton(
                segments,
                group,
                ThemeBrightnessPreference.THEME,
                strings.followThemeLabel(),
                "first");
        addThemeButton(
                segments,
                group,
                ThemeBrightnessPreference.SYSTEM,
                strings.systemThemeLabel(),
                "middle");
        addThemeButton(
                segments,
                group,
                ThemeBrightnessPreference.LIGHT,
                strings.lightThemeLabel(),
                "middle");
        addThemeButton(
                segments,
                group,
                ThemeBrightnessPreference.DARK,
                strings.darkThemeLabel(),
                "last");

        row.add(label, "growx");
        row.add(segments, "alignx right");
        return row;
    }

    /// Adds one FlatLaf segmented theme button.
    ///
    /// @param host segmented-control host
    /// @param group exclusive selection group
    /// @param preference represented brightness preference
    /// @param text localized button label
    /// @param position FlatLaf segment position
    private void addThemeButton(
            JPanel host,
            ButtonGroup group,
            ThemeBrightnessPreference preference,
            String text,
            String position) {
        JToggleButton button = new JToggleButton(text);
        button.setName("appearanceTheme" + preference.name());
        button.putClientProperty("JButton.buttonType", "segmented");
        button.putClientProperty("JButton.segmentPosition", position);
        button.addActionListener(event -> {
            if (!applyingSnapshot && button.isSelected()) {
                model.setThemeBrightnessPreference(preference);
            }
        });
        themeButtons.put(preference, button);
        group.add(button);
        host.add(button);
    }

    /// Creates the model-bounded corner-radius slider row.
    ///
    /// @return configured radius field row
    private JPanel createRadiusRow() {
        JPanel row = fieldRow();
        JLabel label = new JLabel(strings.cornerRadiusLabel());
        cornerRadiusSlider.setPaintTicks(false);
        cornerRadiusSlider.setPaintLabels(false);
        cornerRadiusSlider.setName("appearanceCornerRadius");
        cornerRadiusSlider.addChangeListener(event -> {
            int radius = alignedRadius(cornerRadiusSlider.getValue());
            if (radius != cornerRadiusSlider.getValue()) {
                applyingSnapshot = true;
                try {
                    cornerRadiusSlider.setValue(radius);
                } finally {
                    applyingSnapshot = false;
                }
            }
            cornerRadiusValue.setText(Integer.toString(radius));
            @Nullable AppearanceSettingsSnapshot snapshot = displayedSnapshot;
            if (!applyingSnapshot
                    && !cornerRadiusSlider.getValueIsAdjusting()
                    && snapshot != null
                    && snapshot.cornerRadius() != radius) {
                model.setCornerRadius(radius);
            }
        });
        cornerRadiusValue.setHorizontalAlignment(JLabel.TRAILING);

        JPanel control = new JPanel(new MigLayout("insets 0, fillx", "[grow,fill]12[36!]", "[]"));
        control.setOpaque(false);
        control.add(cornerRadiusSlider, "growx");
        control.add(cornerRadiusValue, "alignx right");

        row.add(label, "growx");
        row.add(control, "wmin 260, growx");
        return row;
    }

    /// Creates the binary animation preference row.
    ///
    /// @return configured animation field row
    private JPanel createAnimationRow() {
        JPanel row = fieldRow();
        animationsEnabled.setText(strings.animationsLabel());
        animationsEnabled.setName("appearanceAnimations");
        animationsEnabled.addActionListener(event -> {
            if (!applyingSnapshot) {
                model.setAnimationsEnabled(animationsEnabled.isSelected());
            }
        });
        row.add(animationsEnabled, "span 2, growx");
        return row;
    }

    /// Creates a transparent two-column field row.
    ///
    /// @return layout-only field row
    private static JPanel fieldRow() {
        JPanel row = new JPanel(new MigLayout("insets 4 0, fillx", "[grow,fill][grow,fill]", "[]"));
        row.setOpaque(false);
        return row;
    }

    /// Aligns an arbitrary slider position to the model-provided radius grid.
    ///
    /// @param rawRadius raw slider position
    /// @return nearest supported radius within the current model bounds
    private int alignedRadius(int rawRadius) {
        @Nullable AppearanceSettingsSnapshot snapshot = displayedSnapshot;
        if (snapshot == null) {
            return rawRadius;
        }
        int minimum = snapshot.minimumCornerRadius();
        int maximum = snapshot.maximumCornerRadius();
        int step = snapshot.cornerRadiusStep();
        long offset = (long) rawRadius - minimum;
        long alignedOffset = Math.round((double) offset / step) * step;
        long aligned = Math.max(minimum, Math.min(maximum, (long) minimum + alignedOffset));
        return (int) aligned;
    }

    /// Coalesces a model transition to the latest snapshot on the EDT.
    ///
    /// @param change transition that invalidated the displayed controls
    private void modelChanged(ValueChange<AppearanceSettingsSnapshot> change) {
        Objects.requireNonNull(change, "change");
        SwingUiDispatcher.INSTANCE.dispatchOrRun(() -> {
            if (!closed) {
                applySnapshot(model.snapshot());
            }
        });
    }

    /// Applies one immutable settings snapshot without echoing component events back to the model.
    ///
    /// @param snapshot latest model state
    private void applySnapshot(AppearanceSettingsSnapshot snapshot) {
        EdtDispatcher.requireEventDispatchThread();
        Objects.requireNonNull(snapshot, "snapshot");
        applyingSnapshot = true;
        try {
            displayedSnapshot = snapshot;
            themeButtons.get(snapshot.brightnessPreference()).setSelected(true);

            cornerRadiusSlider.setMinimum(snapshot.minimumCornerRadius());
            cornerRadiusSlider.setMaximum(snapshot.maximumCornerRadius());
            cornerRadiusSlider.setMinorTickSpacing(snapshot.cornerRadiusStep());
            cornerRadiusSlider.setSnapToTicks(true);
            cornerRadiusSlider.setValue(snapshot.cornerRadius());
            cornerRadiusValue.setText(Integer.toString(snapshot.cornerRadius()));

            animationsEnabled.setSelected(snapshot.animationsEnabled());
            setControlsEnabled(snapshot.writable());
        } finally {
            applyingSnapshot = false;
        }
    }

    /// Applies settings-store writability to every interactive control.
    ///
    /// @param enabled whether controls may issue persistence commands
    private void setControlsEnabled(boolean enabled) {
        for (JToggleButton button : themeButtons.values()) {
            button.setEnabled(enabled);
        }
        cornerRadiusSlider.setEnabled(enabled);
        animationsEnabled.setEnabled(enabled);
    }
}
