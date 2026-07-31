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
import org.jetbrains.annotations.Unmodifiable;

import javax.imageio.ImageIO;
import javax.swing.Icon;
import javax.swing.ImageIcon;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/// Loads the launcher-owned raster icon family for native windows and Swing chrome.
///
/// The same classpath assets are used by the launcher runtime and native package assets so the
/// title bar, taskbar, and in-app brand mark cannot silently diverge.
@NotNullByDefault
public final class LauncherIconImages {
    /// Classpath resource for the current XYML title mark shown inside launcher chrome.
    private static final String HEADER_RESOURCE_PATH = "/assets/img/icon-title.png";

    /// Classpath resource for the official XYML community mark.
    private static final String COMMUNITY_RESOURCE_PATH = "/assets/img/team-icon.png";

    /// Stable size used by compact raster icons inside the launcher chrome.
    private static final int CHROME_ICON_SIZE = 24;

    /// Classpath resources ordered from the smallest to the largest raster representation.
    private static final String @Unmodifiable [] RESOURCE_PATHS = {
            "/assets/img/icon.png",
            "/assets/img/icon@2x.png",
            "/assets/img/icon@4x.png",
            "/assets/img/icon@8x.png"
    };

    /// Immutable images decoded once for the native window lifetime.
    private static final @Unmodifiable List<Image> IMAGES = loadImages();

    /// Distinct in-app brand mark, decoded independently from the transparent native icon family.
    private static final @Nullable Icon HEADER_ICON = loadChromeIcon(HEADER_RESOURCE_PATH);

    /// Official community mark decoded and scaled from the bundled raster asset.
    private static final @Nullable Icon COMMUNITY_ICON = loadChromeIcon(COMMUNITY_RESOURCE_PATH);

    /// Prevents utility-class construction.
    private LauncherIconImages() {
    }

    /// Returns every successfully decoded launcher icon for the native window API.
    ///
    /// @return immutable icon family ordered by ascending resolution
    public static @Unmodifiable List<Image> windowIcons() {
        return IMAGES;
    }

    /// Returns the compact raster icon used next to the in-app XYML word mark.
    ///
    /// @return the 24-pixel title icon, or `null` when the launcher resources are unavailable
    public static @Nullable Icon headerIcon() {
        return HEADER_ICON;
    }

    /// Returns the compact bundled mark used by the official-community navigation action.
    ///
    /// @return the 24-pixel community icon, or `null` when the launcher resource is unavailable
    public static @Nullable Icon communityIcon() {
        return COMMUNITY_ICON;
    }

    /// Decodes and scales one bundled chrome icon without making launcher startup depend on it.
    ///
    /// @param resourcePath absolute classpath resource path
    /// @return decoded 24-pixel icon, or `null` when the optional resource is unavailable
    private static @Nullable Icon loadChromeIcon(String resourcePath) {
        try (@Nullable InputStream stream = LauncherIconImages.class.getResourceAsStream(
                Objects.requireNonNull(resourcePath, "resourcePath"))) {
            if (stream == null) {
                return null;
            }
            @Nullable BufferedImage image = ImageIO.read(stream);
            return image == null
                    ? null
                    : new ImageIcon(image.getScaledInstance(
                            CHROME_ICON_SIZE,
                            CHROME_ICON_SIZE,
                            Image.SCALE_SMOOTH));
        } catch (IOException ignored) {
            return null;
        }
    }

    /// Decodes the bundled icon family without making startup fail when one optional resolution is absent.
    ///
    /// @return immutable successfully decoded icon family
    private static @Unmodifiable List<Image> loadImages() {
        List<Image> images = new ArrayList<>(RESOURCE_PATHS.length);
        for (String resourcePath : RESOURCE_PATHS) {
            try (@Nullable InputStream stream = LauncherIconImages.class.getResourceAsStream(resourcePath)) {
                if (stream == null) {
                    continue;
                }
                @Nullable BufferedImage image = ImageIO.read(stream);
                if (image != null) {
                    images.add(image);
                }
            } catch (IOException ignored) {
                // A later resolution can still provide a usable native application icon.
            }
        }
        return List.copyOf(images);
    }
}
