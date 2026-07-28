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

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/// Projects an identified source into a caller-selected stable-ID order.
///
/// Only IDs are reordered eagerly. Visible row details are still requested individually through
/// [IdentifiedChoiceDataSource#loadItem(String, LoadCancellation)], so moving a recently used item from the end of a
/// large source to the top never causes the intervening rows to load.
///
/// @param <T> non-null loaded row type
@NotNullByDefault
public final class OrderedChoiceDataSource<T extends Object> implements ViewportChoiceDataSource<T> {
    /// Underlying current content and single-row loader.
    private final IdentifiedChoiceDataSource<T> source;

    /// Immutable stable IDs in current display order.
    private volatile @Unmodifiable List<String> orderedIds = List.of();

    /// Monotonic revision changed whenever display identity or order changes.
    private volatile long revision;

    /// Creates an initially source-ordered projection.
    ///
    /// @param source identified row source
    public OrderedChoiceDataSource(IdentifiedChoiceDataSource<T> source) {
        this.source = Objects.requireNonNull(source, "source");
        orderedIds = List.copyOf(source.stableItemIds());
    }

    /// Replaces the exact display order and invalidates late viewport completions when needed.
    ///
    /// @param replacement stable IDs in desired display order
    /// @return whether the display order changed
    public boolean setOrder(@Unmodifiable List<String> replacement) {
        @Unmodifiable List<String> copy = List.copyOf(Objects.requireNonNull(replacement, "replacement"));
        if (orderedIds.equals(copy)) {
            return false;
        }
        orderedIds = copy;
        revision = Math.addExact(revision, 1L);
        return true;
    }

    /// Returns the current display IDs for selection-index translation.
    ///
    /// @return immutable ordered stable IDs
    public @Unmodifiable List<String> orderedIds() {
        return orderedIds;
    }

    /// Finds one stable identifier in current display order.
    ///
    /// @param stableId stable identifier to locate
    /// @return display index, or empty when absent
    public OptionalInt displayIndexOf(String stableId) {
        int index = orderedIds.indexOf(Objects.requireNonNull(stableId, "stableId"));
        return index < 0 ? OptionalInt.empty() : OptionalInt.of(index);
    }

    /// Returns the exact projected row count.
    @Override
    public OptionalInt exactItemCount() {
        return OptionalInt.of(orderedIds.size());
    }

    /// Returns the projection revision used to discard completions from an earlier ordering.
    @Override
    public OptionalLong sourceRevision() {
        return OptionalLong.of(revision);
    }

    /// Loads only IDs intersecting the requested projected range.
    @Override
    public CompletionStage<ChoicePage<T>> load(
            IndexRange desiredRange,
            LoadCancellation cancellation) {
        Objects.requireNonNull(desiredRange, "desiredRange");
        Objects.requireNonNull(cancellation, "cancellation");
        @Unmodifiable List<String> requestOrder = orderedIds;
        IndexRange actualRange = desiredRange.clampToItemCount(requestOrder.size());
        List<CompletableFuture<T>> requests = new ArrayList<>(actualRange.length());
        for (int index = actualRange.startInclusive(); index < actualRange.endExclusive(); index++) {
            cancellation.throwIfCancelled();
            String stableId = requestOrder.get(index);
            requests.add(source.loadItem(stableId, cancellation).toCompletableFuture());
        }
        CompletableFuture<?>[] completions = requests.toArray(CompletableFuture[]::new);
        return CompletableFuture.allOf(completions).thenApply(ignored -> {
            cancellation.throwIfCancelled();
            List<T> items = new ArrayList<>(requests.size());
            for (CompletableFuture<T> request : requests) {
                items.add(Objects.requireNonNull(request.join(), "identified source returned null row"));
            }
            return new ChoicePage<>(
                    actualRange,
                    List.copyOf(items),
                    OptionalInt.of(requestOrder.size()),
                    actualRange.endExclusive() == requestOrder.size());
        });
    }
}
