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
package space.minecraftstl.xyml.ui.swing.page.instances.management;

import org.jetbrains.annotations.NotNullByDefault;

import java.util.Objects;

/// Captures one user-confirmed destination and world-copy choice for duplicating an instance.
///
/// The destination remains raw until the panel validates it, so a cancelled dialog is represented by
/// `null` at the interaction boundary instead of by an invalid request object.
///
/// @param destinationId requested new instance identifier
/// @param copySaves whether the source effective saves directory should be copied
@NotNullByDefault
public record InstanceLifecycleDuplicateRequest(String destinationId, boolean copySaves) {
    /// Rejects a missing destination text while preserving user whitespace for the panel to normalize.
    ///
    /// @param destinationId requested destination identifier
    /// @param copySaves whether worlds should be duplicated
    public InstanceLifecycleDuplicateRequest {
        Objects.requireNonNull(destinationId, "destinationId");
    }
}
