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
package space.minecraftstl.xyml.ui.swing.page.downloads;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import space.minecraftstl.xyml.game.GameInstanceID;

import java.util.Objects;

/// Captures one programmatic download-center navigation and its optional instance context.
///
/// A request never carries analyzed version or loader metadata: the catalog resolves those from the target
/// instance when it applies the request, so navigation stays free of blocking metadata reads.
///
/// @param target requested download category
/// @param targetInstanceId instance whose context should prefill the category, or `null` for a plain category switch
@NotNullByDefault
public record DownloadPageRequest(
        DownloadPageTarget target,
        @Nullable GameInstanceID targetInstanceId) {
    /// Validates one download-center navigation request.
    public DownloadPageRequest {
        Objects.requireNonNull(target, "target");
    }

    /// Creates a category-only request without an instance context.
    ///
    /// @param target requested download category
    /// @return request carrying no instance context
    public static DownloadPageRequest of(DownloadPageTarget target) {
        return new DownloadPageRequest(target, null);
    }
}
