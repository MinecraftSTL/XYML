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
package space.minecraftstl.xyml.ui.swing.page.nbt;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/// Result of parsing and applying one type-preserving scalar edit.
@NotNullByDefault
public record NBTValueEditResult(boolean applied, @Nullable String errorMessage) {
    /// Validates success and failure invariants.
    ///
    /// @param applied whether the concrete HelloNBT setter ran
    /// @param errorMessage technical validation detail, or `null` on success
    public NBTValueEditResult {
        if (applied && errorMessage != null) {
            throw new IllegalArgumentException("a successful edit cannot have an error message");
        }
        if (!applied) {
            Objects.requireNonNull(errorMessage, "errorMessage");
        }
    }

    /// Creates a successful edit result.
    ///
    /// @return shared-shape successful result
    static NBTValueEditResult success() {
        return new NBTValueEditResult(true, null);
    }

    /// Creates a rejected edit result.
    ///
    /// @param errorMessage non-empty technical reason
    /// @return rejected result
    static NBTValueEditResult failure(String errorMessage) {
        String message = Objects.requireNonNull(errorMessage, "errorMessage");
        if (message.isBlank()) {
            throw new IllegalArgumentException("errorMessage must not be blank");
        }
        return new NBTValueEditResult(false, message);
    }
}
