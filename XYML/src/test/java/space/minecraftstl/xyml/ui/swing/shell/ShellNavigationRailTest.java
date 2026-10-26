// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

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
import javax.swing.JToggleButton;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.event.KeyEvent;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.net.URI;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import static space.minecraftstl.xyml.util.i18n.I18n.i18n;

/// Verifies the shell's bottom navigation hierarchy and independent external actions.
@NotNullByDefault
public final class ShellNavigationRailTest {
    /// The homepage precedes every side destination and shares their accessible, theme-aware icon style.
    @Test
    public void placesAccessibleHomepageFirstAndKeepsSelectionExclusive() {
        AtomicInteger homeRequests = new AtomicInteger();
        EdtDispatcher.executeAndWait(() -> {
            ShellNavigationRail rail = new ShellNavigationRail(
                    ShellPagePresentations.englishFallback(),
                    homeRequests::incrementAndGet,
                    ignored -> { },
                    ignored -> { });
            try {
                rail.setSize(new Dimension(52, 560));
                layoutTree(rail);
                JToggleButton home = rail.homeButton();
                ShellNavigationButton accounts = rail.button(ShellPageId.ACCOUNTS);
                FlatSVGIcon icon = assertInstanceOf(FlatSVGIcon.class, home.getIcon());
                assertAll(
                        () -> assertButtonSize(home),
                        () -> assertEquals(0, home.getParent().getComponentZOrder(home)),
                        () -> assertTrue(home.getY() < accounts.getY()),
                        () -> assertTrue(home.isSelected()),
                        () -> assertTrue(home.isFocusable()),
                        () -> assertEquals(KeyEvent.VK_H, home.getMnemonic()),
                        () -> assertEquals(i18n("swing.shell.home"), home.getToolTipText()),
                        () -> assertEquals(i18n("swing.shell.home"), home.getAccessibleContext().getAccessibleName()),
                        () -> assertTrue(icon.hasFound()),
                        () -> assertEquals(20, icon.getIconWidth()),
                        () -> assertEquals(20, icon.getIconHeight()));
                rail.setSelectedPage(ShellPageId.ACCOUNTS);
                assertFalse(home.isSelected());
                assertTrue(accounts.isSelected());
                rail.setSelectedPage(null);
                home.doClick(0);
                home.doClick(0);
                assertEquals(2, homeRequests.get());
                assertTrue(home.isSelected());
                for (ShellPageId page : ShellPageId.values()) {
                    assertFalse(rail.button(page).isSelected());
                }
            } finally {
                rail.disableNavigation();
            }
            rail.homeButton().doClick(0);
            assertFalse(rail.homeButton().isEnabled());
            assertEquals(2, homeRequests.get());
        });
    }

    /// Settings remains bottom-anchored while community and help actions follow it in order.
    @Test
    public void anchorsExternalActionsBelowSettings() {
        AtomicReference<@Nullable URI> openedDestination = new AtomicReference<>();
        EdtDispatcher.executeAndWait(() -> {
            ShellNavigationRail rail = new ShellNavigationRail(
                    ShellPagePresentations.englishFallback(),
                    () -> { },
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
                    () -> { },
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
