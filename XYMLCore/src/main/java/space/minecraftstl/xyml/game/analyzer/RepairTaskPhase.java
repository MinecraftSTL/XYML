// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.game.analyzer;

import org.jetbrains.annotations.NotNullByDefault;

/// Optional fine-grained phase published by a repair task whose user selection occurs during task execution.
@NotNullByDefault
public enum RepairTaskPhase {
    /// The task is performing read-only preparation before it can present a choice.
    PREPARING,

    /// The task is waiting for the user to choose or cancel one prepared option.
    AWAITING_SELECTION,

    /// The task has a confirmed choice and is performing its explicit action.
    RUNNING;

    /// Task-property key whose value is one [RepairTaskPhase].
    public static final String TASK_PROPERTY = "xyml.crash.repair.phase";
}
