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
