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

import com.formdev.flatlaf.FlatClientProperties;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import javax.swing.JButton;
import javax.swing.JPopupMenu;
import javax.swing.SwingUtilities;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.MouseEvent;
import java.util.Objects;

/// Renders one selected shell value and a trailing disclosure chevron inside a single rounded button.
///
/// A single component lets FlatLaf's caller-selected `Button.arc` own both ends of the control. The chevron is
/// painted rather than hosted in a second button, so theme radius changes cannot leave a square trailing segment.
@NotNullByDefault
final class ShellDropdownButton extends JButton implements PopupMenuListener {
    /// Horizontal space reserved for the disclosure chevron.
    private static final int DISCLOSURE_WIDTH = 22;

    /// Maximum preferred width contributed by one selected value.
    private static final int MAX_PREFERRED_WIDTH = 180;

    /// Popup controlled by this button, or `null` before a selector binds one.
    private @Nullable JPopupMenu popup;

    /// Command that sizes and opens the bound popup, or `null` before binding.
    private @Nullable Runnable popupOpenCommand;

    /// Whether Swing hid the popup while dispatching the current input event.
    private boolean popupHiddenDuringCurrentDispatch;

    /// Whether the current mouse release must leave an automatically hidden popup closed.
    private boolean suppressPopupOpenOnRelease;

    /// Whether the bound popup is expanded and the disclosure chevron must point upward.
    private boolean popupExpanded;

    /// Complete selected text retained independently of the ellipsized rendered text.
    private String fullText = "";

    /// Prevents visible-text updates from re-entering the public text setter.
    private boolean applyingVisibleText;

    /// Creates one left-aligned popup button.
    ShellDropdownButton() {
        setOpaque(false);
        setBackground(new Color(0, 0, 0, 0));
        putClientProperty(
                FlatClientProperties.STYLE,
                "disabledBackground: #00000000; "
                        + "hoverBackground: fade($Button.hoverBackground,20%); "
                        + "pressedBackground: fade($Button.pressedBackground,28%)");
        setHorizontalAlignment(LEFT);
        setMargin(new Insets(4, 10, 4, 8 + DISCLOSURE_WIDTH));
        addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent event) {
                updateVisibleText();
            }
        });
    }

    /// Stores complete selected text and renders only the portion fitting the current width.
    @Override
    public void setText(String text) {
        fullText = Objects.requireNonNull(text, "text");
        updateVisibleText();
    }

    /// Returns complete selected text before width-based ellipsis.
    ///
    /// @return complete selected text
    String fullText() {
        return fullText;
    }

    /// Prevents full selected text from expanding the fixed title-bar columns.
    @Override
    public java.awt.Dimension getPreferredSize() {
        java.awt.Dimension preferred = super.getPreferredSize();
        return new java.awt.Dimension(Math.min(preferred.width, MAX_PREFERRED_WIDTH), preferred.height);
    }

    /// Allows the surrounding fixed-width selector to shrink independently of its full text.
    @Override
    public java.awt.Dimension getMinimumSize() {
        java.awt.Dimension minimum = super.getMinimumSize();
        return new java.awt.Dimension(0, minimum.height);
    }

    /// Recomputes text after the surrounding layout assigns the final button width.
    @Override
    public void doLayout() {
        updateVisibleText();
        super.doLayout();
        updateVisibleText();
    }

    /// Recomputes visible text from the current component geometry.
    private void updateVisibleText() {
        if (applyingVisibleText) {
            return;
        }
        String replacement = ellipsize(fullText, availableTextWidth());
        if (replacement.equals(super.getText())) {
            return;
        }
        applyingVisibleText = true;
        try {
            super.setText(replacement);
        } finally {
            applyingVisibleText = false;
        }
    }

    /// Returns horizontal space available to the text portion of the button.
    private int availableTextWidth() {
        int width = getWidth();
        if (width <= 0) {
            return 0;
        }
        Insets insets = getInsets();
        int iconWidth = getIcon() == null ? 0 : getIcon().getIconWidth() + getIconTextGap();
        return Math.max(0, width - insets.left - insets.right - iconWidth - DISCLOSURE_WIDTH);
    }

    /// Ellipsizes one string to the supplied pixel budget.
    private String ellipsize(String text, int width) {
        if (width <= 0) {
            return text;
        }
        java.awt.FontMetrics metrics = getFontMetrics(getFont());
        if (metrics.stringWidth(text) <= width) {
            return text;
        }
        String suffix = "...";
        int suffixWidth = metrics.stringWidth(suffix);
        if (suffixWidth > width) {
            return "";
        }
        int end = text.length();
        while (end > 0 && metrics.stringWidth(text.substring(0, end)) + suffixWidth > width) {
            end--;
        }
        return text.substring(0, end) + suffix;
    }

    /// Binds one reusable popup and installs the button's show-or-hide action.
    ///
    /// @param popup popup owned by the surrounding selector
    /// @param openCommand command that sizes and shows the popup
    void bindPopup(JPopupMenu popup, Runnable openCommand) {
        if (this.popup != null) {
            throw new IllegalStateException("A shell dropdown popup is already bound");
        }
        this.popup = Objects.requireNonNull(popup, "popup");
        popupOpenCommand = Objects.requireNonNull(openCommand, "openCommand");
        popup.addPopupMenuListener(this);
        addActionListener(event -> togglePopup());
    }

    /// Captures popup visibility before Swing's menu machinery can consume the invoker click.
    ///
    /// @param event mouse event dispatched to this button
    @Override
    protected void processMouseEvent(MouseEvent event) {
        int eventId = event.getID();
        @Nullable JPopupMenu currentPopup = popup;
        if (eventId == MouseEvent.MOUSE_PRESSED) {
            suppressPopupOpenOnRelease = popupHiddenDuringCurrentDispatch
                    || currentPopup != null && currentPopup.isVisible();
        } else if (eventId == MouseEvent.MOUSE_RELEASED) {
            suppressPopupOpenOnRelease |= popupHiddenDuringCurrentDispatch;
        }
        try {
            super.processMouseEvent(event);
        } finally {
            if (eventId == MouseEvent.MOUSE_RELEASED) {
                suppressPopupOpenOnRelease = false;
            }
        }
    }

    /// Clears stale dismissal state whenever the bound popup opens.
    ///
    /// @param event popup visibility event
    @Override
    public void popupMenuWillBecomeVisible(PopupMenuEvent event) {
        popupHiddenDuringCurrentDispatch = false;
        popupExpanded = true;
        repaint();
    }

    /// Records an automatic dismissal until the current input dispatch finishes.
    ///
    /// @param event popup visibility event
    @Override
    public void popupMenuWillBecomeInvisible(PopupMenuEvent event) {
        recordPopupDismissal();
    }

    /// Records a canceled popup using the same invoker-click suppression window.
    ///
    /// @param event popup cancellation event
    @Override
    public void popupMenuCanceled(PopupMenuEvent event) {
        recordPopupDismissal();
    }

    /// Paints the standard button before adding a theme-colored trailing chevron.
    ///
    /// @param graphics destination graphics
    @Override
    protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        Graphics2D copy = (Graphics2D) graphics.create();
        try {
            copy.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            copy.setColor(getForeground());
            int centerX = getComponentOrientation().isLeftToRight() ? getWidth() - 15 : 15;
            int centerY = getHeight() / 2;
            int edgeY = centerY + (popupExpanded ? 2 : -2);
            int pointY = centerY + (popupExpanded ? -2 : 2);
            copy.drawLine(centerX - 4, edgeY, centerX, pointY);
            copy.drawLine(centerX, pointY, centerX + 4, edgeY);
        } finally {
            copy.dispose();
        }
    }

    /// Shows a hidden popup or keeps a popup dismissed by the current button click closed.
    private void togglePopup() {
        JPopupMenu currentPopup = Objects.requireNonNull(popup, "popup is not bound");
        if (suppressPopupOpenOnRelease || popupHiddenDuringCurrentDispatch || currentPopup.isVisible()) {
            currentPopup.setVisible(false);
            return;
        }
        SwingUtilities.updateComponentTreeUI(currentPopup);
        Objects.requireNonNull(popupOpenCommand, "popup open command is not bound").run();
    }

    /// Retains one popup dismissal through the rest of the current AWT event dispatch.
    private void recordPopupDismissal() {
        popupExpanded = false;
        repaint();
        popupHiddenDuringCurrentDispatch = true;
        SwingUtilities.invokeLater(() -> popupHiddenDuringCurrentDispatch = false);
    }
}
