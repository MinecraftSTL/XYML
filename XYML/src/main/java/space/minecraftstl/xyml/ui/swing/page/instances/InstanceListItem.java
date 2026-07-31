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
package space.minecraftstl.xyml.ui.swing.page.instances;

import org.jetbrains.annotations.NotNullByDefault;
import space.minecraftstl.xyml.image.InstanceIconData;

import java.util.Objects;

/// Immutable presentation data for one installed game instance.
///
/// @param id stable repository instance identifier used by commands
/// @param name user-visible instance name
/// @param detail concise game-version or loader detail
/// @param icon normalized non-null instance icon pixels
@NotNullByDefault
public record InstanceListItem(
        String id,
        String name,
        String detail,
        InstanceIconData icon) {
    /// Validates one instance row.
    public InstanceListItem {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(detail, "detail");
        Objects.requireNonNull(icon, "icon");
        if (id.isBlank()) {
            throw new IllegalArgumentException("Instance id cannot be blank");
        }
        if (name.isBlank()) {
            throw new IllegalArgumentException("Instance name cannot be blank");
        }
    }

    /// Returns the compact text rendered by the reusable viewport-list cell.
    ///
    /// @return instance name followed by detail when available
    public String displayText() {
        return detail.isBlank() ? name : name + " - " + detail;
    }

}
