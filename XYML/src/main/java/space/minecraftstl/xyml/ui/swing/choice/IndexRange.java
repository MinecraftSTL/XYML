// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.choice;

import org.jetbrains.annotations.NotNullByDefault;

/// A half-open range of non-negative list indexes.
///
/// @param startInclusive the first index in the range
/// @param endExclusive the first index after the range
@NotNullByDefault
public record IndexRange(int startInclusive, int endExclusive) {
    /// Validates the range endpoints.
    public IndexRange {
        if (startInclusive < 0) {
            throw new IllegalArgumentException("startInclusive must not be negative");
        }
        if (endExclusive < startInclusive) {
            throw new IllegalArgumentException("endExclusive must not precede startInclusive");
        }
    }

    /// Creates a range with the requested number of indexes.
    ///
    /// @param startInclusive the first index in the range
    /// @param length the number of indexes in the range
    /// @return the resulting half-open range
    public static IndexRange ofLength(int startInclusive, int length) {
        if (length < 0) {
            throw new IllegalArgumentException("length must not be negative");
        }
        return new IndexRange(startInclusive, Math.addExact(startInclusive, length));
    }

    /// Returns the number of indexes in this range.
    ///
    /// @return the range length
    public int length() {
        return endExclusive - startInclusive;
    }

    /// Returns whether this range contains no indexes.
    ///
    /// @return whether the range is empty
    public boolean isEmpty() {
        return startInclusive == endExclusive;
    }

    /// Returns whether the given index belongs to this range.
    ///
    /// @param index the index to test
    /// @return whether the index is contained in the range
    public boolean contains(int index) {
        return index >= startInclusive && index < endExclusive;
    }

    /// Restricts this range to an exact item count.
    ///
    /// @param itemCount the exact number of items in the data source
    /// @return the portion of this range that is valid for the data source
    public IndexRange clampToItemCount(int itemCount) {
        if (itemCount < 0) {
            throw new IllegalArgumentException("itemCount must not be negative");
        }
        int start = Math.min(startInclusive, itemCount);
        return new IndexRange(start, Math.min(endExclusive, itemCount));
    }
}
