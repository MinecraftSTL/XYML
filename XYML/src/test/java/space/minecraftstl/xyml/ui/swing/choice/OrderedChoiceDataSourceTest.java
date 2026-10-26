// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.choice;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies stable-ID reordering preserves viewport-only detail loading.
@NotNullByDefault
public final class OrderedChoiceDataSourceTest {
    /// Reordered prefixes load only the exact projected rows requested by the viewport.
    @Test
    public void loadsOnlyRequestedOrderedIds() {
        RecordingSource source = new RecordingSource();
        OrderedChoiceDataSource<String> ordered = new OrderedChoiceDataSource<>(source);
        assertTrue(ordered.setOrder(List.of("C", "A", "B")));

        ChoicePage<String> page = ordered.load(
                new IndexRange(0, 2),
                new LoadCancellation()).toCompletableFuture().join();

        assertEquals(List.of("value-C", "value-A"), page.items());
        assertEquals(List.of("C", "A"), source.loadedIds());
        assertEquals(OptionalInt.of(3), page.exactItemCount());
        assertFalse(page.endOfData());
    }

    /// Reapplying the exact order does not invalidate active viewport work.
    @Test
    public void retainsRevisionForEqualOrder() {
        OrderedChoiceDataSource<String> ordered = new OrderedChoiceDataSource<>(new RecordingSource());
        long initialRevision = ordered.sourceRevision().orElseThrow();

        assertFalse(ordered.setOrder(List.of("A", "B", "C")));
        assertEquals(initialRevision, ordered.sourceRevision().orElseThrow());
    }

    /// Identified fixture recording every detail request.
    @NotNullByDefault
    private static final class RecordingSource implements IdentifiedChoiceDataSource<String> {
        /// Stable source IDs.
        private static final @Unmodifiable List<String> IDS = List.of("A", "B", "C");

        /// Detail IDs requested by the projection.
        private final List<String> loadedIds = new ArrayList<>();

        /// Returns the exact source count.
        @Override
        public OptionalInt exactItemCount() {
            return OptionalInt.of(IDS.size());
        }

        /// Returns immutable stable source IDs.
        @Override
        public @Unmodifiable List<String> stableItemIds() {
            return IDS;
        }

        /// Records and resolves one exact stable ID.
        @Override
        public CompletionStage<String> loadItem(String stableId, LoadCancellation cancellation) {
            cancellation.throwIfCancelled();
            loadedIds.add(stableId);
            return CompletableFuture.completedFuture("value-" + stableId);
        }

        /// Rejects contiguous source loading because this fixture verifies identified loading.
        @Override
        public CompletionStage<ChoicePage<String>> load(
                IndexRange desiredRange,
                LoadCancellation cancellation) {
            throw new AssertionError("Ordered projection should load by stable ID");
        }

        /// Returns immutable requested IDs.
        ///
        /// @return recorded detail requests
        private @Unmodifiable List<String> loadedIds() {
            return List.copyOf(loadedIds);
        }
    }
}
