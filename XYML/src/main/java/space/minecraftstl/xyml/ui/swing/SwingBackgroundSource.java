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
package space.minecraftstl.xyml.ui.swing;

import org.jetbrains.annotations.NotNullByDefault;
import space.minecraftstl.xyml.theme.BuiltinBackground;
import space.minecraftstl.xyml.theme.ThemePackResource;

import java.util.Objects;

/// Fully identified background source ready for the Swing image-loading boundary.
@NotNullByDefault
public sealed interface SwingBackgroundSource permits SwingBackgroundSource.DefaultLocal,
        SwingBackgroundSource.Builtin, SwingBackgroundSource.ThemePackImage,
        SwingBackgroundSource.Local, SwingBackgroundSource.Network,
        SwingBackgroundSource.Paint, SwingBackgroundSource.ThemeColorFill {
    /// Searches launcher-local default image files and directories.
    @NotNullByDefault
    record DefaultLocal() implements SwingBackgroundSource {
    }

    /// Selects one packaged historical wallpaper.
    ///
    /// @param background exact bundled wallpaper
    @NotNullByDefault
    record Builtin(BuiltinBackground background) implements SwingBackgroundSource {
        /// Validates one bundled source.
        public Builtin {
            Objects.requireNonNull(background, "background");
        }
    }

    /// Selects an image owned by the effective validated theme package.
    ///
    /// @param resource reopenable validated package resource
    @NotNullByDefault
    record ThemePackImage(ThemePackResource resource) implements SwingBackgroundSource {
        /// Validates one theme-pack image source.
        public ThemePackImage {
            Objects.requireNonNull(resource, "resource");
        }
    }

    /// Selects a user-entered local image file or directory.
    ///
    /// @param path user-entered path, possibly blank and therefore load-invalid
    @NotNullByDefault
    record Local(String path) implements SwingBackgroundSource {
        /// Validates one local source value without touching the filesystem.
        public Local {
            path = Objects.requireNonNull(path, "path").trim();
        }
    }

    /// Selects a user-entered HTTP or HTTPS image.
    ///
    /// @param url user-entered URL, possibly blank and therefore load-invalid
    @NotNullByDefault
    record Network(String url) implements SwingBackgroundSource {
        /// Validates one network source value without performing I/O.
        public Network {
            url = Objects.requireNonNull(url, "url").trim();
        }
    }

    /// Selects a user-entered solid-color or JavaFX-compatible gradient expression.
    ///
    /// @param expression paint expression, possibly blank and therefore parse-invalid
    @NotNullByDefault
    record Paint(String expression) implements SwingBackgroundSource {
        /// Validates one paint source value.
        public Paint {
            expression = Objects.requireNonNull(expression, "expression").trim();
        }
    }

    /// Uses the active FlatLaf theme surface color.
    @NotNullByDefault
    record ThemeColorFill() implements SwingBackgroundSource {
    }
}
