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

import javax.swing.AbstractListModel;
import javax.swing.SwingUtilities;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;

/// A sparse Swing list model backed by cancellable viewport-range requests.
///
/// The model retains the current adaptive demand plus one measured viewport of reversal hysteresis
/// on both sides and any explicitly pinned indexes. Source-aligned pages are filtered to that
/// bounded window, so a data source cannot impose an arbitrary cache size. It does not allocate one
/// object per logical row. All public state-changing methods must run on the Swing event dispatch
/// thread.
///
/// @param <T> the non-null choice value type
@NotNullByDefault
public final class ViewportChoiceListModel<T extends Object>
        extends AbstractListModel<ChoiceListEntry<T>>
        implements ViewportLoadListener<T>, AutoCloseable {
    /// Data source whose current boundary is re-read after explicit invalidation.
    private final ViewportChoiceDataSource<T> dataSource;

    /// The coordinator that cancels and rejects superseded requests.
    private final ViewportRequestCoordinator<T> coordinator;

    /// The measured load-latency tracker exposed to the viewport strategy.
    private final LoadLatencyTracker latencyTracker = new LoadLatencyTracker();

    /// Sparse loaded values keyed by stable source index.
    private final Map<Integer, T> loadedValues = new HashMap<>();

    /// Failed ranges belonging to the active generation.
    private final List<FailedRange> failedRanges = new ArrayList<>();

    /// Adaptive demand, reversal-hysteresis, and pinned ranges retained for the current plan.
    private final List<IndexRange> retainedRanges = new ArrayList<>();

    /// The currently applied viewport plan, or `null` before layout supplies one.
    private @Nullable ViewportLoadPlan currentPlan;

    /// The exact item count, or empty while the source end is unknown.
    private OptionalInt exactItemCount;

    /// The logical size exposed to JList.
    private int exposedSize;

    /// The generation currently allowed to update this model.
    private long activeGeneration;

    /// Whether this model has been closed.
    private boolean closed;

    /// Creates a sparse viewport-backed model.
    ///
    /// @param dataSource the indexed choice data source
    public ViewportChoiceListModel(ViewportChoiceDataSource<T> dataSource) {
        this.dataSource = dataSource;
        this.exactItemCount = dataSource.exactItemCount();
        this.exposedSize = exactItemCount.orElse(0);
        this.coordinator = new ViewportRequestCoordinator<>(dataSource, this);
    }

    /// Returns the logical row count currently exposed to Swing.
    ///
    /// @return an exact count for bounded sources, or a viewport-derived probe boundary otherwise
    @Override
    public int getSize() {
        return exposedSize;
    }

    /// Returns the presentation entry for a logical source index.
    ///
    /// @param index the logical source index
    /// @return a loaded, loading, or failed presentation entry
    @Override
    public ChoiceListEntry<T> getElementAt(int index) {
        if (index < 0 || index >= exposedSize) {
            throw new IndexOutOfBoundsException(index);
        }
        @Nullable T value = loadedValues.get(index);
        if (value != null) {
            return ChoiceListEntry.loaded(index, value);
        }
        @Nullable Throwable failure = failureAt(index);
        return failure == null
                ? ChoiceListEntry.loading(index)
                : ChoiceListEntry.failed(index, failure);
    }

    /// Applies a measured viewport plan and requests its changed demand.
    ///
    /// @param plan the latest measured load plan
    public void applyPlan(ViewportLoadPlan plan) {
        requireEventDispatchThread();
        if (closed) {
            throw new IllegalStateException("Viewport choice model is closed");
        }
        currentPlan = plan;
        updateRetainedRanges(plan);
        boolean evicted = evictOutsideRetention();
        updateExposedSize(plan);
        if (evicted) {
            fireAllContentsChanged();
        }
        if (isPlanFullyLoaded(plan)) {
            return;
        }
        activeGeneration = coordinator.request(plan);
    }

    /// Retries failed ranges for the current viewport plan.
    public void retry() {
        requireEventDispatchThread();
        if (closed) {
            throw new IllegalStateException("Viewport choice model is closed");
        }
        @Nullable ViewportLoadPlan plan = currentPlan;
        if (plan == null) {
            return;
        }
        long previousGeneration = activeGeneration;
        long requestedGeneration = coordinator.request(plan);
        activeGeneration = requestedGeneration == previousGeneration
                ? coordinator.retry()
                : requestedGeneration;
    }

    /// Invalidates cached values and re-reads the data-source boundary without inventing a logical size.
    ///
    /// The next viewport measurement issues a fresh request. This operation supports sources whose exact count
    /// changes after installation, deletion, filtering, or an explicit refresh.
    public void invalidateData() {
        requireEventDispatchThread();
        if (closed) {
            throw new IllegalStateException("Viewport choice model is closed");
        }

        coordinator.invalidate();
        activeGeneration++;
        currentPlan = null;
        loadedValues.clear();
        failedRanges.clear();
        retainedRanges.clear();
        exactItemCount = dataSource.exactItemCount();
        updateExposedSize(exactItemCount.orElse(0));
        fireAllContentsChanged();
    }

    /// Returns the observed mean source latency used by future load plans.
    ///
    /// @return the observed latency, or zero before the first accepted completion
    public Duration observedLoadLatency() {
        return latencyTracker.observedLatency();
    }

    /// Returns the exact source count when it has been discovered.
    ///
    /// @return the exact source count, or empty while the source remains unbounded
    public OptionalInt exactItemCount() {
        return exactItemCount;
    }

    /// Returns the successfully loaded value at an index, when available.
    ///
    /// @param index the stable source index
    /// @return the loaded value, or `null` when loading or failed
    public @Nullable T loadedValueAt(int index) {
        return loadedValues.get(index);
    }

    /// Marks rows as loading for a new active generation.
    ///
    /// @param generation the active request generation
    /// @param ranges the requested viewport and pinned ranges
    @Override
    public void loading(long generation, @Unmodifiable List<IndexRange> ranges) {
        requireEventDispatchThread();
        activeGeneration = generation;
        @Nullable ViewportLoadPlan plan = currentPlan;
        if (plan == null) {
            retainedRanges.clear();
            retainedRanges.addAll(ranges);
        } else {
            updateRetainedRanges(plan);
        }
        failedRanges.clear();
        evictOutsideRetention();
        fireAllContentsChanged();
    }

    /// Applies a successful source page if its generation remains active.
    ///
    /// @param generation the generation that issued the request
    /// @param requestedRange the original requested range
    /// @param page the source-aligned result page
    @Override
    public void loaded(long generation, IndexRange requestedRange, ChoicePage<T> page) {
        runOnEventDispatchThread(() -> applyLoaded(generation, requestedRange, page));
    }

    /// Applies a failed source range if its generation remains active.
    ///
    /// @param generation the generation that issued the request
    /// @param requestedRange the original requested range
    /// @param failure the load failure
    @Override
    public void failed(long generation, IndexRange requestedRange, Throwable failure) {
        runOnEventDispatchThread(() -> applyFailure(generation, requestedRange, failure));
    }

    /// Records an accepted source-request latency observation.
    ///
    /// @param latency the measured request latency
    @Override
    public void latencyObserved(Duration latency) {
        latencyTracker.record(latency);
    }

    /// Cancels active requests and prevents future model updates.
    @Override
    public void close() {
        requireEventDispatchThread();
        if (!closed) {
            closed = true;
            activeGeneration++;
            coordinator.close();
            loadedValues.clear();
            failedRanges.clear();
            retainedRanges.clear();
            int previousSize = exposedSize;
            exposedSize = 0;
            if (previousSize > 0) {
                fireIntervalRemoved(this, 0, previousSize - 1);
            }
        }
    }

    /// Applies an accepted page to the sparse model.
    ///
    /// @param generation the generation that issued the request
    /// @param requestedRange the viewport range originally requested
    /// @param page the source-aligned result page
    private void applyLoaded(long generation, IndexRange requestedRange, ChoicePage<T> page) {
        if (closed || generation != activeGeneration || !coordinator.isCurrent(generation)) {
            return;
        }
        mergeExactItemCount(page.exactItemCount());
        @Nullable ViewportLoadPlan plan = currentPlan;
        if (plan != null) {
            updateRetainedRanges(plan);
        }
        evictOutsideRetention();
        for (int offset = 0; offset < page.items().size(); offset++) {
            int index = page.range().startInclusive() + offset;
            if (isRetained(index)) {
                loadedValues.put(index, page.items().get(offset));
            }
        }
        removeFailuresIntersecting(requestedRange);
        if (plan != null) {
            updateExposedSize(plan);
        }
        fireRangeContentsChanged(page.range());
    }

    /// Applies an accepted request failure to its compact range representation.
    ///
    /// @param generation the generation that issued the request
    /// @param requestedRange the range that failed
    /// @param failure the load failure
    private void applyFailure(long generation, IndexRange requestedRange, Throwable failure) {
        if (closed || generation != activeGeneration || !coordinator.isCurrent(generation)) {
            return;
        }
        removeFailuresIntersecting(requestedRange);
        if (intersectsRetainedRange(requestedRange)) {
            failedRanges.add(new FailedRange(requestedRange, failure));
        }
        fireRangeContentsChanged(requestedRange);
    }

    /// Merges an optional exact count discovered by a page.
    ///
    /// @param discoveredCount the count reported by the data source
    private void mergeExactItemCount(OptionalInt discoveredCount) {
        if (discoveredCount.isEmpty()) {
            return;
        }
        int count = discoveredCount.getAsInt();
        if (exactItemCount.isPresent() && exactItemCount.getAsInt() != count) {
            throw new IllegalStateException("Choice data source changed its exact item count");
        }
        exactItemCount = discoveredCount;
        loadedValues.keySet().removeIf(index -> index >= count);
    }

    /// Updates the logical Swing size from exact source bounds or viewport geometry.
    ///
    /// For an unbounded source, one additional viewport of logical rows is exposed beyond the
    /// current demand so scrolling near the discovered boundary can produce the next measured
    /// request. This probe is derived from actual visible rows rather than a page-size constant.
    ///
    /// @param plan the latest viewport plan
    private void updateExposedSize(ViewportLoadPlan plan) {
        int newSize;
        if (exactItemCount.isPresent()) {
            newSize = exactItemCount.getAsInt();
        } else {
            long viewportProbeEnd = (long) plan.desiredRange().endExclusive() + plan.visibleRange().length();
            long pinnedEnd = plan.pinnedIndices().stream()
                    .mapToLong(index -> (long) index + 1L)
                    .max()
                    .orElse(0L);
            newSize = (int) Math.min(Integer.MAX_VALUE, Math.max(viewportProbeEnd, pinnedEnd));
        }
        updateExposedSize(newSize);
    }

    /// Updates the logical size and emits the corresponding Swing interval event.
    ///
    /// @param newSize the new logical size
    private void updateExposedSize(int newSize) {
        int previousSize = exposedSize;
        exposedSize = newSize;
        if (newSize > previousSize) {
            fireIntervalAdded(this, previousSize, newSize - 1);
        } else if (newSize < previousSize) {
            fireIntervalRemoved(this, newSize, previousSize - 1);
        }
    }

    /// Rebuilds bounded retention from an adaptive viewport plan.
    ///
    /// One current visible-range length is kept beyond each side of desired demand. That margin
    /// absorbs a short direction reversal without another source request while still scaling with
    /// actual on-screen capacity. Pinned indexes outside the contiguous envelope remain as compact
    /// singleton ranges.
    ///
    /// @param plan the latest measured load plan
    private void updateRetainedRanges(ViewportLoadPlan plan) {
        retainedRanges.clear();
        int hysteresisRows = plan.visibleRange().length();
        IndexRange desiredRange = plan.desiredRange();
        IndexRange envelope = new IndexRange(
                saturatingSubtract(desiredRange.startInclusive(), hysteresisRows),
                saturatingAdd(desiredRange.endExclusive(), hysteresisRows));
        if (exactItemCount.isPresent()) {
            envelope = envelope.clampToItemCount(exactItemCount.getAsInt());
        }
        if (!envelope.isEmpty()) {
            retainedRanges.add(envelope);
        }
        for (int index : plan.pinnedIndices()) {
            if (isValidRetainedIndex(index) && !envelope.contains(index)) {
                retainedRanges.add(IndexRange.ofLength(index, 1));
            }
        }
    }

    /// Returns whether a requested or failed range intersects any retained range.
    ///
    /// @param candidate the range to compare with current retention
    /// @return whether at least one retained index belongs to the candidate
    private boolean intersectsRetainedRange(IndexRange candidate) {
        for (IndexRange retainedRange : retainedRanges) {
            if (rangesIntersect(candidate, retainedRange)) {
                return true;
            }
        }
        return false;
    }

    /// Removes sparse values and failures that have left the bounded retention window.
    ///
    /// @return whether any cached state was removed
    private boolean evictOutsideRetention() {
        boolean valuesRemoved = loadedValues.keySet().removeIf(index -> !isRetained(index));
        boolean failuresRemoved = failedRanges.removeIf(
                failedRange -> !intersectsRetainedRange(failedRange.range()));
        return valuesRemoved || failuresRemoved;
    }

    /// Returns whether every current demanded index is already cached.
    ///
    /// This suppresses redundant reloads when a short direction reversal remains inside the
    /// hysteresis window. Pinned values are included even when outside contiguous demand.
    ///
    /// @param plan the latest viewport plan
    /// @return whether the source does not need to be contacted for this plan
    private boolean isPlanFullyLoaded(ViewportLoadPlan plan) {
        if (!isRangeFullyLoaded(plan.desiredRange())) {
            return false;
        }
        for (int index : plan.pinnedIndices()) {
            if (isValidRetainedIndex(index) && !loadedValues.containsKey(index)) {
                return false;
            }
        }
        return true;
    }

    /// Returns whether every index in a range has a sparse cached value.
    ///
    /// Iterating the bounded sparse cache avoids work proportional to a potentially large logical
    /// source range while preserving an exact completeness check.
    ///
    /// @param range the demanded range
    /// @return whether every index in the range is loaded
    private boolean isRangeFullyLoaded(IndexRange range) {
        IndexRange effectiveRange = exactItemCount.isPresent()
                ? range.clampToItemCount(exactItemCount.getAsInt())
                : range;
        int loadedCount = 0;
        for (int index : loadedValues.keySet()) {
            if (effectiveRange.contains(index)) {
                loadedCount++;
            }
        }
        return loadedCount == effectiveRange.length();
    }

    /// Returns whether an index can be represented by the current source boundary.
    ///
    /// @param index the candidate pinned index
    /// @return whether a singleton retained range can be created for the index
    private boolean isValidRetainedIndex(int index) {
        return index >= 0
                && index < Integer.MAX_VALUE
                && (exactItemCount.isEmpty() || index < exactItemCount.getAsInt());
    }

    /// Returns whether an index belongs to an adaptive or pinned retained range.
    ///
    /// @param index the stable source index
    /// @return whether the sparse value should be retained
    private boolean isRetained(int index) {
        for (IndexRange range : retainedRanges) {
            if (range.contains(index)) {
                return true;
            }
        }
        return false;
    }

    /// Returns whether two half-open ranges share at least one index.
    ///
    /// @param left the first range
    /// @param right the second range
    /// @return whether the ranges intersect
    private static boolean rangesIntersect(IndexRange left, IndexRange right) {
        return left.startInclusive() < right.endExclusive()
                && right.startInclusive() < left.endExclusive();
    }

    /// Adds non-negative integers without overflowing.
    ///
    /// @param left the first non-negative value
    /// @param right the second non-negative value
    /// @return the sum, limited to the largest integer
    private static int saturatingAdd(int left, int right) {
        long result = (long) left + right;
        return result >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) result;
    }

    /// Subtracts non-negative integers without underflowing below zero.
    ///
    /// @param left the non-negative minuend
    /// @param right the non-negative subtrahend
    /// @return the difference, limited to zero
    private static int saturatingSubtract(int left, int right) {
        return left <= right ? 0 : left - right;
    }

    /// Finds the active failure covering an index.
    ///
    /// @param index the stable source index
    /// @return the failure, or `null` when no failed range covers the index
    private @Nullable Throwable failureAt(int index) {
        for (FailedRange failedRange : failedRanges) {
            if (failedRange.range().contains(index)) {
                return failedRange.failure();
            }
        }
        return null;
    }

    /// Removes failures intersecting a retried or successfully loaded range.
    ///
    /// @param range the replacement request range
    private void removeFailuresIntersecting(IndexRange range) {
        Iterator<FailedRange> iterator = failedRanges.iterator();
        while (iterator.hasNext()) {
            IndexRange failedRange = iterator.next().range();
            if (failedRange.startInclusive() < range.endExclusive()
                    && range.startInclusive() < failedRange.endExclusive()) {
                iterator.remove();
            }
        }
    }

    /// Fires a content change for the visible portion of a source range.
    ///
    /// @param range the changed source range
    private void fireRangeContentsChanged(IndexRange range) {
        int start = Math.min(range.startInclusive(), exposedSize);
        int end = Math.min(range.endExclusive(), exposedSize);
        if (start < end) {
            fireContentsChanged(this, start, end - 1);
        }
    }

    /// Fires a content change covering every exposed logical row.
    private void fireAllContentsChanged() {
        if (exposedSize > 0) {
            fireContentsChanged(this, 0, exposedSize - 1);
        }
    }

    /// Executes a callback immediately on the EDT or schedules it otherwise.
    ///
    /// @param callback the model mutation callback
    private static void runOnEventDispatchThread(Runnable callback) {
        if (SwingUtilities.isEventDispatchThread()) {
            callback.run();
        } else {
            SwingUtilities.invokeLater(callback);
        }
    }

    /// Enforces the Swing single-thread rule for synchronous model mutations.
    private static void requireEventDispatchThread() {
        if (!SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("Viewport choice model must be changed on the Swing event dispatch thread");
        }
    }

    /// A compact failure associated with a requested range.
    ///
    /// @param range the failed source range
    /// @param failure the request failure
    @NotNullByDefault
    private record FailedRange(IndexRange range, Throwable failure) {
    }
}
