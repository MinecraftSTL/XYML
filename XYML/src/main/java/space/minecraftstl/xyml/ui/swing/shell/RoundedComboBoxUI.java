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
import com.formdev.flatlaf.util.UIScale;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import javax.swing.border.Border;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JList;
import javax.swing.UIManager;
import javax.swing.plaf.ComponentUI;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.Rectangle;
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
    protected RoundedComboPopup createPopup() {
        return new RoundedComboPopup(comboBox);
    }

    /// Returns the installed rounded popup for package-level geometry verification.
    ///
    /// @return popup owned by this installed UI delegate
    RoundedComboPopup roundedPopup() {
        return (RoundedComboPopup) popup;
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
                current = new RoundedPopupSupport(this, this::outerCornerRadius);
                roundedSupport = current;
            }
            return current;
        }

        /// Computes the outer radius from the first and last elements' actual painted radius and shared spacing.
        ///
        /// FlatLaf limits an element radius to half of its rendered bounds. The popup must use that limited radius,
        /// rather than the larger configured value, to keep its outline concentric with compact settings rows.
        ///
        /// @return zero for square elements, otherwise actual element radius plus element-to-popup spacing
        double outerCornerRadius() {
            int selectionArc = Math.max(0, UIManager.getInt("ComboBox.selectionArc"));
            if (selectionArc == 0) {
                return 0.0;
            }
            Insets selectionInsets = scaledSelectionInsets();
            double configuredRadius = UIScale.scale(selectionArc / 2.0F);
            double elementRadius = actualElementCornerRadius(configuredRadius, selectionInsets);
            return RoundedPopupSupport.concentricOuterCornerRadius(elementRadius, elementSpacing(selectionInsets));
        }

        /// Returns the popup list for package-level geometry verification.
        ///
        /// @return list whose first and last row geometry controls the popup radius
        JList<?> popupList() {
            return list;
        }

        /// Limits the configured radius to the actual selection bounds of both endpoint rows.
        ///
        /// @param configuredRadius scaled configured element radius
        /// @param selectionInsets scaled insets applied while painting an element selection
        /// @return smallest actual endpoint radius
        private double actualElementCornerRadius(double configuredRadius, Insets selectionInsets) {
            int itemCount = list.getModel().getSize();
            if (itemCount == 0) {
                return configuredRadius;
            }
            @Nullable Rectangle firstBounds = list.getCellBounds(0, 0);
            @Nullable Rectangle lastBounds = list.getCellBounds(itemCount - 1, itemCount - 1);
            return Math.min(
                    limitedElementCornerRadius(configuredRadius, firstBounds, selectionInsets),
                    limitedElementCornerRadius(configuredRadius, lastBounds, selectionInsets));
        }

        /// Limits one endpoint radius exactly as FlatLaf limits its painted selection path.
        ///
        /// @param configuredRadius scaled configured element radius
        /// @param cellBounds endpoint cell bounds, or `null` before list geometry is available
        /// @param selectionInsets scaled insets applied while painting an element selection
        /// @return actual radius visible within the endpoint cell
        private static double limitedElementCornerRadius(
                double configuredRadius,
                @Nullable Rectangle cellBounds,
                Insets selectionInsets) {
            if (cellBounds == null) {
                return configuredRadius;
            }
            int selectionWidth = cellBounds.width - selectionInsets.left - selectionInsets.right;
            int selectionHeight = cellBounds.height - selectionInsets.top - selectionInsets.bottom;
            if (selectionHeight <= 0 || cellBounds.width > 0 && selectionWidth <= 0) {
                return 0.0;
            }
            double limitingSize = cellBounds.width > 0
                    ? Math.min(selectionWidth, selectionHeight)
                    : selectionHeight;
            return Math.min(configuredRadius, limitingSize / 2.0);
        }

        /// Returns the common structural spacing between an endpoint selection and the popup outline.
        ///
        /// @param selectionInsets scaled insets applied while painting an element selection
        /// @return smallest non-negative spacing shared by all four sides
        private double elementSpacing(Insets selectionInsets) {
            Insets popupInsets = getInsets();
            Insets scrollerInsets = scroller.getInsets();
            Insets viewportInsets = scroller.getViewport().getInsets();
            Insets listInsets = list.getInsets();
            Insets viewportBorderInsets = borderInsets(scroller.getViewportBorder());
            int top = popupInsets.top + scrollerInsets.top + viewportBorderInsets.top
                    + viewportInsets.top + listInsets.top + selectionInsets.top;
            int left = popupInsets.left + scrollerInsets.left + viewportBorderInsets.left
                    + viewportInsets.left + listInsets.left + selectionInsets.left;
            int bottom = popupInsets.bottom + scrollerInsets.bottom + viewportBorderInsets.bottom
                    + viewportInsets.bottom + listInsets.bottom + selectionInsets.bottom;
            int right = popupInsets.right + scrollerInsets.right + viewportBorderInsets.right
                    + viewportInsets.right + listInsets.right + selectionInsets.right;
            return Math.max(0, Math.min(Math.min(top, bottom), Math.min(left, right)));
        }

        /// Returns scaled selection insets matching FlatLaf's list painter.
        ///
        /// @return non-null scaled selection insets
        private static Insets scaledSelectionInsets() {
            @Nullable Insets configuredInsets = UIManager.getInsets("ComboBox.selectionInsets");
            return configuredInsets == null
                    ? new Insets(0, 0, 0, 0)
                    : UIScale.scale(configuredInsets);
        }

        /// Returns one border's effective insets without requiring callers to handle a missing border.
        ///
        /// @param border optional viewport border
        /// @return effective non-null border insets
        private Insets borderInsets(@Nullable Border border) {
            return border == null ? new Insets(0, 0, 0, 0) : border.getBorderInsets(scroller);
        }
    }
}
