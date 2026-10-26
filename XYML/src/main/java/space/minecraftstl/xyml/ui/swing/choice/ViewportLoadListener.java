// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.choice;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import java.time.Duration;
import java.util.List;

/// Receives lifecycle events for the currently requested viewport generation.
///
/// @param <T> the non-null choice value type
@NotNullByDefault
public interface ViewportLoadListener<T extends Object> {
    /// Reports the ranges requested for a new generation.
    ///
    /// @param generation the monotonically increasing request generation
    /// @param ranges the requested ranges, including isolated pinned indexes
    void loading(long generation, @Unmodifiable List<IndexRange> ranges);

    /// Reports a successfully loaded page.
    ///
    /// @param generation the generation that issued the request
    /// @param requestedRange the viewport range originally passed to the source
    /// @param page the page returned by the source
    void loaded(long generation, IndexRange requestedRange, ChoicePage<T> page);

    /// Reports a failed load.
    ///
    /// @param generation the generation that issued the request
    /// @param requestedRange the range that failed
    /// @param failure the unwrapped load failure
    void failed(long generation, IndexRange requestedRange, Throwable failure);

    /// Reports the measured latency of an accepted request.
    ///
    /// @param latency the elapsed time from source invocation to completion
    void latencyObserved(Duration latency);
}
