// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.observable.collection;

import org.jetbrains.annotations.NotNullByDefault;

/// Receives one immutable collection change on the thread that performed the mutation.
@FunctionalInterface
@NotNullByDefault
public interface CollectionChangeListener<C> {
    /// Handles one completed collection change.
    void onChange(C change);
}
