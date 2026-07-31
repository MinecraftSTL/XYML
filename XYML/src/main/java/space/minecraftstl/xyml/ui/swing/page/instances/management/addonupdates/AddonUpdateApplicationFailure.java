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
package space.minecraftstl.xyml.ui.swing.page.instances.management.addonupdates;

import org.jetbrains.annotations.NotNullByDefault;

import java.util.Objects;

/// One selected add-on update that could not be completed or restored cleanly.
///
/// @param updateItem exact selected update item
/// @param detail non-blank failure summary suitable for a status row or dialog
@NotNullByDefault
public record AddonUpdateApplicationFailure(
        AddonUpdateItem updateItem,
        String detail) {
    /// Validates the exact update reference and user-visible failure detail.
    public AddonUpdateApplicationFailure {
        updateItem = Objects.requireNonNull(updateItem, "updateItem");
        detail = Objects.requireNonNull(detail, "detail");
        if (detail.isBlank()) {
            throw new IllegalArgumentException("detail must not be blank");
        }
    }
}
