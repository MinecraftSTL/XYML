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
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.game.QuickPlayType;

import javax.swing.ButtonGroup;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JTextField;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.util.EnumMap;
import java.util.List;
import java.util.Objects;

import static space.minecraftstl.xyml.util.i18n.I18n.i18n;

/// Mutually exclusive Quick Play modes aligned with their target editors.
///
/// Instance editors begin with an explicit whole-group inheritance choice. Global presets directly choose one local
/// mode, and selecting a target-bearing mode immediately enables its matching editor.
@NotNullByDefault
final class InstanceQuickPlayModeSelector {
    /// Stable visual order shared by instance and global-preset editors.
    private static final @Unmodifiable List<QuickPlayType> DISPLAY_ORDER = List.of(
            QuickPlayType.NONE,
            QuickPlayType.MULTIPLAYER,
            QuickPlayType.SINGLEPLAYER,
            QuickPlayType.REALMS);

    /// Whether the owning editor can inherit its Quick Play mode.
    private final boolean inheritanceAvailable;

    /// Exclusive native Swing selection group.
    private final ButtonGroup buttonGroup = new ButtonGroup();

    /// Instance-only choice that keeps the effective mode inherited.
    private final JRadioButton inheritanceButton = createInheritanceButton();

    /// Radio button representing each durable Quick Play mode.
    private final EnumMap<QuickPlayType, JRadioButton> buttons = new EnumMap<>(QuickPlayType.class);

    /// Multiplayer server target represented by its mode row.
    private final InheritedControl<JTextField> multiplayerControl;

    /// Singleplayer world target represented by its mode row.
    private final InheritedControl<JTextField> singleplayerControl;

    /// Realms target represented by its mode row.
    private final InheritedControl<JTextField> realmsControl;

    /// Prevents applying persisted values from marking the selector as user-edited.
    private boolean applying;

    /// Whether the user changed a mode or target since the last persisted snapshot was applied.
    private boolean edited;

    /// Creates a selector for an instance or a direct global preset.
    ///
    /// @param inheritanceAvailable whether to expose the leading inheritance choice
    /// @param multiplayerControl multiplayer target control
    /// @param singleplayerControl singleplayer target control
    /// @param realmsControl Realms target control
    InstanceQuickPlayModeSelector(
            boolean inheritanceAvailable,
            InheritedControl<JTextField> multiplayerControl,
            InheritedControl<JTextField> singleplayerControl,
            InheritedControl<JTextField> realmsControl) {
        this.inheritanceAvailable = inheritanceAvailable;
        this.multiplayerControl = Objects.requireNonNull(multiplayerControl, "multiplayerControl");
        this.singleplayerControl = Objects.requireNonNull(singleplayerControl, "singleplayerControl");
        this.realmsControl = Objects.requireNonNull(realmsControl, "realmsControl");
        if (inheritanceAvailable) {
            buttonGroup.add(inheritanceButton);
        }
        for (QuickPlayType type : DISPLAY_ORDER) {
            JRadioButton button = createModeButton(type);
            buttons.put(type, button);
            buttonGroup.add(button);
        }
        (inheritanceAvailable ? inheritanceButton : button(QuickPlayType.NONE)).setSelected(true);
        configureEditTracking();
    }

    /// Adds ordered mode rows and their matching target editors to a three-column settings section.
    ///
    /// @param section target settings section
    void addRows(JPanel section) {
        JPanel validatedSection = Objects.requireNonNull(section, "section");
        if (inheritanceAvailable) {
            addRow(validatedSection, inheritanceButton, emptyTargetEditor());
        }
        addRow(validatedSection, button(QuickPlayType.NONE), emptyTargetEditor());
        addTargetRow(validatedSection, QuickPlayType.MULTIPLAYER, multiplayerControl);
        addTargetRow(validatedSection, QuickPlayType.SINGLEPLAYER, singleplayerControl);
        addTargetRow(validatedSection, QuickPlayType.REALMS, realmsControl);
    }

    /// Returns the modes in their stable visual order.
    ///
    /// @return immutable mode order
    static @Unmodifiable List<QuickPlayType> displayOrder() {
        return DISPLAY_ORDER;
    }

    /// Returns whether the instance-specific inheritance choice is selected.
    ///
    /// @return true when the Quick Play mode is inherited
    boolean isInherited() {
        return inheritanceAvailable && inheritanceButton.isSelected();
    }

    /// Returns the selected local mode.
    ///
    /// @return selected Quick Play mode
    /// @throws IllegalStateException when the inheritance choice is selected
    QuickPlayType selectedType() {
        for (QuickPlayType type : DISPLAY_ORDER) {
            if (button(type).isSelected()) {
                return type;
            }
        }
        throw new IllegalStateException("Quick Play mode is inherited instead of locally selected");
    }

    /// Resolves the effective mode represented by the current selection.
    ///
    /// @param inherited currently inherited effective mode
    /// @return inherited or locally selected mode
    QuickPlayType effectiveType(QuickPlayType inherited) {
        return isInherited() ? Objects.requireNonNull(inherited, "inherited") : selectedType();
    }

    /// Builds whole-group override markers and target values from the current radio selection.
    ///
    /// @param current current effective Quick Play values
    /// @return edited Quick Play settings
    InstanceGameSettingsSnapshot.QuickPlaySettings editedSettings(
            InstanceGameSettingsSnapshot.QuickPlaySettings current) {
        InstanceGameSettingsSnapshot.QuickPlaySettings inherited = Objects.requireNonNull(current, "current");
        if (!edited) {
            return inherited;
        }
        boolean local = !isInherited();
        QuickPlayType type = effectiveType(inherited.type());
        return new InstanceGameSettingsSnapshot.QuickPlaySettings(
                local,
                type,
                local && type == QuickPlayType.MULTIPLAYER,
                multiplayerControl.editor().getText().trim(),
                local && type == QuickPlayType.SINGLEPLAYER,
                singleplayerControl.editor().getText().trim(),
                local && type == QuickPlayType.REALMS,
                realmsControl.editor().getText().trim());
    }

    /// Applies durable whole-group values without firing an action event.
    ///
    /// @param values effective Quick Play values and override state
    void apply(InstanceGameSettingsSnapshot.QuickPlaySettings values) {
        InstanceGameSettingsSnapshot.QuickPlaySettings snapshot = Objects.requireNonNull(values, "values");
        applying = true;
        try {
            multiplayerControl.editor().setText(snapshot.multiplayer());
            singleplayerControl.editor().setText(snapshot.singleplayer());
            realmsControl.editor().setText(snapshot.realms());
            boolean locallyConfigured = snapshot.typeOverridden()
                    || snapshot.multiplayerOverridden()
                    || snapshot.singleplayerOverridden()
                    || snapshot.realmsOverridden();
            if (inheritanceAvailable && !locallyConfigured) {
                inheritanceButton.setSelected(true);
            } else {
                button(snapshot.type()).setSelected(true);
            }
        } finally {
            applying = false;
        }
        edited = false;
    }

    /// Enables radio choices and only the target belonging to the selected local mode.
    ///
    /// @param writable whether the owning settings surface accepts edits
    void updateAvailability(boolean writable) {
        setEnabled(writable);
        boolean local = writable && !isInherited();
        QuickPlayType type = local ? selectedType() : QuickPlayType.NONE;
        multiplayerControl.editor().setEnabled(local && type == QuickPlayType.MULTIPLAYER);
        singleplayerControl.editor().setEnabled(local && type == QuickPlayType.SINGLEPLAYER);
        realmsControl.editor().setEnabled(local && type == QuickPlayType.REALMS);
    }

    /// Registers a callback for user selection changes.
    ///
    /// @param listener callback invoked after a radio choice is selected
    void addSelectionListener(Runnable listener) {
        Runnable validatedListener = Objects.requireNonNull(listener, "listener");
        if (inheritanceAvailable) {
            addSelectionListener(inheritanceButton, validatedListener);
        }
        for (JRadioButton button : buttons.values()) {
            addSelectionListener(button, validatedListener);
        }
    }

    /// Applies interaction availability to every visible mode choice.
    ///
    /// @param enabled whether users may change the Quick Play mode
    void setEnabled(boolean enabled) {
        if (inheritanceAvailable) {
            inheritanceButton.setEnabled(enabled);
        }
        for (JRadioButton button : buttons.values()) {
            button.setEnabled(enabled);
        }
    }

    /// Returns the instance-only inheritance radio button.
    ///
    /// @return inheritance choice
    JRadioButton inheritanceButton() {
        return inheritanceButton;
    }

    /// Returns the radio button representing one local mode.
    ///
    /// @param type represented Quick Play mode
    /// @return native mode radio button
    JRadioButton button(QuickPlayType type) {
        return Objects.requireNonNull(buttons.get(Objects.requireNonNull(type, "type")), "missing mode button");
    }

    /// Adds one target-bearing mode row without a second selection control.
    ///
    /// @param section target settings section
    /// @param type represented target mode
    /// @param control target editor holder
    private void addTargetRow(
            JPanel section,
            QuickPlayType type,
            InheritedControl<JTextField> control) {
        JRadioButton button = button(type);
        control.editor().getAccessibleContext().setAccessibleName(button.getText());
        addRow(section, button, control.editor());
    }

    /// Adds one complete row to the owning three-column section.
    ///
    /// @param section target settings section
    /// @param button mode choice
    /// @param editor target editor or transparent placeholder
    private static void addRow(
            JPanel section,
            JRadioButton button,
            JComponent editor) {
        section.add(Objects.requireNonNull(button, "button"), "span 2, growx, aligny center");
        section.add(Objects.requireNonNull(editor, "editor"), "growx");
    }

    /// Creates an invisible text editor that keeps untargeted rows aligned across look-and-feel updates.
    ///
    /// @return invisible editor-sized placeholder
    private static JTextField emptyTargetEditor() {
        JTextField placeholder = new JTextField();
        placeholder.setVisible(false);
        return placeholder;
    }

    /// Tracks explicit mode clicks and target text changes without treating snapshot application as an edit.
    private void configureEditTracking() {
        if (inheritanceAvailable) {
            inheritanceButton.addActionListener(event -> markEdited());
        }
        for (JRadioButton button : buttons.values()) {
            button.addActionListener(event -> markEdited());
        }
        DocumentListener targetListener = new DocumentListener() {
            /// Marks inserted target text as edited.
            @Override
            public void insertUpdate(DocumentEvent event) {
                markEdited();
            }

            /// Marks removed target text as edited.
            @Override
            public void removeUpdate(DocumentEvent event) {
                markEdited();
            }

            /// Marks attribute changes as edited for document implementations that emit them.
            @Override
            public void changedUpdate(DocumentEvent event) {
                markEdited();
            }
        };
        multiplayerControl.editor().getDocument().addDocumentListener(targetListener);
        singleplayerControl.editor().getDocument().addDocumentListener(targetListener);
        realmsControl.editor().getDocument().addDocumentListener(targetListener);
    }

    /// Marks the Quick Play group as user-edited unless persisted values are being applied.
    private void markEdited() {
        if (!applying) {
            edited = true;
        }
    }

    /// Creates the localized inheritance choice.
    ///
    /// @return configured inheritance radio button
    private static JRadioButton createInheritanceButton() {
        JRadioButton button = new JRadioButton(i18n("settings.game.java_directory.inherit"));
        button.setName("instanceGameSettingsQuickPlayModeInherit");
        button.setOpaque(false);
        return button;
    }

    /// Creates one localized local-mode choice.
    ///
    /// @param type represented Quick Play mode
    /// @return configured mode radio button
    private static JRadioButton createModeButton(QuickPlayType type) {
        QuickPlayType validatedType = Objects.requireNonNull(type, "type");
        JRadioButton button = new JRadioButton(InstanceGameSettingsRenderers.quickPlayTypeName(validatedType));
        button.setName("instanceGameSettingsQuickPlayMode" + validatedType.name());
        button.setOpaque(false);
        return button;
    }

    /// Registers one selected-only callback on a radio button.
    ///
    /// @param button source radio button
    /// @param listener validated selection callback
    private static void addSelectionListener(JRadioButton button, Runnable listener) {
        button.addActionListener(event -> {
            if (button.isSelected()) {
                listener.run();
            }
        });
    }
}
