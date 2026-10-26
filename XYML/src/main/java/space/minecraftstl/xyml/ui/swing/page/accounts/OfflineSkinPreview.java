// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.accounts;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import space.minecraftstl.xyml.auth.yggdrasil.TextureModel;

import java.awt.image.BufferedImage;
import java.util.Objects;

/// Decoded images owned by one offline skin preview operation.
///
/// @param model arm geometry used by the player texture
/// @param skin decoded player texture
/// @param cape decoded optional cape texture
@NotNullByDefault
record OfflineSkinPreview(
        TextureModel model,
        BufferedImage skin,
        @Nullable BufferedImage cape) {
    /// Validates one successfully decoded preview payload.
    OfflineSkinPreview {
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(skin, "skin");
    }
}
