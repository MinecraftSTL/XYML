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

import net.miginfocom.swing.MigLayout;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.setting.GameWindowType;

import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.DisplayMode;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static space.minecraftstl.xyml.util.i18n.I18n.i18n;

/// Provides an editable common-resolution selector synchronized with the independent width and height settings.
@NotNullByDefault
final class InstanceWindowSizeControls {
    /// Parses positive decimal width and height values separated by a multiplication marker.
    private static final Pattern RESOLUTION_PATTERN = Pattern.compile(
            "\\s*(\\d+(?:\\.\\d+)?)\\s*[xX\\u00D7]\\s*(\\d+(?:\\.\\d+)?)\\s*");

    /// Width inheritance switch.
    private final JCheckBox widthOverride;

    /// Height inheritance switch.
    private final JCheckBox heightOverride;

    /// Width value editor.
    private final JTextField widthEditor;

    /// Height value editor.
    private final JTextField heightEditor;

    /// Effective window-mode editor controlling preset availability.
    private final JComboBox<GameWindowType> windowTypeEditor;

    /// Editable preset selector containing display-compatible common resolutions.
    private final JComboBox<String> resolutionSelector = new JComboBox<>();

    /// Transparent row inserted into the game-window section.
    private final JPanel component = new JPanel(new MigLayout(
            "insets 0, fillx",
            "[26!,center]8[320:pref,fill]16[grow,fill]",
            "[]"));

    /// Prevents reciprocal document and selector listeners from looping.
    private boolean synchronizing;

    /// Creates one common-resolution selector over the existing inherited editors.
    ///
    /// @param widthOverride width inheritance switch
    /// @param heightOverride height inheritance switch
    /// @param widthEditor width text field
    /// @param heightEditor height text field
    /// @param windowTypeEditor effective window-mode selector
    InstanceWindowSizeControls(
            JCheckBox widthOverride,
            JCheckBox heightOverride,
            JTextField widthEditor,
            JTextField heightEditor,
            JComboBox<GameWindowType> windowTypeEditor) {
        this.widthOverride = Objects.requireNonNull(widthOverride, "widthOverride");
        this.heightOverride = Objects.requireNonNull(heightOverride, "heightOverride");
        this.widthEditor = Objects.requireNonNull(widthEditor, "widthEditor");
        this.heightEditor = Objects.requireNonNull(heightEditor, "heightEditor");
        this.windowTypeEditor = Objects.requireNonNull(windowTypeEditor, "windowTypeEditor");
        configureComponents();
        configureInteractions();
        synchronizeSelectorFromDimensions();
        updateAvailability();
    }

    /// Returns the transparent common-resolution row.
    ///
    /// @return row component
    JComponent component() {
        return component;
    }

    /// Returns common resolutions bounded by the largest attached display.
    ///
    /// The three legacy baseline sizes remain available on small or headless displays. Larger presets are included
    /// only when at least one current display can represent them.
    ///
    /// @param maximumWidth largest physical display width
    /// @param maximumHeight largest physical display height
    /// @return immutable ordered resolution list
    static @Unmodifiable List<String> supportedResolutions(int maximumWidth, int maximumHeight) {
        List<String> resolutions = new ArrayList<>(List.of("854x480", "1280x720", "1600x900"));
        addIfSupported(resolutions, maximumWidth, maximumHeight, 1920, 1080);
        addIfSupported(resolutions, maximumWidth, maximumHeight, 2560, 1440);
        addIfSupported(resolutions, maximumWidth, maximumHeight, 3840, 2160);
        return List.copyOf(resolutions);
    }

    /// Configures component identity, transparency, and the editable preset model.
    private void configureComponents() {
        component.setName("instanceGameSettingsWindowSizePresetRow");
        component.setOpaque(false);
        resolutionSelector.setName("instanceGameSettingsWindowSizePreset");
        resolutionSelector.setEditable(true);
        DisplaySize maximumDisplaySize = maximumDisplaySize();
        for (String resolution : supportedResolutions(maximumDisplaySize.width(), maximumDisplaySize.height())) {
            resolutionSelector.addItem(resolution);
        }
        component.add(new JLabel());
        component.add(new JLabel(i18n("settings.game.window_size")));
        component.add(resolutionSelector, "growx");
    }

    /// Connects bidirectional dimension synchronization and inherited editor availability.
    private void configureInteractions() {
        DocumentListener dimensionListener = new DocumentListener() {
            /// Synchronizes after text insertion.
            @Override
            public void insertUpdate(DocumentEvent event) {
                synchronizeSelectorFromDimensions();
            }

            /// Synchronizes after text removal.
            @Override
            public void removeUpdate(DocumentEvent event) {
                synchronizeSelectorFromDimensions();
            }

            /// Synchronizes after styled-document attribute changes.
            @Override
            public void changedUpdate(DocumentEvent event) {
                synchronizeSelectorFromDimensions();
            }
        };
        widthEditor.getDocument().addDocumentListener(dimensionListener);
        heightEditor.getDocument().addDocumentListener(dimensionListener);
        resolutionSelector.addActionListener(event -> applySelectedResolution());
        windowTypeEditor.addActionListener(event -> updateAvailability());
        widthOverride.addPropertyChangeListener("enabled", event -> updateAvailability());
        heightOverride.addPropertyChangeListener("enabled", event -> updateAvailability());
    }

    /// Mirrors exact current dimensions into the editable selector without changing either editor.
    private void synchronizeSelectorFromDimensions() {
        if (synchronizing) {
            return;
        }
        synchronizing = true;
        try {
            String width = widthEditor.getText().trim();
            String height = heightEditor.getText().trim();
            resolutionSelector.getEditor().setItem(
                    width.isEmpty() || height.isEmpty() ? "" : width + "x" + height);
        } finally {
            synchronizing = false;
        }
    }

    /// Parses and applies one selected or typed resolution as a paired local override.
    private void applySelectedResolution() {
        if (synchronizing || !resolutionSelector.isEnabled()) {
            return;
        }
        @Nullable Object selected = resolutionSelector.getEditor().getItem();
        if (selected == null) {
            return;
        }
        Matcher matcher = RESOLUTION_PATTERN.matcher(selected.toString());
        if (!matcher.matches()) {
            return;
        }

        activateOverride(widthOverride);
        activateOverride(heightOverride);
        synchronizing = true;
        try {
            widthEditor.setText(matcher.group(1));
            heightEditor.setText(matcher.group(2));
        } finally {
            synchronizing = false;
        }
        synchronizeSelectorFromDimensions();
    }

    /// Activates an available inherited setting through its ordinary user interaction path.
    ///
    /// @param override inheritance switch
    private static void activateOverride(JCheckBox override) {
        if (!override.isSelected() && override.isEnabled()) {
            override.doClick();
        }
    }

    /// Enables presets only for an editable windowed-mode settings surface.
    private void updateAvailability() {
        resolutionSelector.setEnabled(
                windowTypeEditor.getSelectedItem() == GameWindowType.WINDOWED
                        && widthOverride.isEnabled()
                        && heightOverride.isEnabled());
    }

    /// Appends one preset when the current display bounds can represent it.
    ///
    /// @param resolutions mutable ordered destination
    /// @param maximumWidth largest display width
    /// @param maximumHeight largest display height
    /// @param width candidate width
    /// @param height candidate height
    private static void addIfSupported(
            List<String> resolutions,
            int maximumWidth,
            int maximumHeight,
            int width,
            int height) {
        if (maximumWidth >= width && maximumHeight >= height) {
            resolutions.add(width + "x" + height);
        }
    }

    /// Detects the largest physical display dimensions without failing in headless tests.
    ///
    /// @return largest attached display size
    private static DisplaySize maximumDisplaySize() {
        if (GraphicsEnvironment.isHeadless()) {
            return new DisplaySize(0, 0);
        }
        int maximumWidth = 0;
        int maximumHeight = 0;
        for (GraphicsDevice device : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
            DisplayMode mode = device.getDisplayMode();
            maximumWidth = Math.max(maximumWidth, mode.getWidth());
            maximumHeight = Math.max(maximumHeight, mode.getHeight());
        }
        return new DisplaySize(maximumWidth, maximumHeight);
    }

    /// Immutable physical display dimensions.
    ///
    /// @param width physical display width
    /// @param height physical display height
    @NotNullByDefault
    private record DisplaySize(int width, int height) {
    }
}
