// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.choice;

import org.jetbrains.annotations.NotNullByDefault;

import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.concurrent.CompletionStage;

/// Supplies indexed choices to a viewport-driven list without prescribing source pagination.
///
/// @param <T> the non-null choice value type
@NotNullByDefault
public interface ViewportChoiceDataSource<T extends Object> {
    /// Returns the exact item count when it is already known.
    ///
    /// @return the exact item count, or empty for a source whose end has not been discovered
    OptionalInt exactItemCount();

    /// Returns the current source-content revision when late completions require validation.
    ///
    /// A coordinator captures this value when issuing a generation and discards every success or
    /// failure completed after the source revision changes. Sources without mutable generations may
    /// keep the default empty value.
    ///
    /// @return stable current content revision, or empty when revision validation is unnecessary
    default OptionalLong sourceRevision() {
        return OptionalLong.empty();
    }

    /// Loads values needed to cover the desired range.
    ///
    /// An implementation backed by a paginated service may align the request to service page
    /// boundaries and report that actual range in its result. Implementations should observe the
    /// cancellation signal before costly work and before completing externally visible side effects.
    ///
    /// @param desiredRange the viewport range that must be covered when it exists
    /// @param cancellation the cooperative cancellation signal
    /// @return the eventual contiguous load result
    CompletionStage<ChoicePage<T>> load(IndexRange desiredRange, LoadCancellation cancellation);
}
