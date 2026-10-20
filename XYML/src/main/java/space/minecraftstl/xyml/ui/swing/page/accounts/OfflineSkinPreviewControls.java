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
package space.minecraftstl.xyml.ui.swing.page.accounts;

import net.miginfocom.swing.MigLayout;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import javax.swing.DefaultListCellRenderer;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import java.awt.Component;
import java.util.Objects;

import static space.minecraftstl.xyml.util.i18n.I18n.i18n;

/// Combines the interactive software skin preview with movement and posture selectors.
@NotNullByDefault
final class OfflineSkinPreviewControls extends JPanel {
    /// Custom software-rendered preview surface.
    private final OfflineSkinPreviewPanel preview = new OfflineSkinPreviewPanel();

    /// Movement cycle selector.
    private final JComboBox<SkinPreviewMotion> motion = new JComboBox<>(SkinPreviewMotion.values());

    /// Body posture selector.
    private final JComboBox<SkinPreviewPosture> posture = new JComboBox<>(SkinPreviewPosture.values());

    /// Guards programmatic selector synchronization from recursive action delivery.
    private boolean synchronizingSelection;

    /// Whether the surrounding dialog currently permits animation selection.
    private boolean animationControlsEnabled = true;

    /// Creates localized preview controls with valid default movement and posture.
    OfflineSkinPreviewControls() {
        super(new MigLayout("insets 0, fill, wrap 1", "[grow,fill]", "[grow,fill][]4[]"));
        configureSelectors();
        add(preview, "grow");
        add(createSelectorRow(i18n("account.skin.preview.movement"), motion, "offlineSkinPreviewMotion"), "growx");
        add(createSelectorRow(i18n("account.skin.preview.posture"), posture, "offlineSkinPreviewPosture"), "growx");
    }

    /// Returns the reusable preview surface.
    ///
    /// @return preview component owned by this panel
    OfflineSkinPreviewPanel preview() {
        return preview;
    }

    /// Displays one decoded preview in the nested software renderer.
    ///
    /// @param value decoded skin payload
    void showPreview(OfflineSkinPreview value) {
        preview.showPreview(value);
    }

    /// Displays one localized placeholder or failure message.
    ///
    /// @param text localized message
    void showMessage(String text) {
        preview.showMessage(text);
    }

    /// Enables or disables the movement and posture selectors.
    ///
    /// @param enabled whether selectors accept input
    void setAnimationControlsEnabled(boolean enabled) {
        animationControlsEnabled = enabled;
        posture.setEnabled(enabled);
        updateMotionControlState();
    }

    /// Configures accessible names, localized renderers, and valid state synchronization.
    private void configureSelectors() {
        motion.getAccessibleContext().setAccessibleName(i18n("account.skin.preview.movement"));
        posture.getAccessibleContext().setAccessibleName(i18n("account.skin.preview.posture"));
        motion.setRenderer(new MovementRenderer());
        posture.setRenderer(new PostureRenderer());
        motion.addActionListener(event -> movementChanged());
        posture.addActionListener(event -> postureChanged());
    }

    /// Creates one labeled selector row.
    ///
    /// @param label localized field label
    /// @param selector selector to place on the row
    /// @param name stable component name
    /// @return configured row panel
    private static JPanel createSelectorRow(
            String label,
            JComboBox<?> selector,
            String name) {
        JPanel row = new JPanel(new MigLayout("insets 0, fillx", "[pref!][grow,fill]", "[]"));
        row.setName(name + "Row");
        row.add(new JLabel(label));
        selector.setName(name);
        row.add(selector, "growx");
        return row;
    }

    /// Applies one movement selection and restores standing when sprinting is selected.
    private void movementChanged() {
        if (synchronizingSelection) {
            return;
        }
        SkinPreviewMotion selected = selectedMotion();
        if (selected == SkinPreviewMotion.SPRINTING && selectedPosture() != SkinPreviewPosture.STANDING) {
            synchronizingSelection = true;
            posture.setSelectedItem(SkinPreviewPosture.STANDING);
            preview.setPosture(SkinPreviewPosture.STANDING);
            synchronizingSelection = false;
        }
        preview.setMotion(selected);
    }

    /// Applies one posture selection and downgrades sprinting to walking for non-upright postures.
    private void postureChanged() {
        if (synchronizingSelection) {
            return;
        }
        SkinPreviewPosture selectedPosture = selectedPosture();
        SkinPreviewMotion selectedMotion = selectedMotion();
        if (selectedPosture == SkinPreviewPosture.RIDING) {
            selectedMotion = SkinPreviewMotion.IDLE;
            synchronizingSelection = true;
            motion.setSelectedItem(selectedMotion);
            synchronizingSelection = false;
        } else if (selectedMotion == SkinPreviewMotion.SPRINTING
                && selectedPosture != SkinPreviewPosture.STANDING) {
            selectedMotion = SkinPreviewMotion.WALKING;
            synchronizingSelection = true;
            motion.setSelectedItem(selectedMotion);
            synchronizingSelection = false;
        }
        updateMotionControlState();
        preview.setMotion(selectedMotion);
        preview.setPosture(selectedPosture);
    }

    /// Enables movement only for postures where vanilla permits it.
    private void updateMotionControlState() {
        motion.setEnabled(animationControlsEnabled && selectedPosture() != SkinPreviewPosture.RIDING);
    }

    /// Returns the selected movement, falling back to idle for an unexpected empty model.
    ///
    /// @return selected movement
    private SkinPreviewMotion selectedMotion() {
        @Nullable SkinPreviewMotion selected = (SkinPreviewMotion) motion.getSelectedItem();
        return selected == null ? SkinPreviewMotion.IDLE : selected;
    }

    /// Returns the selected posture, falling back to standing for an unexpected empty model.
    ///
    /// @return selected posture
    private SkinPreviewPosture selectedPosture() {
        @Nullable SkinPreviewPosture selected = (SkinPreviewPosture) posture.getSelectedItem();
        return selected == null ? SkinPreviewPosture.STANDING : selected;
    }

    /// Renders movement values through the launcher localization service.
    @NotNullByDefault
    private static final class MovementRenderer extends DefaultListCellRenderer {
        /// Produces one localized movement label.
        @Override
        public Component getListCellRendererComponent(
                JList<?> list,
                @Nullable Object value,
                int index,
                boolean selected,
                boolean focused) {
            String text = value instanceof SkinPreviewMotion movement
                    ? movementText(movement)
                    : " ";
            return super.getListCellRendererComponent(list, text, index, selected, focused);
        }
    }

    /// Renders posture values through the launcher localization service.
    @NotNullByDefault
    private static final class PostureRenderer extends DefaultListCellRenderer {
        /// Produces one localized posture label.
        @Override
        public Component getListCellRendererComponent(
                JList<?> list,
                @Nullable Object value,
                int index,
                boolean selected,
                boolean focused) {
            String text = value instanceof SkinPreviewPosture selectedPosture
                    ? postureText(selectedPosture)
                    : " ";
            return super.getListCellRendererComponent(list, text, index, selected, focused);
        }
    }

    /// Resolves one movement resource key.
    ///
    /// @param movement movement value
    /// @return localized movement label
    private static String movementText(SkinPreviewMotion movement) {
        return switch (Objects.requireNonNull(movement, "movement")) {
            case IDLE -> i18n("account.skin.preview.movement.idle");
            case WALKING -> i18n("account.skin.preview.movement.walking");
            case SPRINTING -> i18n("account.skin.preview.movement.sprinting");
        };
    }

    /// Resolves one posture resource key.
    ///
    /// @param posture posture value
    /// @return localized posture label
    private static String postureText(SkinPreviewPosture posture) {
        return switch (Objects.requireNonNull(posture, "posture")) {
            case STANDING -> i18n("account.skin.preview.posture.standing");
            case SNEAKING -> i18n("account.skin.preview.posture.sneaking");
            case RIDING -> i18n("account.skin.preview.posture.riding");
            case SWIMMING -> i18n("account.skin.preview.posture.swimming");
        };
    }
}
