// SPDX-FileCopyrightText: Copyright (C) 2025 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

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
