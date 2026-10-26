// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.setting.property;

import com.google.gson.JsonElement;
import space.minecraftstl.xyml.observable.property.SimpleObjectProperty;
import space.minecraftstl.xyml.setting.GameSettings;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.UnknownNullability;

/// Stores one toolkit-neutral game setting value together with its serialized metadata and raw JSON fallback.
///
/// @author Glavo
@NotNullByDefault
public class SimpleSettingProperty<T extends @UnknownNullability Object> extends SimpleObjectProperty<T>
        implements SettingProperty<T> {
    /// Raw JSON retained when the typed value could not be deserialized, or null when no fallback is needed.
    private @Nullable JsonElement rawJson;

    /// Value used by effective-setting resolution when the direct value is null.
    private final T defaultValue;

    /// Creates a property owned by the supplied game settings object.
    ///
    /// @param bean owning game settings object
    /// @param name stable serialized property name
    /// @param defaultValue direct initial value and null fallback
    public SimpleSettingProperty(GameSettings bean,
                                 String name,
                                 T defaultValue) {
        super(bean, name, defaultValue);
        this.defaultValue = defaultValue;
    }

    /// Returns the fallback used when the direct value is null.
    @Override
    public T defaultValue() {
        return defaultValue;
    }

    /// Returns raw JSON retained after a failed typed deserialization.
    @Override
    public @Nullable JsonElement getRawJson() {
        return rawJson;
    }

    /// Replaces the raw JSON fallback retained for serialization.
    @Override
    public void setRawJson(@Nullable JsonElement rawJson) {
        this.rawJson = rawJson;
    }

    /// Clears preserved raw JSON after a distinct typed value has been committed.
    @Override
    protected void valueChanged(@Nullable T previousValue, @Nullable T currentValue) {
        this.rawJson = null;
    }
}
