// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

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
