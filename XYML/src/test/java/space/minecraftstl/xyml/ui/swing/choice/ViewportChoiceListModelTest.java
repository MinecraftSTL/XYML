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
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
