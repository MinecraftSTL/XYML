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
import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/// Tests loading, failure, retry, and loaded states in the sparse Swing model.
@NotNullByDefault
public final class ViewportChoiceListModelTest {
    /// Verifies that asynchronous results transition placeholders without leaving the EDT.
    @Test
    public void exposesLoadingErrorAndLoadedStates() throws Exception {
        ControlledDataSource dataSource = new ControlledDataSource();
        AtomicReference<@Nullable ViewportChoiceListModel<String>> modelReference = new AtomicReference<>();
        ViewportLoadPlan plan = new ViewportLoadPlan(
                new IndexRange(0, 3),
                new IndexRange(0, 3),
                Set.of(),
                ScrollDirection.STATIONARY,
                0.0,
                0);

        SwingUtilities.invokeAndWait(() -> {
            ViewportChoiceListModel<String> model = new ViewportChoiceListModel<>(dataSource);
            modelReference.set(model);
            model.applyPlan(plan);
            assertEquals(ChoiceLoadStatus.LOADING, model.getElementAt(1).status());
        });

        dataSource.requests.get(0).completeExceptionally(new IOException("unavailable"));
        drainEventDispatchThread();
        SwingUtilities.invokeAndWait(() -> {
            ViewportChoiceListModel<String> model = Objects.requireNonNull(modelReference.get());
            assertEquals(ChoiceLoadStatus.ERROR, model.getElementAt(1).status());
            model.retry();
            assertEquals(ChoiceLoadStatus.LOADING, model.getElementAt(1).status());
        });

        dataSource.requests.get(1).complete(new ChoicePage<>(
                new IndexRange(0, 3),
                List.of("zero", "one", "two"),
                OptionalInt.of(10),
                false));
        drainEventDispatchThread();
        SwingUtilities.invokeAndWait(() -> {
            ViewportChoiceListModel<String> model = Objects.requireNonNull(modelReference.get());
            assertEquals(ChoiceLoadStatus.LOADED, model.getElementAt(1).status());
            assertEquals("one", model.getElementAt(1).value());
            model.close();
        });
    }

    /// Verifies that invalidation accepts a changed exact source count and issues fresh work for the next plan.
    @Test
    public void reloadsAfterExactItemCountChanges() throws Exception {
        ResizableDataSource dataSource = new ResizableDataSource(3);
        AtomicReference<@Nullable ViewportChoiceListModel<String>> modelReference = new AtomicReference<>();
        ViewportLoadPlan initialPlan = new ViewportLoadPlan(
                new IndexRange(0, 3), new IndexRange(0, 3), Set.of(),
                ScrollDirection.STATIONARY, 0.0, 0);

        SwingUtilities.invokeAndWait(() -> {
            ViewportChoiceListModel<String> model = new ViewportChoiceListModel<>(dataSource);
            modelReference.set(model);
            model.applyPlan(initialPlan);
            assertEquals(3, model.getSize());

            dataSource.setItemCount(5);
            model.invalidateData();
            assertEquals(5, model.getSize());
            model.applyPlan(new ViewportLoadPlan(
                    new IndexRange(0, 5), new IndexRange(0, 5), Set.of(),
                    ScrollDirection.STATIONARY, 0.0, 0));
            assertEquals(2, dataSource.requestCount());
            model.close();
        });
    }

    /// Verifies that a short reversal reuses warm rows while a distant jump releases old values.
    @Test
    public void retainsWarmRowsAcrossShortDirectionReversalAndEvictsDistantData() throws Exception {
        ResizableDataSource dataSource = new ResizableDataSource(100);

        SwingUtilities.invokeAndWait(() -> {
            ViewportChoiceListModel<String> model = new ViewportChoiceListModel<>(dataSource);
            model.applyPlan(new ViewportLoadPlan(
                    new IndexRange(20, 25), new IndexRange(15, 30), Set.of(),
                    ScrollDirection.STATIONARY, 0.0, 0));
            assertEquals(1, dataSource.requestCount());
            assertEquals("15", model.loadedValueAt(15));

            model.applyPlan(new ViewportLoadPlan(
                    new IndexRange(21, 26), new IndexRange(16, 31), Set.of(),
                    ScrollDirection.DOWN, 5.0, 1));
            assertEquals(2, dataSource.requestCount());
            assertEquals("30", model.loadedValueAt(30));

            model.applyPlan(new ViewportLoadPlan(
                    new IndexRange(20, 25), new IndexRange(15, 30), Set.of(),
                    ScrollDirection.UP, 5.0, 1));
            assertEquals(2, dataSource.requestCount());

            model.applyPlan(new ViewportLoadPlan(
                    new IndexRange(70, 75), new IndexRange(65, 80), Set.of(),
                    ScrollDirection.DOWN, 20.0, 5));
            assertEquals(3, dataSource.requestCount());
            assertNull(model.loadedValueAt(15));
            assertEquals("70", model.loadedValueAt(70));
            model.close();
        });
    }

    /// Verifies that wide source-aligned pages cannot escape adaptive retention boundaries.
    @Test
    public void filtersSourceAlignedPagesToAdaptiveRetentionBoundary() throws Exception {
        WholeSourcePageDataSource dataSource = new WholeSourcePageDataSource(100);

        SwingUtilities.invokeAndWait(() -> {
            ViewportChoiceListModel<String> model = new ViewportChoiceListModel<>(dataSource);
            model.applyPlan(new ViewportLoadPlan(
                    new IndexRange(20, 25), new IndexRange(15, 30), Set.of(),
                    ScrollDirection.STATIONARY, 0.0, 0));

            assertNull(model.loadedValueAt(9));
            assertEquals("10", model.loadedValueAt(10));
            assertEquals("34", model.loadedValueAt(34));
            assertNull(model.loadedValueAt(35));
            model.close();
        });
    }

    /// Waits until callbacks already queued on the Swing EDT have completed.
    private static void drainEventDispatchThread() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
        });
    }

    /// A source whose request futures are completed explicitly by the test.
    @NotNullByDefault
    private static final class ControlledDataSource implements ViewportChoiceDataSource<String> {
        /// Request futures captured in source invocation order.
        private final List<CompletableFuture<ChoicePage<String>>> requests = new ArrayList<>();

        /// Returns the stable exact test source size.
        ///
        /// @return ten logical rows
        @Override
        public OptionalInt exactItemCount() {
            return OptionalInt.of(10);
        }

        /// Captures a request future for explicit test completion.
        ///
        /// @param desiredRange the requested range
        /// @param cancellation the cooperative cancellation signal
        /// @return a test-controlled future
        @Override
        public CompletionStage<ChoicePage<String>> load(
                IndexRange desiredRange,
                LoadCancellation cancellation) {
            CompletableFuture<ChoicePage<String>> future = new CompletableFuture<>();
            requests.add(future);
            return future;
        }
    }

    /// Bounded immediate source whose exact item count can change between invalidated generations.
    @NotNullByDefault
    private static final class ResizableDataSource implements ViewportChoiceDataSource<String> {
        /// Current exact source size.
        private final AtomicInteger itemCount;

        /// Number of viewport requests received.
        private final AtomicInteger requests = new AtomicInteger();

        /// Creates a source with an initial exact size.
        ///
        /// @param initialItemCount initial item count
        private ResizableDataSource(int initialItemCount) {
            itemCount = new AtomicInteger(initialItemCount);
        }

        /// Returns the current exact source size.
        @Override
        public OptionalInt exactItemCount() {
            return OptionalInt.of(itemCount.get());
        }

        /// Returns an immediate page covering the requested range.
        @Override
        public CompletionStage<ChoicePage<String>> load(
                IndexRange desiredRange,
                LoadCancellation cancellation) {
            requests.incrementAndGet();
            List<String> values = new ArrayList<>();
            for (int index = desiredRange.startInclusive(); index < desiredRange.endExclusive(); index++) {
                values.add(Integer.toString(index));
            }
            return CompletableFuture.completedFuture(new ChoicePage<>(
                    desiredRange,
                    values,
                    OptionalInt.of(itemCount.get()),
                    desiredRange.endExclusive() == itemCount.get()));
        }

        /// Replaces the exact source size.
        ///
        /// @param newItemCount new exact size
        private void setItemCount(int newItemCount) {
            itemCount.set(newItemCount);
        }

        /// Returns how many load requests were received.
        ///
        /// @return request count
        private int requestCount() {
            return requests.get();
        }
    }

    /// Immediate source that always returns one page aligned to the entire source.
    @NotNullByDefault
    private static final class WholeSourcePageDataSource implements ViewportChoiceDataSource<String> {
        /// Exact source size used to construct every aligned page.
        private final int itemCount;

        /// Creates a source with a stable exact size.
        ///
        /// @param itemCount exact source size
        private WholeSourcePageDataSource(int itemCount) {
            this.itemCount = itemCount;
        }

        /// Returns the stable exact source size.
        ///
        /// @return exact source size
        @Override
        public OptionalInt exactItemCount() {
            return OptionalInt.of(itemCount);
        }

        /// Returns the complete source while preserving its native alignment.
        ///
        /// @param desiredRange the requested adaptive range
        /// @param cancellation the cooperative cancellation signal
        /// @return an immediately completed whole-source page
        @Override
        public CompletionStage<ChoicePage<String>> load(
                IndexRange desiredRange,
                LoadCancellation cancellation) {
            List<String> values = new ArrayList<>();
            for (int index = 0; index < itemCount; index++) {
                values.add(Integer.toString(index));
            }
            return CompletableFuture.completedFuture(new ChoicePage<>(
                    new IndexRange(0, itemCount),
                    values,
                    OptionalInt.of(itemCount),
                    true));
        }
    }
}
