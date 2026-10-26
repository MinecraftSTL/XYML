// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.game.install;

import org.jetbrains.annotations.NotNullByDefault;
import space.minecraftstl.xyml.task.Task;

/// Creates a stopped task chain for one immutable vanilla installation request.
@FunctionalInterface
@NotNullByDefault
public interface GameInstallTaskFactory {
    /// Validates the destination and creates the not-yet-started installation task.
    ///
    /// @param request captured instance name and game version
    /// @return stopped task whose success means repository post-processing also completed
    Task<?> create(GameInstallRequest request);
}
