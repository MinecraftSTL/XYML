// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.theme;

import org.jetbrains.annotations.NotNullByDefault;

import java.util.Objects;

/// Complete toolkit-neutral input for resolving one selected launcher theme.
///
/// @param selectedTheme persisted requested theme reference
/// @param context current platform, locale, and operating-system brightness
/// @param userOverrides user appearance values applied after theme conditions
@NotNullByDefault
public record ThemeResolutionRequest(
        ThemeReference selectedTheme,
        ThemeResolveContext context,
        ThemeUserAppearanceOverrides userOverrides) {
    /// Validates every resolution input.
    public ThemeResolutionRequest {
        Objects.requireNonNull(selectedTheme, "selectedTheme");
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(userOverrides, "userOverrides");
    }
}
