// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.settings;

import org.jetbrains.annotations.NotNullByDefault;
import space.minecraftstl.xyml.game.GameJavaVersion;

import java.util.Objects;

/// Describes one current-platform Mojang Java runtime choice and its asynchronously loaded local state.
///
/// @param version Mojang game-runtime component and major Java version
/// @param installed whether a manifest or installation directory already exists locally
@NotNullByDefault
public record MojangJavaRuntimeOption(GameJavaVersion version, boolean installed) {
    /// Rejects an absent Mojang runtime version.
    public MojangJavaRuntimeOption {
        version = Objects.requireNonNull(version, "version");
    }
}
