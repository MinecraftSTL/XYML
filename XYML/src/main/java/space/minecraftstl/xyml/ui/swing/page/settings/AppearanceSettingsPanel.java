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

import com.formdev.flatlaf.extras.FlatSVGIcon;
import net.miginfocom.swing.MigLayout;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.observable.Subscription;
import space.minecraftstl.xyml.observable.ValueChange;
import space.minecraftstl.xyml.setting.BackgroundType;
import space.minecraftstl.xyml.theme.BackgroundLoadPolicy;
import space.minecraftstl.xyml.theme.BuiltinBackground;
import space.minecraftstl.xyml.theme.NetworkBackgroundImageCachePolicy;
import space.minecraftstl.xyml.theme.ThemeBrightnessPreference;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;
import space.minecraftstl.xyml.ui.swing.SwingUiDispatcher;
import space.minecraftstl.xyml.ui.swing.dialog.EditablePathChooser;
import space.minecraftstl.xyml.ui.swing.page.settings.theme.ThemePackManagementPanel;

import javax.swing.ButtonGroup;
import javax.swing.DefaultComboBoxModel;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JColorChooser;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JSeparator;
import javax.swing.JSlider;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.UIManager;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.io.File;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;

/// Renders complete appearance preferences and persists every background edit as one immutable replacement.
///
/// The panel must be created on the EDT and closed when its host page is discarded. Model updates may arrive on
/// any thread and are coalesced to the latest snapshot before Swing components are changed. Background text fields
/// commit on Enter or focus loss, while sliders commit only after adjustment ends.
@NotNullByDefault
public final class AppearanceSettingsPanel extends JPanel implements AutoCloseable {
    /// Stable percentage resolution used by the background opacity slider.
    private static final int OPACITY_SCALE = 100;

    /// Settings model supplying snapshots and atomic persistence commands.
    private final AppearanceSettingsModel model;

    /// Localized page text.
    private final AppearanceSettingsStrings strings;

    /// Localized launcher-background text.
    private final AppearanceBackgroundStrings backgroundStrings;

    /// Optional local theme-pack surface owned with this appearance page.
    private final @Nullable ThemePackManagementPanel themePackManagementPanel;

    /// Theme buttons keyed by persisted mode.
    private final EnumMap<ThemeBrightnessPreference, JToggleButton> themeButtons =
            new EnumMap<>(ThemeBrightnessPreference.class);

    /// Slider configured from model-provided radius bounds and increment.
    private final JSlider cornerRadiusSlider = new JSlider();

    /// Current radius value displayed beside the slider.
    private final JLabel cornerRadiusValue = new JLabel();

    /// Transparent host positioned directly below the corner-radius slider for restart status and action.
    private final JPanel cornerRadiusRestartHost = new JPanel(new MigLayout(
            "insets 0, fillx",
            "[grow,fill]",
            "[]"));

    /// Binary persisted animation preference.
    private final JCheckBox animationsEnabled = new JCheckBox();

    /// Whether launcher background-source values override the selected theme.
    private final JCheckBox backgroundSourceOverridden = new JCheckBox();

    /// Primary launcher background source selector.
    private final JComboBox<BackgroundType> backgroundTypeBox = new JComboBox<>();

    /// Bundled wallpaper identifier used by primary and fallback built-in sources.
    private final JComboBox<String> builtinBackgroundBox = new JComboBox<>();

    /// Directly editable local background image file-or-directory path.
    private final JTextField localImagePathField = new JTextField();

    /// Opens the editable file browser for a local background path.
    private final JButton browseLocalImageButton = new JButton();

    /// Directly editable network image URL.
    private final JTextField networkImageUrlField = new JTextField();

    /// Directly editable primary solid-color or JavaFX-compatible gradient expression.
    private final JTextField customPaintField = new JTextField();

    /// Color swatch opening the primary paint chooser.
    private final JButton customPaintButton = new JButton();

    /// Whether launcher background opacity overrides the selected theme.
    private final JCheckBox backgroundOpacityOverridden = new JCheckBox();

    /// Background opacity represented as an integer percentage.
    private final JSlider backgroundOpacitySlider = new JSlider(0, OPACITY_SCALE);

    /// Current background opacity percentage.
    private final JLabel backgroundOpacityValue = new JLabel();

    /// Network image cache policy selector.
    private final JComboBox<NetworkBackgroundImageCachePolicy> networkCachePolicyBox = new JComboBox<>();

    /// Non-network fallback source selector.
    private final JComboBox<BackgroundType> fallbackTypeBox = new JComboBox<>();

    /// Directly editable fallback solid-color or JavaFX-compatible gradient expression.
    private final JTextField fallbackPaintField = new JTextField();

    /// Color swatch opening the fallback paint chooser.
    private final JButton fallbackPaintButton = new JButton();

    /// Selected image-loading behavior.
    private final JComboBox<BackgroundLoadPolicy> backgroundLoadPolicyBox = new JComboBox<>();

    /// Whether launcher window transparency overrides the selected theme.
    private final JCheckBox windowTransparencyOverridden = new JCheckBox();

    /// Whether unpainted native-window pixels reveal the desktop.
    private final JCheckBox windowTransparent = new JCheckBox();

    /// Subscription owned by this panel.
    private final Subscription modelSubscription;

    /// Snapshot currently represented by controls, or null before initial application.
    private @Nullable AppearanceSettingsSnapshot displayedSnapshot;

    /// Prevents programmatic component updates from writing back to the model.
    private boolean applyingSnapshot;

    /// Whether a restart action currently blocks appearance edits.
    private boolean restartInProgress;

    /// Appearance-specific restart status and action, attached by the owning settings center.
    private @Nullable SettingsRestartPanel cornerRadiusRestartPanel;

    /// Whether model resources have been released.
    private boolean closed;

    /// Creates an appearance settings panel on the EDT.
    ///
    /// @param model toolkit-neutral settings model
    /// @param strings localized page text
    public AppearanceSettingsPanel(AppearanceSettingsModel model, AppearanceSettingsStrings strings) {
        this(model, strings, null);
    }

    /// Creates an appearance settings panel with an optional local theme-pack manager.
    ///
    /// @param model toolkit-neutral settings model
    /// @param strings localized page text
    /// @param themePackManagementPanel local theme-pack surface to embed and own, or `null`
    public AppearanceSettingsPanel(
            AppearanceSettingsModel model,
            AppearanceSettingsStrings strings,
            @Nullable ThemePackManagementPanel themePackManagementPanel) {
        super(new MigLayout(
                "insets 20 24 24 24, fillx, wrap 1",
                "[grow,fill]",
                "[]"));
        EdtDispatcher.requireEventDispatchThread();
        this.model = Objects.requireNonNull(model, "model");
        this.strings = Objects.requireNonNull(strings, "strings");
        backgroundStrings = strings.background();
        this.themePackManagementPanel = themePackManagementPanel;

        applyingSnapshot = true;
        try {
            configureComponents();
        } finally {
            applyingSnapshot = false;
        }
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

    /// Returns the complete background values currently represented by controls.
    ///
    /// @return immutable background settings assembled from every control
    public BackgroundAppearanceSettings displayedBackground() {
        EdtDispatcher.requireEventDispatchThread();
        return backgroundFromControls();
    }

    /// Attaches the restart status row beneath the corner-radius slider.
    ///
    /// The appearance page owns the attached row after this call. Its callback first disables appearance controls,
    /// then notifies the settings center so the remaining settings pages use the same restart lock.
    ///
    /// @param strings localized restart text for appearance changes
    /// @param restartCommand launcher restart lifecycle command
    /// @param restartActivity callback receiving restart-in-progress transitions
    void attachCornerRadiusRestartPanel(
            SettingsRestartStrings strings,
            SettingsRestartCommand restartCommand,
            Consumer<Boolean> restartActivity) {
        EdtDispatcher.requireEventDispatchThread();
        if (closed) {
            throw new IllegalStateException("Cannot attach restart controls after close");
        }
        if (cornerRadiusRestartPanel != null) {
            throw new IllegalStateException("Corner-radius restart controls are already attached");
        }
        Consumer<Boolean> validatedActivity = Objects.requireNonNull(restartActivity, "restartActivity");
        SettingsRestartPanel panel = new SettingsRestartPanel(
                Objects.requireNonNull(strings, "strings"),
                Objects.requireNonNull(restartCommand, "restartCommand"),
                active -> {
                    setRestartInProgress(active);
                    validatedActivity.accept(active);
                });
        panel.setName("appearanceCornerRadiusRestart");
        cornerRadiusRestartPanel = panel;
        cornerRadiusRestartHost.add(panel, "growx");
        @Nullable AppearanceSettingsSnapshot snapshot = displayedSnapshot;
        if (snapshot != null) {
            panel.updateCornerRadius(snapshot.cornerRadius());
        }
        panel.setAvailable(snapshot != null && snapshot.writable() && !restartInProgress);
        cornerRadiusRestartHost.revalidate();
        cornerRadiusRestartHost.repaint();
    }

    /// Updates appearance-control availability while any settings restart is being prepared.
    ///
    /// @param inProgress whether the restart command is active
    void setRestartInProgress(boolean inProgress) {
        EdtDispatcher.requireEventDispatchThread();
        restartInProgress = inProgress;
        @Nullable AppearanceSettingsSnapshot snapshot = displayedSnapshot;
        setControlsEnabled(snapshot != null && snapshot.writable());
    }

    /// Releases the model subscription from any caller thread.
    @Override
    public void close() {
        SwingUiDispatcher.INSTANCE.dispatchOrRun(() -> {
            if (!closed) {
                closed = true;
                modelSubscription.unsubscribe();
                if (cornerRadiusRestartPanel != null) {
                    cornerRadiusRestartPanel.close();
                }
                if (themePackManagementPanel != null) {
                    themePackManagementPanel.close();
                }
            }
        });
    }

    /// Builds the stable unframed settings layout.
    private void configureComponents() {
        setOpaque(false);
        configureBackgroundControls();

        JLabel heading = new JLabel(strings.pageTitle());
        heading.setName("appearancePageTitle");
        heading.setFont(heading.getFont().deriveFont(Font.BOLD, 24.0F));
        add(heading, "gapbottom 18");

        add(createThemeRow(), "gapbottom 12");
        add(new JSeparator(), "growx, gapbottom 12");
        add(createRadiusRow(), "gapbottom 12");
        add(new JSeparator(), "growx, gapbottom 12");
        add(createAnimationRow(), "gapbottom 12");
        add(new JSeparator(), "growx, gapbottom 14");

        JLabel backgroundHeading = new JLabel(backgroundStrings.sectionTitle());
        backgroundHeading.setName("appearanceBackgroundTitle");
        backgroundHeading.setFont(backgroundHeading.getFont().deriveFont(Font.BOLD, 18.0F));
        add(backgroundHeading, "gapbottom 8");
        add(createFullWidthToggleRow(backgroundSourceOverridden), "gapbottom 4");
        add(createFieldRow(backgroundStrings.sourceTypeLabel(), backgroundTypeBox), "gapbottom 4");
        add(createFieldRow(backgroundStrings.builtinSelectionLabel(), builtinBackgroundBox), "gapbottom 4");
        add(createLocalPathRow(), "gapbottom 4");
        add(createFieldRow(backgroundStrings.networkUrlLabel(), networkImageUrlField), "gapbottom 4");
        add(createColorFieldRow(backgroundStrings.paintValueLabel(), customPaintField, customPaintButton),
                "gapbottom 4");
        add(createFullWidthToggleRow(backgroundOpacityOverridden), "gapbottom 4");
        add(createOpacityRow(), "gapbottom 4");
        add(createFieldRow(backgroundStrings.networkCacheLabel(), networkCachePolicyBox), "gapbottom 4");
        add(createFieldRow(backgroundStrings.fallbackTypeLabel(), fallbackTypeBox), "gapbottom 4");
        add(createColorFieldRow(
                backgroundStrings.fallbackPaintLabel(),
                fallbackPaintField,
                fallbackPaintButton), "gapbottom 4");
        add(createFieldRow(backgroundStrings.loadPolicyLabel(), backgroundLoadPolicyBox), "gapbottom 4");
        add(createFullWidthToggleRow(windowTransparencyOverridden), "gapbottom 4");
        add(createFullWidthToggleRow(windowTransparent), "gapbottom 12");

        if (themePackManagementPanel != null) {
            add(new JSeparator(), "growx, gapbottom 12");
            add(themePackManagementPanel, "grow, hmin 320");
        }
    }

    /// Configures background control models, localized renderers, names, and atomic listeners.
    private void configureBackgroundControls() {
        populateBackgroundTypeModel();
        for (String id : BuiltinBackground.BUILTIN_BACKGROUND_IDS) {
            builtinBackgroundBox.addItem(id);
        }
        networkCachePolicyBox.setModel(enumModel(NetworkBackgroundImageCachePolicy.class));
        fallbackTypeBox.addItem(BackgroundType.BUILTIN);
        fallbackTypeBox.addItem(BackgroundType.PAINT);
        fallbackTypeBox.addItem(BackgroundType.THEME_COLOR);
        backgroundLoadPolicyBox.setModel(enumModel(BackgroundLoadPolicy.class));

        backgroundTypeBox.setRenderer(new LocalizedValueRenderer<>(
                BackgroundType.class,
                backgroundStrings::sourceLabel));
        networkCachePolicyBox.setRenderer(new LocalizedValueRenderer<>(
                NetworkBackgroundImageCachePolicy.class,
                backgroundStrings::networkCachePolicyLabel));
        fallbackTypeBox.setRenderer(new LocalizedValueRenderer<>(
                BackgroundType.class,
                backgroundStrings::fallbackLabel));
        backgroundLoadPolicyBox.setRenderer(new LocalizedValueRenderer<>(
                BackgroundLoadPolicy.class,
                backgroundStrings::loadPolicyLabel));

        backgroundSourceOverridden.setName("appearanceBackgroundSourceOverridden");
        backgroundSourceOverridden.setText(backgroundStrings.sourceOverrideLabel());
        backgroundTypeBox.setName("appearanceBackgroundType");
        builtinBackgroundBox.setName("appearanceBuiltinBackground");
        localImagePathField.setName("appearanceLocalBackgroundPath");
        localImagePathField.putClientProperty("JTextField.placeholderText", backgroundStrings.localPathLabel());
        networkImageUrlField.setName("appearanceNetworkBackgroundUrl");
        networkImageUrlField.putClientProperty("JTextField.placeholderText", "https://");
        customPaintField.setName("appearanceBackgroundPaint");
        customPaintField.putClientProperty("JTextField.placeholderText", "#RRGGBB");
        backgroundOpacityOverridden.setName("appearanceBackgroundOpacityOverridden");
        backgroundOpacityOverridden.setText(backgroundStrings.opacityOverrideLabel());
        backgroundOpacitySlider.setName("appearanceBackgroundOpacity");
        backgroundOpacityValue.setName("appearanceBackgroundOpacityValue");
        backgroundOpacityValue.setHorizontalAlignment(JLabel.TRAILING);
        networkCachePolicyBox.setName("appearanceNetworkBackgroundCache");
        fallbackTypeBox.setName("appearanceBackgroundFallbackType");
        fallbackPaintField.setName("appearanceBackgroundFallbackPaint");
        fallbackPaintField.putClientProperty("JTextField.placeholderText", "#RRGGBB");
        backgroundLoadPolicyBox.setName("appearanceBackgroundLoadPolicy");
        windowTransparencyOverridden.setName("appearanceWindowTransparencyOverridden");
        windowTransparencyOverridden.setText(backgroundStrings.windowTransparencyOverrideLabel());
        windowTransparent.setName("appearanceWindowTransparent");
        windowTransparent.setText(backgroundStrings.windowTransparentLabel());

        configureBrowseButton();
        configureColorButton(customPaintButton, "appearanceBackgroundPaintChooser");
        configureColorButton(fallbackPaintButton, "appearanceBackgroundFallbackPaintChooser");
        configureBackgroundListeners();
    }

    /// Populates every supported primary background source in stable enum order.
    private void populateBackgroundTypeModel() {
        for (BackgroundType type : BackgroundType.values()) {
            backgroundTypeBox.addItem(type);
        }
    }

    /// Configures the familiar folder icon action for local path browsing.
    private void configureBrowseButton() {
        browseLocalImageButton.setName("appearanceBrowseLocalBackground");
        browseLocalImageButton.setText(null);
        browseLocalImageButton.setIcon(new FlatSVGIcon("assets/swing/icons/folder-open.svg", 18, 18));
        browseLocalImageButton.setToolTipText(backgroundStrings.browseLabel());
        browseLocalImageButton.getAccessibleContext().setAccessibleName(backgroundStrings.browseLabel());
        browseLocalImageButton.setPreferredSize(new Dimension(34, 30));
        browseLocalImageButton.addActionListener(event -> browseLocalBackground());
    }

    /// Configures one compact color-swatch action.
    ///
    /// @param button swatch button
    /// @param name stable component name
    private void configureColorButton(JButton button, String name) {
        JButton target = Objects.requireNonNull(button, "button");
        target.setName(Objects.requireNonNull(name, "name"));
        target.setText(null);
        target.setToolTipText(backgroundStrings.chooseColorLabel());
        target.getAccessibleContext().setAccessibleName(backgroundStrings.chooseColorLabel());
        target.setPreferredSize(new Dimension(34, 30));
        target.setOpaque(true);
    }

    /// Attaches listeners that publish one complete background replacement per accepted user action.
    private void configureBackgroundListeners() {
        backgroundSourceOverridden.addActionListener(event -> backgroundControlChanged());
        backgroundTypeBox.addActionListener(event -> backgroundControlChanged());
        builtinBackgroundBox.addActionListener(event -> commitBackground());
        networkCachePolicyBox.addActionListener(event -> backgroundControlChanged());
        fallbackTypeBox.addActionListener(event -> backgroundControlChanged());
        backgroundLoadPolicyBox.addActionListener(event -> commitBackground());
        backgroundOpacityOverridden.addActionListener(event -> backgroundControlChanged());
        windowTransparencyOverridden.addActionListener(event -> backgroundControlChanged());
        windowTransparent.addActionListener(event -> commitBackground());

        BackgroundTextCommitter textCommitter = new BackgroundTextCommitter(this::commitBackground);
        for (JTextField field : List.of(
                localImagePathField,
                networkImageUrlField,
                customPaintField,
                fallbackPaintField)) {
            field.addActionListener(event -> commitBackground());
            field.addFocusListener(textCommitter);
        }

        customPaintButton.addActionListener(event -> chooseColor(customPaintField));
        fallbackPaintButton.addActionListener(event -> chooseColor(fallbackPaintField));
        backgroundOpacitySlider.addChangeListener(event -> {
            backgroundOpacityValue.setText(backgroundOpacitySlider.getValue() + "%");
            if (!backgroundOpacitySlider.getValueIsAdjusting()) {
                commitBackground();
            }
        });
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

        addThemeButton(segments, group, ThemeBrightnessPreference.THEME, strings.followThemeLabel(), "first");
        addThemeButton(segments, group, ThemeBrightnessPreference.SYSTEM, strings.systemThemeLabel(), "middle");
        addThemeButton(segments, group, ThemeBrightnessPreference.LIGHT, strings.lightThemeLabel(), "middle");
        addThemeButton(segments, group, ThemeBrightnessPreference.DARK, strings.darkThemeLabel(), "last");

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
        JPanel row = new JPanel(new MigLayout(
                "insets 4 0, fillx",
                "[grow,fill][grow,fill]",
                "[][pref!]"));
        row.setOpaque(false);
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
        row.add(control, "wmin 260, growx, wrap");
        cornerRadiusRestartHost.setOpaque(false);
        row.add(cornerRadiusRestartHost, "cell 1 1, growx, gaptop 2");
        return row;
    }

    /// Creates the binary animation preference row.
    ///
    /// @return configured animation field row
    private JPanel createAnimationRow() {
        animationsEnabled.setText(strings.animationsLabel());
        animationsEnabled.setName("appearanceAnimations");
        animationsEnabled.addActionListener(event -> {
            if (!applyingSnapshot) {
                model.setAnimationsEnabled(animationsEnabled.isSelected());
            }
        });
        return createFullWidthToggleRow(animationsEnabled);
    }

    /// Creates one labeled two-column row for an arbitrary control.
    ///
    /// @param labelText localized field label
    /// @param control input component
    /// @return transparent field row
    private static JPanel createFieldRow(String labelText, Component control) {
        JPanel row = fieldRow();
        row.add(new JLabel(Objects.requireNonNull(labelText, "labelText")), "growx");
        row.add(Objects.requireNonNull(control, "control"), "wmin 260, growx");
        return row;
    }

    /// Creates the local path field with an adjacent folder icon action.
    ///
    /// @return local path field row
    private JPanel createLocalPathRow() {
        JPanel input = compoundInput(localImagePathField, browseLocalImageButton);
        return createFieldRow(backgroundStrings.localPathLabel(), input);
    }

    /// Creates one editable color expression with an adjacent color swatch.
    ///
    /// @param labelText localized field label
    /// @param field color expression field
    /// @param button color chooser swatch
    /// @return color field row
    private static JPanel createColorFieldRow(String labelText, JTextField field, JButton button) {
        return createFieldRow(labelText, compoundInput(field, button));
    }

    /// Creates one horizontal text-and-action input without adding a decorative card.
    ///
    /// @param field expanding text field
    /// @param button stable-width adjacent action
    /// @return transparent compound input
    private static JPanel compoundInput(JTextField field, JButton button) {
        JPanel input = new JPanel(new MigLayout("insets 0, fillx", "[grow,fill]6[34!]", "[]"));
        input.setOpaque(false);
        input.add(Objects.requireNonNull(field, "field"), "growx");
        input.add(Objects.requireNonNull(button, "button"), "w 34!, h 30!");
        return input;
    }

    /// Creates the background opacity slider and exact percentage label.
    ///
    /// @return opacity field row
    private JPanel createOpacityRow() {
        JPanel input = new JPanel(new MigLayout("insets 0, fillx", "[grow,fill]12[44!]", "[]"));
        input.setOpaque(false);
        input.add(backgroundOpacitySlider, "growx");
        input.add(backgroundOpacityValue, "alignx right");
        return createFieldRow(backgroundStrings.opacityLabel(), input);
    }

    /// Creates a full-width checkbox row.
    ///
    /// @param toggle configured checkbox
    /// @return transparent full-width row
    private static JPanel createFullWidthToggleRow(JCheckBox toggle) {
        JPanel row = fieldRow();
        row.add(Objects.requireNonNull(toggle, "toggle"), "span 2, growx");
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

    /// Opens a chooser supporting direct typed paths, image files, and image directories.
    private void browseLocalBackground() {
        EditablePathChooser chooser = createLocalBackgroundChooser(localImagePathField.getText());
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            @Nullable File selected = chooser.getSelectedFile();
            if (selected != null) {
                localImagePathField.setText(selected.getAbsolutePath());
                commitBackground();
            }
        }
    }

    /// Creates a local background chooser near the current directly entered path when possible.
    ///
    /// @param currentPath current local path text
    /// @return configured editable path chooser
    private static EditablePathChooser createLocalBackgroundChooser(String currentPath) {
        EditablePathChooser chooser = new EditablePathChooser();
        chooser.setFileSelectionMode(JFileChooser.FILES_AND_DIRECTORIES);
        String pathText = Objects.requireNonNull(currentPath, "currentPath").trim();
        if (pathText.isEmpty()) {
            return chooser;
        }
        try {
            File selected = Path.of(pathText).toAbsolutePath().normalize().toFile();
            chooser.setSelectedFile(selected);
            @Nullable File directory = selected.isDirectory() ? selected : selected.getParentFile();
            if (directory != null && directory.isDirectory()) {
                chooser.setCurrentDirectory(directory);
            }
        } catch (InvalidPathException | SecurityException ignored) {
            // The editable chooser remains usable from its platform-default directory.
        }
        return chooser;
    }

    /// Opens a native color chooser and commits a canonical hexadecimal expression.
    ///
    /// @param field target primary or fallback color field
    private void chooseColor(JTextField field) {
        JTextField target = Objects.requireNonNull(field, "field");
        Color initial = Objects.requireNonNullElse(parseDisplayColor(target.getText()), Color.WHITE);
        @Nullable Color selected = JColorChooser.showDialog(
                this,
                backgroundStrings.chooseColorLabel(),
                initial);
        if (selected != null) {
            target.setText(String.format(
                    Locale.ROOT,
                    "#%02X%02X%02X",
                    selected.getRed(),
                    selected.getGreen(),
                    selected.getBlue()));
            commitBackground();
        }
    }

    /// Handles a control that changes both persistence content and dependent enabled states.
    private void backgroundControlChanged() {
        if (!applyingSnapshot) {
            setBackgroundControlsEnabled(displayedSnapshot().writable());
            commitBackground();
        }
    }

    /// Persists all background controls through one model call when they differ from the displayed snapshot.
    private void commitBackground() {
        if (applyingSnapshot || closed) {
            return;
        }
        updatePaintSwatches();
        BackgroundAppearanceSettings replacement = backgroundFromControls();
        @Nullable AppearanceSettingsSnapshot snapshot = displayedSnapshot;
        if (snapshot == null || !replacement.equals(snapshot.background())) {
            model.setBackgroundAppearance(replacement);
        }
    }

    /// Constructs one validated complete background setting from current controls.
    ///
    /// @return immutable replacement preserving inactive source values
    private BackgroundAppearanceSettings backgroundFromControls() {
        @Nullable String customPaint = nullableText(customPaintField.getText());
        return new BackgroundAppearanceSettings(
                selectedItem(backgroundTypeBox, BackgroundType.class),
                selectedItem(builtinBackgroundBox, String.class),
                localImagePathField.getText(),
                networkImageUrlField.getText(),
                customPaint,
                backgroundOpacitySlider.getValue() / (double) OPACITY_SCALE,
                selectedItem(networkCachePolicyBox, NetworkBackgroundImageCachePolicy.class),
                selectedItem(fallbackTypeBox, BackgroundType.class),
                fallbackPaintField.getText(),
                selectedItem(backgroundLoadPolicyBox, BackgroundLoadPolicy.class),
                windowTransparent.isSelected(),
                backgroundSourceOverridden.isSelected(),
                backgroundOpacityOverridden.isSelected(),
                windowTransparencyOverridden.isSelected());
    }

    /// Returns trimmed text or null when a nullable color field is blank.
    ///
    /// @param text field text
    /// @return trimmed non-empty text, or null
    private static @Nullable String nullableText(String text) {
        String value = Objects.requireNonNull(text, "text").trim();
        return value.isEmpty() ? null : value;
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
    /// @param change transition that invalidated displayed controls
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
            if (cornerRadiusRestartPanel != null) {
                cornerRadiusRestartPanel.updateCornerRadius(snapshot.cornerRadius());
            }
            animationsEnabled.setSelected(snapshot.animationsEnabled());

            BackgroundAppearanceSettings background = snapshot.background();
            backgroundSourceOverridden.setSelected(background.sourceOverridden());
            backgroundTypeBox.setSelectedItem(background.type());
            builtinBackgroundBox.setSelectedItem(background.builtinBackgroundId());
            localImagePathField.setText(background.customImagePath());
            networkImageUrlField.setText(background.networkImageUrl());
            customPaintField.setText(Objects.requireNonNullElse(background.customPaint(), ""));
            backgroundOpacityOverridden.setSelected(background.opacityOverridden());
            backgroundOpacitySlider.setValue((int) Math.round(background.opacity() * OPACITY_SCALE));
            backgroundOpacityValue.setText(backgroundOpacitySlider.getValue() + "%");
            networkCachePolicyBox.setSelectedItem(background.networkCachePolicy());
            fallbackTypeBox.setSelectedItem(background.fallbackType());
            fallbackPaintField.setText(background.fallbackPaint());
            backgroundLoadPolicyBox.setSelectedItem(background.loadPolicy());
            windowTransparencyOverridden.setSelected(background.windowTransparencyOverridden());
            windowTransparent.setSelected(background.windowTransparent());
            updatePaintSwatches();
            setControlsEnabled(snapshot.writable());
        } finally {
            applyingSnapshot = false;
        }
    }

    /// Applies settings-store writability to general and dependent background controls.
    ///
    /// @param enabled whether controls may issue persistence commands
    private void setControlsEnabled(boolean enabled) {
        boolean interactive = enabled && !restartInProgress;
        if (cornerRadiusRestartPanel != null) {
            cornerRadiusRestartPanel.setAvailable(interactive);
        }
        for (JToggleButton button : themeButtons.values()) {
            button.setEnabled(interactive);
        }
        cornerRadiusSlider.setEnabled(interactive);
        animationsEnabled.setEnabled(interactive);
        setBackgroundControlsEnabled(interactive);
    }

    /// Applies source, fallback, and theme-override dependencies to background controls.
    ///
    /// @param writable whether the store accepts changes
    private void setBackgroundControlsEnabled(boolean writable) {
        backgroundSourceOverridden.setEnabled(writable);
        backgroundOpacityOverridden.setEnabled(writable);
        windowTransparencyOverridden.setEnabled(writable);

        boolean sourceActive = writable && backgroundSourceOverridden.isSelected();
        BackgroundType type = selectedItem(backgroundTypeBox, BackgroundType.class);
        BackgroundType fallbackType = selectedItem(fallbackTypeBox, BackgroundType.class);
        backgroundTypeBox.setEnabled(sourceActive);
        boolean builtinRequired = fallbackType == BackgroundType.BUILTIN
                || sourceActive && type == BackgroundType.BUILTIN;
        builtinBackgroundBox.setEnabled(writable && builtinRequired);
        boolean localActive = sourceActive && type == BackgroundType.CUSTOM;
        localImagePathField.setEnabled(localActive);
        browseLocalImageButton.setEnabled(localActive);
        boolean networkActive = sourceActive && type == BackgroundType.NETWORK;
        networkImageUrlField.setEnabled(networkActive);
        networkCachePolicyBox.setEnabled(networkActive);
        boolean paintActive = sourceActive && type == BackgroundType.PAINT;
        customPaintField.setEnabled(paintActive);
        customPaintButton.setEnabled(paintActive);

        backgroundOpacitySlider.setEnabled(writable && backgroundOpacityOverridden.isSelected());
        fallbackTypeBox.setEnabled(writable);
        boolean fallbackPaintActive = writable && fallbackType == BackgroundType.PAINT;
        fallbackPaintField.setEnabled(fallbackPaintActive);
        fallbackPaintButton.setEnabled(fallbackPaintActive);
        backgroundLoadPolicyBox.setEnabled(writable);
        windowTransparent.setEnabled(writable && windowTransparencyOverridden.isSelected());
    }

    /// Updates both color chooser buttons from their corresponding text values.
    private void updatePaintSwatches() {
        updatePaintSwatch(customPaintButton, customPaintField.getText());
        updatePaintSwatch(fallbackPaintButton, fallbackPaintField.getText());
    }

    /// Updates one swatch without rejecting expressions supported only by the renderer.
    ///
    /// @param button target swatch
    /// @param expression current color expression
    private static void updatePaintSwatch(JButton button, String expression) {
        @Nullable Color parsed = parseDisplayColor(expression);
        @Nullable Color fallback = UIManager.getColor("Button.background");
        button.setBackground(parsed != null ? parsed : Objects.requireNonNullElse(fallback, Color.LIGHT_GRAY));
    }

    /// Parses common paint values for the color swatch without constraining persisted renderer syntax.
    ///
    /// @param expression color expression
    /// @return display color, or null when unsupported by the compact preview
    private static @Nullable Color parseDisplayColor(String expression) {
        String value = Objects.requireNonNull(expression, "expression").trim().toLowerCase(Locale.ROOT);
        try {
            if (value.startsWith("#")) {
                String digits = value.substring(1);
                return switch (digits.length()) {
                    case 3 -> new Color(
                            Integer.parseInt(digits.substring(0, 1).repeat(2), 16),
                            Integer.parseInt(digits.substring(1, 2).repeat(2), 16),
                            Integer.parseInt(digits.substring(2, 3).repeat(2), 16));
                    case 6 -> new Color(Integer.parseInt(digits, 16));
                    case 8 -> new Color(
                            Integer.parseInt(digits.substring(0, 2), 16),
                            Integer.parseInt(digits.substring(2, 4), 16),
                            Integer.parseInt(digits.substring(4, 6), 16),
                            Integer.parseInt(digits.substring(6, 8), 16));
                    default -> null;
                };
            }
            return switch (value) {
                case "black" -> Color.BLACK;
                case "white" -> Color.WHITE;
                case "red" -> Color.RED;
                case "green" -> Color.GREEN;
                case "blue" -> Color.BLUE;
                case "gray", "grey" -> Color.GRAY;
                case "transparent" -> new Color(0, 0, 0, 0);
                default -> null;
            };
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    /// Returns one typed combo-box selection or reports an incomplete component configuration.
    ///
    /// @param comboBox source combo box
    /// @param expectedType expected selected value type
    /// @param <T> selected value type
    /// @return non-null typed selected value
    private static <T> T selectedItem(JComboBox<T> comboBox, Class<T> expectedType) {
        @Nullable Object selected = Objects.requireNonNull(comboBox, "comboBox").getSelectedItem();
        Class<T> type = Objects.requireNonNull(expectedType, "expectedType");
        if (!type.isInstance(selected)) {
            throw new IllegalStateException("Missing combo-box selection for " + type.getSimpleName());
        }
        return type.cast(selected);
    }

    /// Creates a mutable Swing combo-box model containing every enum constant in declaration order.
    ///
    /// @param enumType enum value type
    /// @param <E> enum type
    /// @return populated combo-box model
    private static <E extends Enum<E>> DefaultComboBoxModel<E> enumModel(Class<E> enumType) {
        Class<E> type = Objects.requireNonNull(enumType, "enumType");
        E @Nullable [] nullableConstants = type.getEnumConstants();
        E @Unmodifiable [] constants = Objects.requireNonNull(nullableConstants, "enum constants");
        DefaultComboBoxModel<E> model = new DefaultComboBoxModel<>();
        for (E value : constants) {
            model.addElement(value);
        }
        return model;
    }

    /// Commits background text after keyboard traversal leaves a field.
    @NotNullByDefault
    private static final class BackgroundTextCommitter extends FocusAdapter {
        /// Atomic background commit action.
        private final Runnable commit;

        /// Creates one focus listener for all background text fields.
        ///
        /// @param commit atomic background commit action
        private BackgroundTextCommitter(Runnable commit) {
            this.commit = Objects.requireNonNull(commit, "commit");
        }

        /// Commits the complete background after focus leaves a text input.
        ///
        /// @param event focus transition
        @Override
        public void focusLost(FocusEvent event) {
            Objects.requireNonNull(event, "event");
            commit.run();
        }
    }

    /// Renders typed combo-box values with caller-provided localized text.
    ///
    /// @param <T> combo-box value type
    @NotNullByDefault
    private static final class LocalizedValueRenderer<T> extends DefaultListCellRenderer {
        /// Runtime value type used to reject unexpected renderer values.
        private final Class<T> valueType;

        /// Localized label resolver.
        private final Function<T, String> labeler;

        /// Creates one reusable localized renderer.
        ///
        /// @param valueType runtime value type
        /// @param labeler localized label resolver
        private LocalizedValueRenderer(Class<T> valueType, Function<T, String> labeler) {
            this.valueType = Objects.requireNonNull(valueType, "valueType");
            this.labeler = Objects.requireNonNull(labeler, "labeler");
        }

        /// Resolves one combo-box value to localized visible text.
        ///
        /// @param list owning list
        /// @param value selected value, or null while the model is empty
        /// @param index row index
        /// @param isSelected whether the row is selected
        /// @param cellHasFocus whether the row owns focus
        /// @return configured label component
        @Override
        public Component getListCellRendererComponent(
                JList<?> list,
                @Nullable Object value,
                int index,
                boolean isSelected,
                boolean cellHasFocus) {
            String text = valueType.isInstance(value) ? labeler.apply(valueType.cast(value)) : "";
            return super.getListCellRendererComponent(list, text, index, isSelected, cellHasFocus);
        }
    }
}
