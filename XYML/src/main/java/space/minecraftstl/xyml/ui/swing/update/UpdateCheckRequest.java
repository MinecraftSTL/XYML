// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.update;

import org.jetbrains.annotations.NotNullByDefault;
import space.minecraftstl.xyml.upgrade.UpdateChannel;

import java.util.Objects;

/// Immutable identity of one launcher update check.
///
/// Equal requests share one in-flight operation in [SwingUpdateCheckService].
///
/// @param channel release channel to query
@NotNullByDefault
public record UpdateCheckRequest(UpdateChannel channel) {
    /// Validates one immutable update request.
    public UpdateCheckRequest {
        Objects.requireNonNull(channel, "channel");
    }
}
