/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2026 huangyuhui <huanghongxun2008@126.com> and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
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
