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
import space.minecraftstl.xyml.library.nbt.edit.NBTAddress;
import space.minecraftstl.xyml.library.nbt.edit.NBTEditException;

import java.util.Objects;

/// Result of one synchronous transactional editor command.
@NotNullByDefault
public record NBTEditResult(
        boolean applied,
        @Nullable NBTAddress selection,
        @Nullable NBTEditException.Reason reason,
        @Nullable String errorMessage) {
    /// Validates the success and failure shapes.
    ///
    /// @param applied whether the operation committed
    /// @param selection preferred selection after a successful model rebuild
    /// @param reason stable library rejection reason, or `null` for a UI-state rejection
    /// @param errorMessage technical detail, or `null` on success
    public NBTEditResult {
        if (applied) {
            Objects.requireNonNull(selection, "selection");
            if (reason != null || errorMessage != null) {
                throw new IllegalArgumentException("A successful edit cannot carry an error");
            }
        } else {
            Objects.requireNonNull(errorMessage, "errorMessage");
            if (selection != null) {
                throw new IllegalArgumentException("A failed edit cannot carry a selection");
            }
        }
    }

    /// Creates a committed result.
    ///
    /// @param selection preferred current address
    /// @return successful result
    static NBTEditResult success(NBTAddress selection) {
        return new NBTEditResult(true, Objects.requireNonNull(selection, "selection"), null, null);
    }

    /// Creates a library-rejected result.
    ///
    /// @param failure checked edit rejection
    /// @return failed result with stable reason
    static NBTEditResult failure(NBTEditException failure) {
        NBTEditException checked = Objects.requireNonNull(failure, "failure");
        return new NBTEditResult(false, null, checked.reason(), checked.getMessage());
    }

    /// Creates a UI-state or parse rejection.
    ///
    /// @param message non-empty technical detail
    /// @return failed result without a library reason
    static NBTEditResult failure(String message) {
        String detail = Objects.requireNonNull(message, "message");
        if (detail.isBlank()) {
            throw new IllegalArgumentException("message must not be blank");
        }
        return new NBTEditResult(false, null, null, detail);
    }
}
