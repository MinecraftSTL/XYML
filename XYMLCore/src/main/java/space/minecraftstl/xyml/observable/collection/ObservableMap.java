// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.observable.collection;

import org.jetbrains.annotations.NotNullByDefault;
import space.minecraftstl.xyml.observable.Subscription;

import java.util.Map;

/// A toolkit-neutral mutable map that preserves key insertion order and emits immutable changes.
///
/// The map owns its storage and rejects null keys and values. It is not thread-safe and must be confined or
/// externally synchronized. Notifications are synchronous on the mutating thread after state has changed.
@NotNullByDefault
public interface ObservableMap<K, V> extends Map<K, V> {
    /// Registers a synchronous listener and returns its independently cancellable subscription.
    Subscription subscribe(CollectionChangeListener<MapChange<K, V>> listener);
}
