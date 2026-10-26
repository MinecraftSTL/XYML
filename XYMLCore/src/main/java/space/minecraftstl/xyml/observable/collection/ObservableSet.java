// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.observable.collection;

import org.jetbrains.annotations.NotNullByDefault;
import space.minecraftstl.xyml.observable.Subscription;

import java.util.Set;

/// A toolkit-neutral mutable set that preserves insertion order and emits immutable changes.
///
/// The set owns its storage and rejects null elements. It is not thread-safe and must be confined or externally
/// synchronized. Notifications are synchronous on the mutating thread after state has changed.
@NotNullByDefault
public interface ObservableSet<E> extends Set<E> {
    /// Registers a synchronous listener and returns its independently cancellable subscription.
    Subscription subscribe(CollectionChangeListener<SetChange<E>> listener);
}
