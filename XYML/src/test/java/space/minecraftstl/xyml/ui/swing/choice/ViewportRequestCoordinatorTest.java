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
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests cancellation and generation filtering for viewport source requests.
@NotNullByDefault
public final class ViewportRequestCoordinatorTest {
    /// Verifies that a changed viewport cancels old work and ignores its late completion.
    @Test
    public void cancelsAndDiscardsSupersededRequest() {
        ControlledDataSource dataSource = new ControlledDataSource(OptionalInt.empty());
        RecordingListener listener = new RecordingListener();
        ViewportRequestCoordinator<String> coordinator = new ViewportRequestCoordinator<>(dataSource, listener);

        long firstGeneration = coordinator.request(plan(new IndexRange(0, 3)));
        long secondGeneration = coordinator.request(plan(new IndexRange(20, 23)));

        assertTrue(dataSource.requests.get(0).cancellation().isCancelled());
        assertFalse(dataSource.requests.get(1).cancellation().isCancelled());
        assertTrue(secondGeneration > firstGeneration);

        dataSource.requests.get(0).future().complete(page(0, "old-0", "old-1", "old-2"));
        assertEquals(List.of(), listener.loadedGenerations);

        dataSource.requests.get(1).future().complete(page(20, "new-20", "new-21", "new-22"));
        assertEquals(List.of(secondGeneration), listener.loadedGenerations);
        assertEquals(List.of(new IndexRange(20, 23)), listener.loadedRanges);
    }

    /// Verifies that isolated selected and focused indexes become exact singleton requests.
    @Test
    public void requestsPinnedIndexesOutsideViewport() {
        ControlledDataSource dataSource = new ControlledDataSource();
        RecordingListener listener = new RecordingListener();
        ViewportRequestCoordinator<String> coordinator = new ViewportRequestCoordinator<>(dataSource, listener);
        IndexRange visibleRange = new IndexRange(10, 13);
        ViewportLoadPlan plan = new ViewportLoadPlan(
                visibleRange,
                visibleRange,
                Set.of(2, 11, 50),
                ScrollDirection.STATIONARY,
                0.0,
                0);

        coordinator.request(plan);

        assertEquals(
                List.of(new IndexRange(2, 3), new IndexRange(10, 13), new IndexRange(50, 51)),
                dataSource.requests.stream().map(ControlledRequest::range).toList());
    }

    /// Verifies that a final short page validly closes a previously unknown source boundary.
    @Test
    public void acceptsShortFinalPageAtDiscoveredBoundary() {
        ControlledDataSource dataSource = new ControlledDataSource(OptionalInt.empty());
        RecordingListener listener = new RecordingListener();
        ViewportRequestCoordinator<String> coordinator = new ViewportRequestCoordinator<>(dataSource, listener);
        long generation = coordinator.request(plan(new IndexRange(0, 5)));

        dataSource.requests.get(0).future().complete(new ChoicePage<>(
                new IndexRange(0, 3),
                List.of("zero", "one", "two"),
                OptionalInt.of(3),
                true));

        assertEquals(List.of(generation), listener.loadedGenerations);
        assertEquals(List.of(new IndexRange(0, 5)), listener.loadedRanges);
    }

    /// Verifies that a source may align a result to its native page boundaries.
    @Test
    public void acceptsSourceAlignedPageWiderThanViewportRequest() {
        ControlledDataSource dataSource = new ControlledDataSource();
        RecordingListener listener = new RecordingListener();
        ViewportRequestCoordinator<String> coordinator = new ViewportRequestCoordinator<>(dataSource, listener);
        long generation = coordinator.request(plan(new IndexRange(10, 13)));

        dataSource.requests.get(0).future().complete(new ChoicePage<>(
                new IndexRange(8, 16),
                List.of("8", "9", "10", "11", "12", "13", "14", "15"),
                OptionalInt.of(100),
                false));

        assertEquals(List.of(generation), listener.loadedGenerations);
        assertEquals(List.of(new IndexRange(10, 13)), listener.loadedRanges);
    }

    /// Verifies that explicit invalidation cancels current work and forces equal future demand into a new generation.
    @Test
    public void invalidatesEqualDemandAndDiscardsLateCompletion() {
        ControlledDataSource dataSource = new ControlledDataSource();
        RecordingListener listener = new RecordingListener();
        ViewportRequestCoordinator<String> coordinator = new ViewportRequestCoordinator<>(dataSource, listener);
        ViewportLoadPlan plan = plan(new IndexRange(4, 7));
        long firstGeneration = coordinator.request(plan);

        coordinator.invalidate();
        long secondGeneration = coordinator.request(plan);

        assertTrue(dataSource.requests.get(0).cancellation().isCancelled());
        assertTrue(secondGeneration > firstGeneration);
        dataSource.requests.get(0).future().complete(page(4, "old-4", "old-5", "old-6"));
        assertEquals(List.of(), listener.loadedGenerations);

        dataSource.requests.get(1).future().complete(page(4, "new-4", "new-5", "new-6"));
        assertEquals(List.of(secondGeneration), listener.loadedGenerations);
    }

    /// Verifies a mutable source revision rejects late completion before coordinator invalidation.
    @Test
    public void discardsCompletionAfterSourceRevisionChanges() {
        ControlledDataSource dataSource = new ControlledDataSource();
        dataSource.setSourceRevision(7L);
        RecordingListener listener = new RecordingListener();
        ViewportRequestCoordinator<String> coordinator = new ViewportRequestCoordinator<>(
                dataSource,
                listener);
        coordinator.request(plan(new IndexRange(0, 3)));

        dataSource.setSourceRevision(8L);
        dataSource.requests.get(0).future().complete(page(0, "old-0", "old-1", "old-2"));

        assertEquals(List.of(), listener.loadedGenerations);
    }

    /// Creates a minimal load plan with the requested desired range.
    ///
    /// @param range the visible and desired range
    /// @return the load plan
    private static ViewportLoadPlan plan(IndexRange range) {
        return new ViewportLoadPlan(range, range, Set.of(), ScrollDirection.STATIONARY, 0.0, 0);
    }

    /// Creates a complete page from string test values.
    ///
    /// @param startInclusive the first source index
    /// @param values values ordered by source index
    /// @return an immutable choice page
    private static ChoicePage<String> page(int startInclusive, String @Unmodifiable ... values) {
        return new ChoicePage<>(
                IndexRange.ofLength(startInclusive, values.length),
                List.of(values),
                OptionalInt.of(100),
                false);
    }

    /// A request captured by the controlled data source.
    ///
    /// @param range the requested range
    /// @param cancellation the cooperative cancellation signal
    /// @param future the future controlled by the test
    @NotNullByDefault
    private record ControlledRequest(
            IndexRange range,
            LoadCancellation cancellation,
            CompletableFuture<ChoicePage<String>> future) {
    }

    /// A data source that exposes each request and completion to the test.
    @NotNullByDefault
    private static final class ControlledDataSource implements ViewportChoiceDataSource<String> {
        /// Requests received in invocation order.
        private final List<ControlledRequest> requests = new ArrayList<>();

        /// The exact source count exposed before loading, when known.
        private final OptionalInt itemCount;

        /// Optional mutable content revision used by late-completion tests.
        private OptionalLong sourceRevision = OptionalLong.empty();

        /// Creates a bounded one-hundred-row test source.
        private ControlledDataSource() {
            this(OptionalInt.of(100));
        }

        /// Creates a test source with the requested initial boundary state.
        ///
        /// @param itemCount the optional exact item count
        private ControlledDataSource(OptionalInt itemCount) {
            this.itemCount = itemCount;
        }

        /// Returns the stable exact test data-source size.
        ///
        /// @return one hundred logical rows
        @Override
        public OptionalInt exactItemCount() {
            return itemCount;
        }

        /// Returns the current optional test revision.
        ///
        /// @return current revision, or empty when revision validation is disabled
        @Override
        public OptionalLong sourceRevision() {
            return sourceRevision;
        }

        /// Enables or advances the test source revision.
        ///
        /// @param revision non-negative test revision
        private void setSourceRevision(long revision) {
            if (revision < 0L) {
                throw new IllegalArgumentException("revision must not be negative");
            }
            sourceRevision = OptionalLong.of(revision);
        }

        /// Captures a controllable source request.
        ///
        /// @param desiredRange the requested viewport range
        /// @param cancellation the cooperative cancellation signal
        /// @return the future controlled by the test
        @Override
        public CompletionStage<ChoicePage<String>> load(
                IndexRange desiredRange,
                LoadCancellation cancellation) {
            CompletableFuture<ChoicePage<String>> future = new CompletableFuture<>();
            requests.add(new ControlledRequest(desiredRange, cancellation, future));
            return future;
        }
    }

    /// A listener that records accepted request completions.
    @NotNullByDefault
    private static final class RecordingListener implements ViewportLoadListener<String> {
        /// Generations whose results were accepted.
        private final List<Long> loadedGenerations = new ArrayList<>();

        /// Requested ranges whose results were accepted.
        private final List<IndexRange> loadedRanges = new ArrayList<>();

        /// Ignores loading notifications for this generation-filtering test.
        ///
        /// @param generation the new generation
        /// @param ranges the requested ranges
        @Override
        public void loading(long generation, @Unmodifiable List<IndexRange> ranges) {
        }

        /// Records one accepted result.
        ///
        /// @param generation the accepted generation
        /// @param requestedRange the requested range
        /// @param page the source page
        @Override
        public void loaded(long generation, IndexRange requestedRange, ChoicePage<String> page) {
            loadedGenerations.add(generation);
            loadedRanges.add(requestedRange);
        }

        /// Fails the test when an unexpected source error is accepted.
        ///
        /// @param generation the request generation
        /// @param requestedRange the failed request range
        /// @param failure the unexpected failure
        @Override
        public void failed(long generation, IndexRange requestedRange, Throwable failure) {
            throw new AssertionError(failure);
        }

        /// Ignores latency values for this generation-filtering test.
        ///
        /// @param latency the measured accepted request latency
        @Override
        public void latencyObserved(Duration latency) {
        }
    }
}
