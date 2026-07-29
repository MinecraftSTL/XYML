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

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import space.minecraftstl.xyml.Metadata;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Dimension;
import java.net.URI;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies the shell's bottom navigation hierarchy and bundled official-community action.
@NotNullByDefault
public final class ShellNavigationRailTest {
    /// Settings remains bottom-anchored while the independent official-group action follows it.
    @Test
    public void anchorsOfficialGroupActionBelowSettings() {
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
            javax.swing.Icon officialGroupIcon = Objects.requireNonNull(
                    officialGroup.getIcon(),
                    "official group icon");
            String tooltip = Objects.requireNonNull(
                    officialGroup.getToolTipText(),
                    "official group tooltip");
            int settingsY = SwingUtilities.convertPoint(settings, 0, 0, rail).y;
            int officialGroupY = SwingUtilities.convertPoint(officialGroup, 0, 0, rail).y;

            assertAll(
                    () -> assertTrue(settingsY > rail.getHeight() / 2),
                    () -> assertTrue(officialGroupY > settingsY),
                    () -> assertTrue(
                            rail.getHeight() - officialGroupY - officialGroup.getHeight() <= 10),
                    () -> assertEquals("officialGroupButton", officialGroup.getName()),
                    () -> assertNotNull(officialGroupIcon),
                    () -> assertEquals(24, officialGroupIcon.getIconWidth()),
                    () -> assertEquals(24, officialGroupIcon.getIconHeight()),
                    () -> assertFalse(tooltip.isBlank()),
                    () -> assertEquals(
                            tooltip,
                            officialGroup.getAccessibleContext().getAccessibleName()));

            officialGroup.doClick();
            assertEquals(URI.create(Metadata.GROUPS_URL), openedDestination.get());

            rail.disableNavigation();
            assertAll(
                    () -> assertFalse(settings.isEnabled()),
                    () -> assertFalse(officialGroup.isEnabled()));
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
