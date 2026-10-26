// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.shell;

import com.formdev.flatlaf.FlatLightLaf;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import space.minecraftstl.xyml.ui.swing.SwingDesignTokens;

import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies exact-radius rendering for the shell popup's middle single-choice region.
@NotNullByDefault
public final class RoundedChoicePanelTest {
    /// The host clips opaque list content at nonzero radii and becomes rectangular at zero radius.
    @Test
    public void clipsSingleChoiceContentToCurrentRadius() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            assertTrue(FlatLightLaf.setup());
            RoundedChoicePanel choicePanel = new RoundedChoicePanel(new BorderLayout());
            JPanel listContent = new JPanel();
            listContent.setBackground(new Color(83, 171, 102));
            choicePanel.add(listContent, BorderLayout.CENTER);
            choicePanel.setSize(new Dimension(220, 96));
            choicePanel.doLayout();

            new SwingDesignTokens(12).applyTo(UIManager.getDefaults());
            BufferedImage rounded = render(choicePanel);
            assertEquals(0, rounded.getRGB(0, 0) >>> 24);
            assertEquals(255, rounded.getRGB(rounded.getWidth() / 2, 0) >>> 24);

            new SwingDesignTokens(0).applyTo(UIManager.getDefaults());
            BufferedImage square = render(choicePanel);
            assertEquals(255, square.getRGB(0, 0) >>> 24);
        });
    }

    /// Paints one configured choice surface into a transparent image.
    ///
    /// @param choicePanel surface to paint
    /// @return rendered pixels
    private static BufferedImage render(RoundedChoicePanel choicePanel) {
        BufferedImage image = new BufferedImage(
                choicePanel.getWidth(),
                choicePanel.getHeight(),
                BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try {
            choicePanel.paint(graphics);
        } finally {
            graphics.dispose();
        }
        return image;
    }
}
