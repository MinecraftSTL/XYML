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
package space.minecraftstl.xyml.ui.swing.page.downloads;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import space.minecraftstl.xyml.addon.RemoteAddon;

import javax.swing.Icon;
import javax.swing.UIManager;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.util.Objects;

import static space.minecraftstl.xyml.util.i18n.I18n.i18n;

/// Provides localized labels and theme-aware status icons for remote add-on release channels.
@NotNullByDefault
final class RemoteVersionChannelPresentation {
    /// Diameter of the colored channel marker in logical pixels.
    private static final int MARKER_SIZE = 10;

    /// Total icon width and height including balanced padding.
    private static final int ICON_SIZE = 14;

    /// Reusable Alpha channel icon that resolves its color during painting.
    private static final Icon ALPHA_ICON = new ChannelIcon(RemoteAddon.VersionType.Alpha);

    /// Reusable Beta channel icon that resolves its color during painting.
    private static final Icon BETA_ICON = new ChannelIcon(RemoteAddon.VersionType.Beta);

    /// Reusable Release channel icon that resolves its color during painting.
    private static final Icon RELEASE_ICON = new ChannelIcon(RemoteAddon.VersionType.Release);

    /// Prevents construction of this presentation utility.
    private RemoteVersionChannelPresentation() {
    }

    /// Resolves the localized label for one version channel.
    ///
    /// @param versionType remote version channel
    /// @return localized Alpha, Beta, or Release label
    static String label(RemoteAddon.VersionType versionType) {
        return switch (Objects.requireNonNull(versionType, "versionType")) {
            case Alpha -> i18n("addon.channel.alpha");
            case Beta -> i18n("addon.channel.beta");
            case Release -> i18n("addon.channel.release");
        };
    }

    /// Returns a reusable icon for one version channel.
    ///
    /// @param versionType remote version channel
    /// @return theme-aware status marker
    static Icon icon(RemoteAddon.VersionType versionType) {
        return switch (Objects.requireNonNull(versionType, "versionType")) {
            case Alpha -> ALPHA_ICON;
            case Beta -> BETA_ICON;
            case Release -> RELEASE_ICON;
        };
    }

    /// Resolves the exact upstream channel color for a light or dark palette.
    ///
    /// @param versionType remote version channel
    /// @param darkTheme whether the active palette is dark
    /// @return opaque channel marker color
    static Color color(RemoteAddon.VersionType versionType, boolean darkTheme) {
        return switch (Objects.requireNonNull(versionType, "versionType")) {
            case Alpha -> new Color(darkTheme ? 0xFF496E : 0xCB2245);
            case Beta -> new Color(darkTheme ? 0xFFA347 : 0xE08325);
            case Release -> new Color(darkTheme ? 0x1BD96A : 0x00AF5C);
        };
    }

    /// Detects whether the current Swing surface uses a dark palette.
    ///
    /// @return whether the active panel background has dark perceived luminance
    private static boolean isDarkTheme() {
        @Nullable Color background = UIManager.getColor("Panel.background");
        if (background == null) {
            return false;
        }
        int luminance = background.getRed() * 299
                + background.getGreen() * 587
                + background.getBlue() * 114;
        return luminance < 128_000;
    }

    /// Paints one release-channel marker with the color of the current Swing palette.
    @NotNullByDefault
    private static final class ChannelIcon implements Icon {
        /// Version channel represented by this immutable icon.
        private final RemoteAddon.VersionType versionType;

        /// Creates a reusable marker for one release channel.
        ///
        /// @param versionType remote version channel
        private ChannelIcon(RemoteAddon.VersionType versionType) {
            this.versionType = Objects.requireNonNull(versionType, "versionType");
        }

        /// {@inheritDoc}
        @Override
        public void paintIcon(@Nullable Component component, Graphics graphics, int x, int y) {
            Graphics2D copy = (Graphics2D) graphics.create();
            try {
                copy.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                copy.setColor(color(versionType, isDarkTheme()));
                int offset = (ICON_SIZE - MARKER_SIZE) / 2;
                copy.fillOval(x + offset, y + offset, MARKER_SIZE, MARKER_SIZE);
            } finally {
                copy.dispose();
            }
        }

        /// {@inheritDoc}
        @Override
        public int getIconWidth() {
            return ICON_SIZE;
        }

        /// {@inheritDoc}
        @Override
        public int getIconHeight() {
            return ICON_SIZE;
        }
    }
}
