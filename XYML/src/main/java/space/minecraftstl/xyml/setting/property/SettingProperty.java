// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.setting.property;

import space.minecraftstl.xyml.observable.property.ObjectProperty;
import space.minecraftstl.xyml.util.gson.RawPreservingProperty;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.UnknownNullability;

/// A toolkit-neutral serialized game setting property with a fallback value and lossless raw JSON retention.
///
/// @author Glavo
@NotNullByDefault
public interface SettingProperty<T extends @UnknownNullability Object>
        extends ObjectProperty<T>, RawPreservingProperty<T> {
    /// Returns the fallback used when the stored direct value is null.
    T defaultValue();
}
