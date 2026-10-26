// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.game.install;

import org.jetbrains.annotations.NotNullByDefault;

/// Describes the lifecycle of one game-installation session.
@NotNullByDefault
public enum GameInstallStatus {
    /// The request is creating or starting its task execution chain.
    PREPARING,

    /// The complete installation and repository update finished normally.
    COMPLETED,

    /// Installation stopped because of a non-cancellation failure.
    FAILED,

    /// Installation stopped after cancellation was accepted.
    CANCELLED;

    /// Returns whether no further installation-state transition is possible.
    ///
    /// @return true for every status except [#PREPARING]
    public boolean isTerminal() {
        return this != PREPARING;
    }
}
