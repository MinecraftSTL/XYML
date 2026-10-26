// SPDX-FileCopyrightText: Copyright (C) 2025 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.util.gson;

import com.google.gson.JsonElement;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import space.minecraftstl.xyml.observable.property.SimpleObjectProperty;

import java.util.Objects;

/// Toolkit-neutral object property that preserves unsupported JSON until its typed value actually changes.
///
/// @author Glavo
/// @see ObservableSetting
@NotNullByDefault
public class RawPreservingObjectProperty<T> extends SimpleObjectProperty<T> implements RawPreservingProperty<T> {
    /// Unsupported JSON retained for lossless serialization, or null after a real value change.
    private @Nullable JsonElement rawJson;

    /// Creates an unnamed, ownerless property with a null value.
    public RawPreservingObjectProperty() {
    }

    /// Creates an unnamed, ownerless property with the supplied initial value.
    public RawPreservingObjectProperty(@Nullable T initialValue) {
        super(initialValue);
    }

    /// Creates a named property with an owner and a null value.
    public RawPreservingObjectProperty(@Nullable Object bean, String name) {
        super(bean, name);
    }

    /// Creates a named property with an owner and the supplied initial value.
    public RawPreservingObjectProperty(@Nullable Object bean, String name, @Nullable T initialValue) {
        super(bean, name, initialValue);
    }

    /// Replaces or clears the retained unsupported JSON.
    @Override
    public void setRawJson(@Nullable JsonElement value) {
        this.rawJson = value;
    }

    /// Returns the retained unsupported JSON, or null when none is retained.
    @Override
    public @Nullable JsonElement getRawJson() {
        return rawJson;
    }

    /// Clears retained JSON only when the typed value changed according to logical equality.
    @Override
    protected void valueChanged(@Nullable T previousValue, @Nullable T currentValue) {
        if (!Objects.equals(previousValue, currentValue)) {
            rawJson = null;
        }
    }
}
