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

import com.formdev.flatlaf.extras.FlatSVGIcon;
import net.miginfocom.swing.MigLayout;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.setting.InstanceIconType;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;

import javax.swing.ButtonGroup;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JToggleButton;
import java.awt.Component;
import java.awt.Dimension;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/// Presents the fourteen bundled instance icons and one custom-image entry as a Swing dialog.
///
/// The fixed five-column grid is keyboard navigable through a native `ButtonGroup`, keeps exactly one
/// option selected, and delegates custom-file acquisition to the existing safe file chooser boundary.
@NotNullByDefault
final class InstanceIconChooserDialog {
    /// Client-property key used to retain one built-in enum value on its toggle button.
    private static final String ICON_TYPE_PROPERTY = "instanceIconType";

    /// Exact bundled choices exposed by the former JavaFX dialog, in its established order.
    private static final @Unmodifiable List<InstanceIconType> BUILT_IN_TYPES = List.of(
            InstanceIconType.GRASS,
            InstanceIconType.CHEST,
            InstanceIconType.CHICKEN,
            InstanceIconType.COMMAND,
            InstanceIconType.APRIL_FOOLS,
            InstanceIconType.OPTIFINE,
            InstanceIconType.CRAFT_TABLE,
            InstanceIconType.FABRIC,
            InstanceIconType.LEGACY_FABRIC,
            InstanceIconType.FORGE,
            InstanceIconType.CLEANROOM,
            InstanceIconType.NEO_FORGE,
            InstanceIconType.FURNACE,
            InstanceIconType.QUILT);

    /// Stable visible and assistive text supplied by the overview.
    private final InstanceOverviewStrings strings;

    /// Dialog content containing the single-select icon grid.
    private final JPanel content = new JPanel(new MigLayout(
            "insets 12, wrap 5, gap 8",
            "[64!][64!][64!][64!][64!]",
            "[64!][64!][64!]"));

    /// Custom-image entry, selected when the current instance has a persisted custom file.
    private final JToggleButton customButton = new JToggleButton();

    /// Immutable built-in toggle buttons in the same order as `BUILT_IN_TYPES`.
    private final @Unmodifiable List<JToggleButton> builtInButtons;

    /// Creates one dialog model and selects the currently active image identity.
    ///
    /// @param currentType current persisted bundled fallback type
    /// @param hasCustomImage whether a custom image currently overrides the bundled type
    /// @param strings stable dialog text
    InstanceIconChooserDialog(
            InstanceIconType currentType,
            boolean hasCustomImage,
            InstanceOverviewStrings strings) {
        EdtDispatcher.requireEventDispatchThread();
        InstanceIconType validatedType = Objects.requireNonNull(currentType, "currentType");
        this.strings = Objects.requireNonNull(strings, "strings");
        content.setName("instanceIconChooser");
        content.setOpaque(false);

        ButtonGroup choices = new ButtonGroup();
        configureCustomButton();
        choices.add(customButton);
        content.add(customButton, "w 64!, h 64!");

        List<JToggleButton> mutableButtons = new ArrayList<>(BUILT_IN_TYPES.size());
        InstanceIconType activeBuiltIn = validatedType == InstanceIconType.DEFAULT
                ? InstanceIconType.GRASS
                : validatedType;
        for (InstanceIconType iconType : BUILT_IN_TYPES) {
            JToggleButton button = createBuiltInButton(iconType);
            button.setSelected(!hasCustomImage && iconType == activeBuiltIn);
            choices.add(button);
            content.add(button, "w 64!, h 64!");
            mutableButtons.add(button);
        }
        builtInButtons = List.copyOf(mutableButtons);
        customButton.setSelected(hasCustomImage);
    }

    /// Shows the modal selector and resolves its custom entry through the supplied file chooser.
    ///
    /// @param owner parent component for the modal dialog
    /// @param currentType current persisted bundled fallback type
    /// @param hasCustomImage whether a custom image currently overrides the bundled type
    /// @param strings stable dialog text
    /// @param customFileChooser existing safe custom-image chooser
    /// @return completed icon selection, or `null` when either dialog is cancelled
    static @Nullable InstanceIconChoice show(
            Component owner,
            InstanceIconType currentType,
            boolean hasCustomImage,
            InstanceOverviewStrings strings,
            Supplier<@Nullable Path> customFileChooser) {
        EdtDispatcher.requireEventDispatchThread();
        InstanceIconChooserDialog chooser = new InstanceIconChooserDialog(currentType, hasCustomImage, strings);
        int result = JOptionPane.showConfirmDialog(
                Objects.requireNonNull(owner, "owner"),
                chooser.content,
                strings.iconChooserTitle(),
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE);
        if (result != JOptionPane.OK_OPTION) {
            return null;
        }
        return chooser.selectedChoice(customFileChooser);
    }

    /// Returns the dialog content for focused component and off-screen rendering tests.
    ///
    /// @return stable icon grid component
    JPanel content() {
        EdtDispatcher.requireEventDispatchThread();
        return content;
    }

    /// Returns the exact built-in type order.
    ///
    /// @return immutable list containing fourteen distinct selectable types
    static @Unmodifiable List<InstanceIconType> builtInTypes() {
        return BUILT_IN_TYPES;
    }

    /// Resolves the selected toggle into a completed overview choice.
    ///
    /// The custom file supplier is invoked only when the custom tile is selected.
    ///
    /// @param customFileChooser custom file acquisition callback
    /// @return completed choice, or `null` when custom file acquisition is cancelled
    @Nullable InstanceIconChoice selectedChoice(Supplier<@Nullable Path> customFileChooser) {
        EdtDispatcher.requireEventDispatchThread();
        Supplier<@Nullable Path> validatedChooser = Objects.requireNonNull(customFileChooser, "customFileChooser");
        if (customButton.isSelected()) {
            @Nullable Path selectedFile = validatedChooser.get();
            return selectedFile != null ? new InstanceIconChoice.Custom(selectedFile) : null;
        }
        for (JToggleButton button : builtInButtons) {
            if (button.isSelected()) {
                @Nullable Object property = button.getClientProperty(ICON_TYPE_PROPERTY);
                if (property instanceof InstanceIconType iconType) {
                    return new InstanceIconChoice.BuiltIn(iconType);
                }
            }
        }
        return null;
    }

    /// Configures the semantic custom-image tile with the bundled image symbol.
    private void configureCustomButton() {
        configureTile(customButton, "instanceIconCustom", strings.customIconTooltip());
        customButton.setIcon(new FlatSVGIcon("assets/swing/icons/image.svg", 32, 32));
    }

    /// Creates one fixed-size bundled icon tile.
    ///
    /// @param iconType icon represented by the tile
    /// @return configured single-select toggle button
    private static JToggleButton createBuiltInButton(InstanceIconType iconType) {
        InstanceIconType validatedType = Objects.requireNonNull(iconType, "iconType");
        JToggleButton button = new JToggleButton();
        String displayName = displayName(validatedType);
        configureTile(button, "instanceIcon" + validatedType.name(), displayName);
        button.putClientProperty(ICON_TYPE_PROPERTY, validatedType);
        button.setIcon(InstanceIconImages.loadBuiltIn(validatedType, 36));
        return button;
    }

    /// Applies stable geometry and accessible text to one icon-only toggle.
    ///
    /// @param button target toggle button
    /// @param name stable component name
    /// @param tooltip visible and assistive icon description
    private static void configureTile(JToggleButton button, String name, String tooltip) {
        JToggleButton validatedButton = Objects.requireNonNull(button, "button");
        String validatedTooltip = requireNonBlank(tooltip, "tooltip");
        validatedButton.setName(requireNonBlank(name, "name"));
        validatedButton.setText(null);
        validatedButton.setToolTipText(validatedTooltip);
        validatedButton.getAccessibleContext().setAccessibleName(validatedTooltip);
        validatedButton.setPreferredSize(new Dimension(64, 64));
        validatedButton.setMinimumSize(new Dimension(64, 64));
        validatedButton.setMaximumSize(new Dimension(64, 64));
        validatedButton.putClientProperty("JButton.buttonType", "toolBarButton");
    }

    /// Produces concise names for the icon-only bundled choices.
    ///
    /// @param iconType bundled icon type
    /// @return non-blank assistive display name
    private static String displayName(InstanceIconType iconType) {
        return switch (Objects.requireNonNull(iconType, "iconType")) {
            case DEFAULT, GRASS -> "Grass";
            case CHEST -> "Chest";
            case CHICKEN -> "Chicken";
            case COMMAND -> "Command block";
            case OPTIFINE -> "OptiFine";
            case CRAFT_TABLE -> "Crafting table";
            case FABRIC -> "Fabric";
            case FORGE -> "Forge";
            case NEO_FORGE -> "NeoForge";
            case FURNACE -> "Furnace";
            case QUILT -> "Quilt";
            case APRIL_FOOLS -> "April Fools";
            case CLEANROOM -> "Cleanroom";
            case LEGACY_FABRIC -> "Legacy Fabric";
        };
    }

    /// Validates required non-blank component text.
    ///
    /// @param value candidate text
    /// @param name parameter name
    /// @return validated text
    private static String requireNonBlank(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
