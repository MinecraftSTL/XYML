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
    /// Pure in-memory fallback used by source adapters that have not loaded an icon yet.
    private static final InstanceIconData DEFAULT_ICON = createDefaultIcon();

    /// Creates a compatible row with a neutral non-blocking fallback icon.
    ///
    /// @param id stable repository instance identifier used by commands
    /// @param name user-visible instance name
    /// @param detail concise game-version or loader detail
    public InstanceListItem(String id, String name, String detail) {
        this(id, name, detail, DEFAULT_ICON);
    }

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

    /// Creates a deterministic neutral icon without resource access or image decoding.
    ///
    /// @return immutable fixed-size placeholder pixels
    private static InstanceIconData createDefaultIcon() {
        int[] pixels = new int[InstanceIconData.PIXEL_COUNT];
        for (int y = 0; y < InstanceIconData.HEIGHT; y++) {
            for (int x = 0; x < InstanceIconData.WIDTH; x++) {
                boolean border = x < 2 || y < 2
                        || x >= InstanceIconData.WIDTH - 2
                        || y >= InstanceIconData.HEIGHT - 2;
                int color = border
                        ? 0xFF66717A
                        : ((x / 5 + y / 5) & 1) == 0 ? 0xFF9AA4AC : 0xFF828D96;
                pixels[y * InstanceIconData.WIDTH + x] = color;
            }
        }
        return new InstanceIconData(pixels);
    }
}
