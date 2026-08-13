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

import com.formdev.flatlaf.ui.FlatComboBoxUI;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JList;
import javax.swing.plaf.ComponentUI;
import javax.swing.plaf.basic.ComboPopup;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.util.Objects;

/// FlatLaf combo-box delegate whose popup and every selected option follow the exact launcher radius.
@NotNullByDefault
public final class RoundedComboBoxUI extends FlatComboBoxUI {
    /// Creates a fresh UI delegate for Swing's defaults lookup.
    ///
    /// @param component combo box receiving the delegate
    /// @return new exact-radius combo-box UI
    public static ComponentUI createUI(JComponent component) {
        Objects.requireNonNull(component, "component");
        return new RoundedComboBoxUI();
    }

    /// Creates the FlatLaf-compatible popup with exact component and native-window clipping.
    ///
    /// @return exact-radius combo popup
    @Override
    protected ComboPopup createPopup() {
        return new RoundedComboPopup(comboBox);
    }

    /// FlatLaf popup retaining its behavior while replacing lossy platform radius handling.
    @NotNullByDefault
    final class RoundedComboPopup extends FlatComboPopup {
        /// Exact-radius support, initialized lazily because Swing configures popups from their superclass constructor.
        private @Nullable RoundedPopupSupport roundedSupport;

        /// Creates one popup for the owning combo box.
        ///
        /// @param combo owning combo box
        @SuppressWarnings("rawtypes")
        RoundedComboPopup(JComboBox combo) {
            super(combo);
        }

        /// Creates a list whose UI can round selections from arbitrary launcher renderers.
        ///
        /// @return configured combo-box popup list
        @Override
        @SuppressWarnings({"rawtypes", "unchecked"})
        protected JList createList() {
            JList result = super.createList();
            result.setUI(new RoundedComboBoxListUI());
            return result;
        }

        /// Restores exact-radius rendering after FlatLaf configures its standard popup surface.
        @Override
        protected void configurePopup() {
            super.configurePopup();
            roundedSupport().configurePopupRendering();
        }

        /// Clips the popup background, border, scroll pane, list, and custom renderers to one exact outline.
        ///
        /// @param graphics destination graphics
        @Override
        public void paint(Graphics graphics) {
            RoundedPopupSupport support = roundedSupport();
            Graphics2D copy = support.createPaintGraphics(graphics);
            try {
                super.paint(copy);
                support.paintRoundedBorder(copy);
            } finally {
                copy.dispose();
            }
        }

        /// Shapes a newly shown heavyweight popup and restores cached popup windows when hiding.
        ///
        /// @param visible whether the popup should be visible
        @Override
        public void setVisible(boolean visible) {
            super.setVisible(visible);
            roundedSupport().popupVisibilityChanged(visible);
        }

        /// Reapplies the native shape after a visible combo popup changes size.
        ///
        /// @param size requested popup size
        @Override
        public void setPopupSize(Dimension size) {
            super.setPopupSize(size);
            roundedSupport().popupSizeChanged();
        }

        /// Returns lazily initialized support safe to call during the Swing superclass constructor.
        ///
        /// @return exact-radius popup support
        private RoundedPopupSupport roundedSupport() {
            @Nullable RoundedPopupSupport current = roundedSupport;
            if (current == null) {
                current = new RoundedPopupSupport(this, "ComboBox.borderCornerRadius");
                roundedSupport = current;
            }
            return current;
        }
    }
}
