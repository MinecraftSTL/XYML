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
