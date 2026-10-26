// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.home;

import org.jetbrains.annotations.NotNullByDefault;
import space.minecraftstl.xyml.game.launch.LaunchRequest;
import space.minecraftstl.xyml.game.launch.LaunchSession;

/// Starts one launch session from the immutable identities captured by the home model.
@FunctionalInterface
@NotNullByDefault
public interface HomeLaunchCommand {
    /// Starts preparing the supplied captured request.
    ///
    /// @param request stable account, game-directory, and instance identifiers captured at command time
    /// @return the session representing this exact launch request
    LaunchSession launch(LaunchRequest request);
}
