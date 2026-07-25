/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2025 huangyuhui <huanghongxun2008@126.com> and contributors
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
package space.minecraftstl.xyml.util.gson;

import com.google.gson.JsonElement;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

/// Persistence contract for a property-like value that can retain unsupported JSON without losing user data.
///
/// This interface deliberately has no dependency on a UI toolkit or a specific property API. A settings adapter may
/// store raw JSON after typed deserialization fails and write it back until the implementing value actually changes.
///
/// @author Glavo
@NotNullByDefault
public interface RawPreservingProperty<T> {
    /// Replaces the retained JSON, or clears it when null is supplied.
    void setRawJson(@Nullable JsonElement value);

    /// Returns the retained unsupported JSON, or null when the current typed value should be serialized.
    @Nullable JsonElement getRawJson();
}
