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
package space.minecraftstl.xyml.ui.swing.page.accounts;

import org.jetbrains.annotations.NotNullByDefault;

/// Body posture rendered by the software skin preview.
@NotNullByDefault
enum SkinPreviewPosture {
    /// Ordinary standing posture.
    STANDING,

    /// Crouched posture with a forward torso lean.
    SNEAKING,

    /// Seated posture with arms and legs extended forward.
    RIDING,

    /// Minecraft 1.21 swimming pose, also used by block-constrained crawling.
    SWIMMING,

    /// Side-lying sleeping pose.
    SLEEPING,

    /// Minecraft 1.21 elytra fall-flying pose.
    FALL_FLYING
}
