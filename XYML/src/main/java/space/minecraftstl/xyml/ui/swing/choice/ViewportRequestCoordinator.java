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
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.OptionalLong;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;

/// Cancels superseded viewport requests and discards their late completions.
///
/// @param <T> the non-null choice value type
@NotNullByDefault
public final class ViewportRequestCoordinator<T extends Object> implements AutoCloseable {
    /// The data source used for all viewport requests.
    private final ViewportChoiceDataSource<T> dataSource;

    /// The listener notified only for the current non-cancelled generation.
    private final ViewportLoadListener<T> listener;

    /// Cancellation signals belonging to the current generation.
    private final List<LoadCancellation> activeCancellations = new ArrayList<>();

    /// The most recently requested plan, or `null` before the first request.
    private @Nullable ViewportLoadPlan currentPlan;

    /// The monotonically increasing request generation.
    private long generation;

    /// Whether this coordinator has been permanently closed.
    private boolean closed;

    /// Creates a request coordinator.
    ///
    /// @param dataSource the indexed choice data source
    /// @param listener the request lifecycle listener
    public ViewportRequestCoordinator(
            ViewportChoiceDataSource<T> dataSource,
            ViewportLoadListener<T> listener) {
        this.dataSource = dataSource;
        this.listener = listener;
    }

    /// Requests the ranges needed by a viewport plan when its demand changed.
    ///
    /// @param plan the latest viewport plan
    /// @return the active request generation
    public synchronized long request(ViewportLoadPlan plan) {
        return request(plan, false);
    }

    /// Retries the current plan even when its demanded indexes have not changed.
    ///
    /// @return the new request generation, or the current generation when no plan exists
    public synchronized long retry() {
        if (currentPlan == null) {
            return generation;
        }
        return request(currentPlan, true);
    }

    /// Returns whether a generation is still current and eligible to update the model.
    ///
    /// @param candidateGeneration the generation to test
    /// @return whether results from the generation may still be applied
    public synchronized boolean isCurrent(long candidateGeneration) {
        return !closed && candidateGeneration == generation;
    }

    /// Cancels the active generation and forgets its demand while keeping this coordinator reusable.
    ///
    /// A later [#request(ViewportLoadPlan)] call starts a fresh generation even if its ranges match the
    /// invalidated plan. Late completions from invalidated work are discarded.
    public synchronized void invalidate() {
        if (closed) {
            throw new IllegalStateException("Viewport request coordinator is closed");
        }
        generation++;
        cancelActiveRequests();
        currentPlan = null;
    }

    /// Cancels current requests and prevents all future requests.
    @Override
    public synchronized void close() {
        if (!closed) {
            closed = true;
            generation++;
            cancelActiveRequests();
            currentPlan = null;
        }
    }

    /// Starts a request generation.
    ///
    /// @param plan the latest viewport plan
    /// @param force whether an unchanged plan must be retried
    /// @return the active request generation
    private long request(ViewportLoadPlan plan, boolean force) {
        if (closed) {
            throw new IllegalStateException("Viewport request coordinator is closed");
        }
        if (!force && currentPlan != null && currentPlan.hasSameDemand(plan)) {
            return generation;
        }

        cancelActiveRequests();
        currentPlan = plan;
        long requestGeneration = ++generation;
        OptionalLong requestSourceRevision = dataSource.sourceRevision();
        @Unmodifiable List<IndexRange> ranges = requestedRanges(plan);
        listener.loading(requestGeneration, ranges);

        for (IndexRange range : ranges) {
            LoadCancellation cancellation = new LoadCancellation();
            activeCancellations.add(cancellation);
            long startedAtNanos = System.nanoTime();
            try {
                CompletionStage<ChoicePage<T>> stage = dataSource.load(range, cancellation);
                stage.whenComplete((@Nullable ChoicePage<T> page, @Nullable Throwable failure) ->
                        complete(
                                requestGeneration,
                                requestSourceRevision,
                                range,
                                cancellation,
                                startedAtNanos,
                                page,
                                failure));
            } catch (Throwable failure) {
                complete(
                        requestGeneration,
                        requestSourceRevision,
                        range,
                        cancellation,
                        startedAtNanos,
                        null,
                        failure);
            }
        }
        return requestGeneration;
    }

    /// Accepts one asynchronous request completion if its generation remains current.
    ///
    /// @param requestGeneration the generation that issued the request
    /// @param requestSourceRevision source revision captured before issuing the generation
    /// @param requestedRange the range originally passed to the source
    /// @param cancellation the request cancellation signal
    /// @param startedAtNanos the monotonic request start time
    /// @param page the returned page, or `null` for failure
    /// @param failure the completion failure, or `null` for success
    private synchronized void complete(
            long requestGeneration,
            OptionalLong requestSourceRevision,
            IndexRange requestedRange,
            LoadCancellation cancellation,
            long startedAtNanos,
            @Nullable ChoicePage<T> page,
            @Nullable Throwable failure) {
        if (!isCurrent(requestGeneration)
                || cancellation.isCancelled()
                || !requestSourceRevision.equals(dataSource.sourceRevision())) {
            return;
        }

        Duration latency = Duration.ofNanos(Math.max(0L, System.nanoTime() - startedAtNanos));
        listener.latencyObserved(latency);
        if (failure != null) {
            listener.failed(requestGeneration, requestedRange, unwrap(failure));
        } else if (page == null) {
            listener.failed(requestGeneration, requestedRange,
                    new NullPointerException("Choice data source completed with a null page"));
        } else if (!covers(page, requestedRange)) {
            listener.failed(requestGeneration, requestedRange,
                    new IllegalArgumentException("Choice page does not cover the requested range"));
        } else {
            listener.loaded(requestGeneration, requestedRange, page);
        }
    }

    /// Requests the main desired range and any pinned indexes outside that range.
    ///
    /// Adjacent pinned indexes are merged, but no arbitrary page size is introduced.
    ///
    /// @param plan the current viewport plan
    /// @return immutable sorted, non-overlapping request ranges
    private static @Unmodifiable List<IndexRange> requestedRanges(ViewportLoadPlan plan) {
        List<IndexRange> candidates = new ArrayList<>();
        if (!plan.desiredRange().isEmpty()) {
            candidates.add(plan.desiredRange());
        }
        for (int index : plan.pinnedIndices()) {
            if (!plan.desiredRange().contains(index) && index < Integer.MAX_VALUE) {
                candidates.add(IndexRange.ofLength(index, 1));
            }
        }
        candidates.sort(Comparator.comparingInt(IndexRange::startInclusive));

        List<IndexRange> merged = new ArrayList<>();
        for (IndexRange candidate : candidates) {
            if (merged.isEmpty()) {
                merged.add(candidate);
                continue;
            }
            int lastIndex = merged.size() - 1;
            IndexRange last = merged.get(lastIndex);
            if (candidate.startInclusive() <= last.endExclusive()) {
                merged.set(lastIndex, new IndexRange(
                        last.startInclusive(),
                        Math.max(last.endExclusive(), candidate.endExclusive())));
            } else {
                merged.add(candidate);
            }
        }
        return List.copyOf(merged);
    }

    /// Returns whether an actual source range covers the range requested by the viewport.
    ///
    /// A final page from a previously unbounded source may be shorter than requested; its exact
    /// item count then constrains the requested range before coverage is checked.
    ///
    /// @param page the source-aligned result page
    /// @param requested the viewport request range
    /// @return whether every requested index is represented by the source result
    private static boolean covers(ChoicePage<?> page, IndexRange requested) {
        IndexRange effectiveRequest = page.exactItemCount().isPresent()
                ? requested.clampToItemCount(page.exactItemCount().getAsInt())
                : requested;
        IndexRange actual = page.range();
        return actual.startInclusive() <= requested.startInclusive()
                && actual.endExclusive() >= effectiveRequest.endExclusive();
    }

    /// Unwraps asynchronous completion wrappers.
    ///
    /// @param failure the reported asynchronous failure
    /// @return the underlying cause when available
    private static Throwable unwrap(Throwable failure) {
        if (failure instanceof CompletionException) {
            @Nullable Throwable cause = failure.getCause();
            if (cause != null) {
                return cause;
            }
        }
        return failure;
    }

    /// Cancels all active cooperative request signals.
    private void cancelActiveRequests() {
        for (LoadCancellation cancellation : activeCancellations) {
            cancellation.cancel();
        }
        activeCancellations.clear();
    }
}
