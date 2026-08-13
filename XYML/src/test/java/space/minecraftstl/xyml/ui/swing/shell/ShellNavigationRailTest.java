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
import com.formdev.flatlaf.extras.FlatSVGIcon;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import space.minecraftstl.xyml.Metadata;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;
import space.minecraftstl.xyml.ui.swing.SwingDesignTokens;

import javax.swing.AbstractButton;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.net.URI;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies the shell's bottom navigation hierarchy and independent external actions.
@NotNullByDefault
public final class ShellNavigationRailTest {
    /// Settings remains bottom-anchored while community and help actions follow it in order.
    @Test
    public void anchorsExternalActionsBelowSettings() {
        AtomicReference<@Nullable URI> openedDestination = new AtomicReference<>();
        EdtDispatcher.executeAndWait(() -> {
            ShellNavigationRail rail = new ShellNavigationRail(
                    ShellPagePresentations.englishFallback(),
                    ignored -> { },
                    openedDestination::set);
            rail.setSize(new Dimension(52, 560));
            layoutTree(rail);

            ShellNavigationButton settings = rail.button(ShellPageId.SETTINGS);
            JButton officialGroup = rail.officialGroupButton();
            JButton help = rail.helpButton();
            javax.swing.Icon officialGroupIcon = Objects.requireNonNull(
                    officialGroup.getIcon(),
                    "official group icon");
            FlatSVGIcon helpIcon = assertInstanceOf(
                    FlatSVGIcon.class,
                    help.getIcon());
            String officialGroupTooltip = Objects.requireNonNull(
                    officialGroup.getToolTipText(),
                    "official group tooltip");
            String helpTooltip = Objects.requireNonNull(
                    help.getToolTipText(),
                    "help tooltip");
            int settingsY = SwingUtilities.convertPoint(settings, 0, 0, rail).y;
            int officialGroupY = SwingUtilities.convertPoint(officialGroup, 0, 0, rail).y;
            int helpY = SwingUtilities.convertPoint(help, 0, 0, rail).y;

            assertAll(
                    () -> assertButtonSize(settings),
                    () -> assertButtonSize(officialGroup),
                    () -> assertButtonSize(help),
                    () -> assertTrue(settingsY > rail.getHeight() / 2),
                    () -> assertTrue(officialGroupY > settingsY),
                    () -> assertTrue(helpY > officialGroupY),
                    () -> assertTrue(
                            rail.getHeight() - helpY - help.getHeight() <= 10),
                    () -> assertEquals("officialGroupButton", officialGroup.getName()),
                    () -> assertEquals("helpButton", help.getName()),
                    () -> assertNotNull(officialGroupIcon),
                    () -> assertEquals(24, officialGroupIcon.getIconWidth()),
                    () -> assertEquals(24, officialGroupIcon.getIconHeight()),
                    () -> assertTrue(helpIcon.hasFound()),
                    () -> assertEquals(24, helpIcon.getIconWidth()),
                    () -> assertEquals(24, helpIcon.getIconHeight()),
                    () -> assertFalse(officialGroupTooltip.isBlank()),
                    () -> assertFalse(helpTooltip.isBlank()),
                    () -> assertEquals(
                            officialGroupTooltip,
                            officialGroup.getAccessibleContext().getAccessibleName()),
                    () -> assertEquals(
                            helpTooltip,
                            help.getAccessibleContext().getAccessibleName()));

            officialGroup.doClick();
            assertEquals(URI.create(Metadata.GROUPS_URL), openedDestination.get());
            help.doClick();
            assertEquals(URI.create(Metadata.CONTACT_URL), openedDestination.get());

            rail.disableNavigation();
            assertAll(
                    () -> assertFalse(settings.isEnabled()),
                    () -> assertFalse(officialGroup.isEnabled()),
                    () -> assertFalse(help.isEnabled()));
        });
    }

    /// The official-community endpoint remains the repository's explicit QQ invitation instead of a generic page.
    @Test
    public void usesOfficialQqInvitationEndpoint() {
        URI destination = URI.create(Metadata.GROUPS_URL);
        String query = Objects.requireNonNull(destination.getQuery(), "official group query");
        assertAll(
                () -> assertEquals("https", destination.getScheme()),
                () -> assertEquals("qm.qq.com", destination.getHost()),
                () -> assertEquals("/cgi-bin/qm/qr", destination.getPath()),
                () -> assertTrue(query.contains("authKey=")));
    }

    /// The help action retains the migrated equivalent of the legacy title-bar contact destination.
    @Test
    public void usesProjectHelpEndpoint() {
        URI destination = URI.create(Metadata.CONTACT_URL);
        assertAll(
                () -> assertEquals("https", destination.getScheme()),
                () -> assertEquals("github.com", destination.getHost()),
                () -> assertEquals("/MinecraftSTL/XYML/issues/new/choose", destination.getPath()));
    }

    /// A radius of 18 paints the selected background across the exact 40-pixel square without toolbar insets.
    @Test
    public void rendersRoundedNavigationButtonAsFortyPixelSquare() {
        assertTrue(FlatLightLaf.setup());
        new SwingDesignTokens(18).applyTo(UIManager.getDefaults());

        EdtDispatcher.executeAndWait(() -> {
            ShellNavigationRail rail = new ShellNavigationRail(
                    ShellPagePresentations.englishFallback(),
                    ignored -> { },
                    ignored -> { });
            ShellNavigationButton button = rail.button(ShellPageId.ACCOUNTS);
            rail.setSelectedPage(ShellPageId.ACCOUNTS);
            button.setSize(ShellNavigationRail.BUTTON_SIZE, ShellNavigationRail.BUTTON_SIZE);

            BufferedImage image = new BufferedImage(
                    ShellNavigationRail.BUTTON_SIZE,
                    ShellNavigationRail.BUTTON_SIZE,
                    BufferedImage.TYPE_INT_ARGB);
            Graphics2D graphics = image.createGraphics();
            try {
                button.paint(graphics);
            } finally {
                graphics.dispose();
            }

            int center = ShellNavigationRail.BUTTON_SIZE / 2;
            int last = ShellNavigationRail.BUTTON_SIZE - 1;
            assertAll(
                    () -> assertButtonSize(button),
                    () -> assertTrue(alphaAt(image, center, 0) > 0),
                    () -> assertTrue(alphaAt(image, center, last) > 0),
                    () -> assertTrue(alphaAt(image, 0, center) > 0),
                    () -> assertTrue(alphaAt(image, last, center) > 0));
        });
    }

    /// Verifies the exact component geometry shared by every navigation-rail action.
    ///
    /// @param button navigation-rail button to measure after layout
    private static void assertButtonSize(AbstractButton button) {
        assertAll(
                () -> assertEquals(ShellNavigationRail.BUTTON_SIZE, button.getWidth()),
                () -> assertEquals(ShellNavigationRail.BUTTON_SIZE, button.getHeight()));
    }

    /// Returns the alpha channel at one rendered pixel.
    ///
    /// @param image rendered button image
    /// @param x horizontal pixel coordinate
    /// @param y vertical pixel coordinate
    /// @return alpha value from zero through 255
    private static int alphaAt(BufferedImage image, int x, int y) {
        return image.getRGB(x, y) >>> 24;
    }

    /// Recursively lays out a test component tree without opening a native window.
    ///
    /// @param component root component to lay out
    private static void layoutTree(Component component) {
        if (component instanceof JComponent swingComponent) {
            swingComponent.doLayout();
        }
        if (component instanceof JPanel panel) {
            for (Component child : panel.getComponents()) {
                layoutTree(child);
            }
        }
    }
}
