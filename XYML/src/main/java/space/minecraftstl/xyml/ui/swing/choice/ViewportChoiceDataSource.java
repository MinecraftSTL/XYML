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
