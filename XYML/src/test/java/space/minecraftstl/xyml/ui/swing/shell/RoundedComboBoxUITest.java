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
package space.minecraftstl.xyml.ui.swing.shell;

import com.formdev.flatlaf.FlatLightLaf;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import space.minecraftstl.xyml.setting.LauncherSettings;

import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.ListCellRenderer;
import javax.swing.UIManager;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies the shared exact-radius combo-box delegate and custom renderer selection painting.
@NotNullByDefault
public final class RoundedComboBoxUITest {
    /// Every combo box created after theme installation receives the exact-radius delegate.
    @Test
    public void installsAsGlobalComboBoxDelegate() {
        assertTrue(FlatLightLaf.setup());
        @Nullable Object previousDelegate = UIManager.get("ComboBoxUI");
        try {
            UIManager.put("ComboBoxUI", RoundedComboBoxUI.class.getName());
            JComboBox<String> comboBox = new JComboBox<>(new String[] {"stable", "beta"});
            comboBox.updateUI();

            assertInstanceOf(RoundedComboBoxUI.class, comboBox.getUI());
        } finally {
            restoreDefault("ComboBoxUI", previousDelegate);
        }
    }

    /// A custom opaque renderer is clipped to the configured radius, including the 20px upper bound.
    @Test
    public void customRendererSelectionUsesRoundedGeometry() {
        assertTrue(FlatLightLaf.setup());
        @Nullable Object previousArc = UIManager.get("ComboBox.selectionArc");
        try {
            JList<String> list = new JList<>(new String[] {"stable", "beta"});
            list.setName("ComboBox.list");
            list.setBackground(new Color(23, 31, 41));
            list.setSelectionBackground(new Color(213, 122, 47));
            list.setSelectionForeground(Color.WHITE);
            list.setFixedCellHeight(40);
            list.setSelectedIndex(0);
            list.setCellRenderer(selectedRenderer());
            list.setSize(180, 40);
            list.setUI(new RoundedComboBoxListUI());

            UIManager.put("ComboBox.selectionArc", LauncherSettings.MAXIMUM_CORNER_RADIUS * 2);
            BufferedImage rounded = render(list);
            assertEquals(list.getBackground().getRGB(), rounded.getRGB(0, 0));
            assertEquals(list.getSelectionBackground().getRGB(), rounded.getRGB(90, 20));

            UIManager.put("ComboBox.selectionArc", 0);
            BufferedImage square = render(list);
            assertEquals(list.getSelectionBackground().getRGB(), square.getRGB(0, 0));
        } finally {
            restoreDefault("ComboBox.selectionArc", previousArc);
        }
    }

    /// Compact settings rows use their actual limited radius plus the real element-to-popup spacing.
    @Test
    public void computesPopupRadiusFromEndpointGeometryAndSpacing() {
        assertTrue(FlatLightLaf.setup());
        @Nullable Object previousArc = UIManager.get("ComboBox.selectionArc");
        @Nullable Object previousPopupInsets = UIManager.get("ComboBox.popupInsets");
        @Nullable Object previousSelectionInsets = UIManager.get("ComboBox.selectionInsets");
        try {
            UIManager.put("ComboBox.selectionArc", LauncherSettings.MAXIMUM_CORNER_RADIUS * 2);
            UIManager.put("ComboBox.popupInsets", new Insets(0, 0, 0, 0));
            UIManager.put("ComboBox.selectionInsets", new Insets(0, 0, 0, 0));
            RoundedComboBoxUI compactUi = new RoundedComboBoxUI();
            JComboBox<String> compactBox = new JComboBox<>(new String[] {"stable", "beta"});
            compactBox.setUI(compactUi);
            RoundedComboBoxUI.RoundedComboPopup compactPopup = compactUi.roundedPopup();
            compactPopup.popupList().setFixedCellWidth(180);
            compactPopup.popupList().setFixedCellHeight(28);
            compactPopup.popupList().setSize(180, 56);
            double compactElementRadius = 28.0 / 2.0;
            double compactSpacing = compactPopup.getInsets().top;
            assertEquals(compactElementRadius + compactSpacing, compactPopup.outerCornerRadius());

            UIManager.put("ComboBox.popupInsets", new Insets(3, 3, 3, 3));
            UIManager.put("ComboBox.selectionInsets", new Insets(2, 2, 2, 2));
            RoundedComboBoxUI insetUi = new RoundedComboBoxUI();
            JComboBox<String> insetBox = new JComboBox<>(new String[] {"stable", "beta"});
            insetBox.setUI(insetUi);
            RoundedComboBoxUI.RoundedComboPopup insetPopup = insetUi.roundedPopup();
            insetPopup.popupList().setFixedCellWidth(180);
            insetPopup.popupList().setFixedCellHeight(40);
            insetPopup.popupList().setSize(180, 80);
            double insetElementRadius = (40.0 - 2.0 - 2.0) / 2.0;
            double insetSpacing = insetPopup.getInsets().top + 3.0 + 2.0;
            assertEquals(insetElementRadius + insetSpacing, insetPopup.outerCornerRadius());

            UIManager.put("ComboBox.selectionArc", 0);
            assertEquals(0.0, insetPopup.outerCornerRadius());
        } finally {
            restoreDefault("ComboBox.selectionArc", previousArc);
            restoreDefault("ComboBox.popupInsets", previousPopupInsets);
            restoreDefault("ComboBox.selectionInsets", previousSelectionInsets);
        }
    }

    /// Returns a renderer that deliberately paints an opaque rectangular selected surface.
    private static ListCellRenderer<String> selectedRenderer() {
        return (list, value, index, selected, focus) -> {
            JLabel label = new JLabel(value);
            label.setOpaque(true);
            label.setBackground(selected ? list.getSelectionBackground() : list.getBackground());
            label.setForeground(selected ? list.getSelectionForeground() : list.getForeground());
            return label;
        };
    }

    /// Paints one list into an ARGB image for corner and center assertions.
    private static BufferedImage render(JList<String> list) {
        BufferedImage image = new BufferedImage(list.getWidth(), list.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try {
            list.paint(graphics);
        } finally {
            graphics.dispose();
        }
        return image;
    }

    /// Restores one UI default changed temporarily by a test.
    ///
    /// @param key UI defaults key
    /// @param value previous value, or `null` when the key was absent
    private static void restoreDefault(String key, @Nullable Object value) {
        if (value == null) {
            UIManager.getDefaults().remove(key);
        } else {
            UIManager.put(key, value);
        }
    }
}
