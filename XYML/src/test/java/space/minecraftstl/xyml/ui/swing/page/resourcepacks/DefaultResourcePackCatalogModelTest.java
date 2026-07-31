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
package space.minecraftstl.xyml.ui.swing.page.resourcepacks;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import space.minecraftstl.xyml.game.GameRepository;
import space.minecraftstl.xyml.ui.swing.choice.ChoicePage;
import space.minecraftstl.xyml.ui.swing.choice.IndexRange;
import space.minecraftstl.xyml.ui.swing.choice.LoadCancellation;
import space.minecraftstl.xyml.ui.swing.choice.ScrollDirection;
import space.minecraftstl.xyml.ui.swing.choice.ViewportLoadListener;
import space.minecraftstl.xyml.ui.swing.choice.ViewportLoadPlan;
import space.minecraftstl.xyml.ui.swing.choice.ViewportRequestCoordinator;

import javax.swing.SwingUtilities;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests two-stage indexing, exact viewport parsing, cancellation ownership, and subscription barriers.
@NotNullByDefault
public final class DefaultResourcePackCatalogModelTest {
    /// Deterministic localized text used by every model fixture.
    private static final ResourcePackCatalogStatusStrings STATUS_STRINGS =
            new ResourcePackCatalogStatusStrings(
                    "idle",
                    "loading",
                    "ready",
                    "empty",
                    "unsupported",
                    "failed",
                    "unknown",
                    "writing",
                    "write failed");

    /// Maximum time allotted to one concurrency checkpoint.
    private static final long CONCURRENCY_TIMEOUT_SECONDS = 5L;

    /// Temporary directory used by the production-access isolation test.
    @TempDir
    private Path temporaryDirectory;

    /// Verifies initial count is unknown and shallow indexing never resolves row metadata.
    @Test
    public void indexesLazilyWithoutLoadingItems() {
        Path second = testPath("second.zip");
        Path first = testPath("first.zip");
        RecordingAccess access = new RecordingAccess(
                cancellation -> supportedIndex(List.of(second, first)),
                (paths, cancellation) -> {
                    throw new AssertionError("Indexing must not load items");
                });
        ManualExecutor executor = new ManualExecutor();
        DefaultResourcePackCatalogModel model = model(access, executor);

        CompletionStage<ChoicePage<ResourcePackCatalogItem>> premature = model.load(
                new IndexRange(0, 1),
                new LoadCancellation());
        assertAll(
                () -> assertEquals(OptionalInt.empty(), model.exactItemCount()),
                () -> assertEquals(OptionalInt.empty(), model.snapshot().itemCount()),
                () -> assertInstanceOf(IllegalStateException.class, stageFailure(premature)),
                () -> assertEquals(0, access.indexCalls()),
                () -> assertEquals(0, access.itemCalls()));

        model.loadIfNeeded();
        model.loadIfNeeded();
        assertAll(
                () -> assertEquals(1, executor.taskCount()),
                () -> assertEquals(0, access.indexCalls()),
                () -> assertEquals(ResourcePackCatalogStatus.LOADING, model.snapshot().status()));
        executor.runNext();

        assertAll(
                () -> assertEquals(1, access.indexCalls()),
                () -> assertEquals(0, access.itemCalls()),
                () -> assertEquals(ResourcePackCatalogStatus.READY, model.snapshot().status()),
                () -> assertEquals(OptionalInt.of(2), model.exactItemCount()),
                () -> assertEquals(1L, model.snapshot().contentRevision()),
                () -> assertTrue(model.snapshot().listEnabled()));
        model.close();
    }

    /// Verifies one viewport call resolves only its clamped actual paths and never widens the range.
    @Test
    public void loadsOnlyExactActualRangeWithoutPageOrCacheExpansion() {
        Path third = testPath("third.zip");
        Path first = testPath("first.zip");
        Path second = testPath("second.zip");
        RecordingAccess access = new RecordingAccess(
                cancellation -> supportedIndex(List.of(third, first, second)),
                DefaultResourcePackCatalogModelTest::itemsForPaths);
        ManualExecutor executor = new ManualExecutor();
        DefaultResourcePackCatalogModel model = model(access, executor);
        model.loadIfNeeded();
        executor.runNext();

        CompletionStage<ChoicePage<ResourcePackCatalogItem>> range = model.load(
                new IndexRange(1, 1000),
                new LoadCancellation());
        assertAll(
                () -> assertEquals(1, executor.taskCount()),
                () -> assertEquals(0, access.itemCalls()));
        executor.runNext();
        ChoicePage<ResourcePackCatalogItem> page = range.toCompletableFuture().join();

        assertAll(
                () -> assertEquals(List.of(second, third), access.requestedPaths().get(0)),
                () -> assertEquals(new IndexRange(1, 3), page.range()),
                () -> assertEquals(List.of(second, third), page.items().stream()
                        .map(ResourcePackCatalogItem::path)
                        .toList()),
                () -> assertEquals(OptionalInt.of(3), page.exactItemCount()),
                () -> assertTrue(page.endOfData()));

        ChoicePage<ResourcePackCatalogItem> empty = model.load(
                new IndexRange(20, 40),
                new LoadCancellation()).toCompletableFuture().join();
        assertAll(
                () -> assertEquals(new IndexRange(3, 3), empty.range()),
                () -> assertEquals(1, access.itemCalls()),
                () -> assertEquals(0, executor.taskCount()));
        model.close();
    }

    /// Verifies both indexing and range parsing requested on EDT execute on a worker.
    @Test
    public void keepsBothBlockingStagesOffSwingEventDispatchThread() {
        Path pack = testPath("worker.zip");
        AtomicReference<@Nullable Boolean> indexWasOnEdt = new AtomicReference<>();
        AtomicReference<@Nullable Boolean> itemsWereOnEdt = new AtomicReference<>();
        RecordingAccess access = new RecordingAccess(
                cancellation -> {
                    indexWasOnEdt.set(SwingUtilities.isEventDispatchThread());
                    return supportedIndex(List.of(pack));
                },
                (paths, cancellation) -> {
                    itemsWereOnEdt.set(SwingUtilities.isEventDispatchThread());
                    return itemsForPaths(paths, cancellation);
                });
        ManualExecutor executor = new ManualExecutor();
        DefaultResourcePackCatalogModel model = model(access, executor);

        invokeOnEdt(model::loadIfNeeded);
        runOnWorker(executor.takeNext(), "resource-pack-index-worker");
        AtomicReference<@Nullable CompletionStage<ChoicePage<ResourcePackCatalogItem>>> result =
                new AtomicReference<>();
        invokeOnEdt(() -> result.set(model.load(new IndexRange(0, 1), new LoadCancellation())));
        runOnWorker(executor.takeNext(), "resource-pack-range-worker");

        assertAll(
                () -> assertEquals(Boolean.FALSE, indexWasOnEdt.get()),
                () -> assertEquals(Boolean.FALSE, itemsWereOnEdt.get()),
                () -> assertEquals(1, requireNonNull(result.get()).toCompletableFuture().join().items().size()));
        model.close();
    }

    /// Verifies selection survives an equivalent refreshed index and clear is content-neutral.
    @Test
    public void preservesAndClearsStablePathSelection() {
        Path first = testPath("first.zip");
        Path selected = testPath("selected.zip");
        AtomicInteger generation = new AtomicInteger();
        RecordingAccess access = new RecordingAccess(
                cancellation -> switch (generation.getAndIncrement()) {
                    case 0, 1 -> supportedIndex(List.of(first, selected));
                    default -> supportedIndex(List.of(first));
                },
                DefaultResourcePackCatalogModelTest::itemsForPaths);
        DefaultResourcePackCatalogModel model = model(access, Runnable::run);

        model.loadIfNeeded();
        model.selectResourcePack(selected);
        long selectedRevision = model.snapshot().contentRevision();
        model.refresh();
        assertAll(
                () -> assertEquals(OptionalInt.of(1), model.snapshot().selectedIndex()),
                () -> assertNotEquals(selectedRevision, model.snapshot().contentRevision()));

        long refreshedRevision = model.snapshot().contentRevision();
        model.clearSelection();
        assertAll(
                () -> assertEquals(OptionalInt.empty(), model.snapshot().selectedIndex()),
                () -> assertEquals(refreshedRevision, model.snapshot().contentRevision()));

        model.selectResourcePack(selected);
        model.refresh();
        assertAll(
                () -> assertEquals(OptionalInt.empty(), model.snapshot().selectedIndex()),
                () -> assertEquals(OptionalInt.of(1), model.snapshot().itemCount()),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> model.selectResourcePack(selected)));
        model.close();
    }

    /// Verifies unsupported and failed indexes retain an honest exact-count distinction.
    @Test
    public void distinguishesUnsupportedExactEmptyFromFailedUnknownIndex() {
        AtomicInteger calls = new AtomicInteger();
        RecordingAccess access = new RecordingAccess(
                cancellation -> {
                    if (calls.getAndIncrement() == 0) {
                        throw new IOException("index failed");
                    }
                    return new ResourcePackCatalogIndex(false, List.of());
                },
                DefaultResourcePackCatalogModelTest::itemsForPaths);
        DefaultResourcePackCatalogModel model = model(access, Runnable::run);

        model.loadIfNeeded();
        assertAll(
                () -> assertEquals(ResourcePackCatalogStatus.FAILED, model.snapshot().status()),
                () -> assertEquals(OptionalInt.empty(), model.snapshot().itemCount()),
                () -> assertEquals("failed: index failed", model.snapshot().statusText()),
                () -> assertTrue(model.snapshot().refreshEnabled()));

        model.refresh();
        assertAll(
                () -> assertEquals(ResourcePackCatalogStatus.UNSUPPORTED, model.snapshot().status()),
                () -> assertEquals(OptionalInt.of(0), model.snapshot().itemCount()),
                () -> assertEquals("unsupported", model.snapshot().statusText()),
                () -> assertFalse(model.snapshot().listEnabled()));
        model.close();
    }

    /// Verifies index executor rejection is visible while range rejection stays on its returned stage.
    @Test
    public void reportsExecutorRejectionAtTheOwningStage() {
        RejectedExecutionException indexRejection = new RejectedExecutionException("index executor stopped");
        DefaultResourcePackCatalogModel rejectedIndexModel = model(
                new RecordingAccess(
                        cancellation -> supportedIndex(List.of()),
                        DefaultResourcePackCatalogModelTest::itemsForPaths),
                command -> {
                    throw indexRejection;
                });
        rejectedIndexModel.loadIfNeeded();
        assertAll(
                () -> assertEquals(ResourcePackCatalogStatus.FAILED,
                        rejectedIndexModel.snapshot().status()),
                () -> assertEquals("failed: index executor stopped",
                        rejectedIndexModel.snapshot().statusText()));
        rejectedIndexModel.close();

        Path pack = testPath("range.zip");
        SwitchableExecutor executor = new SwitchableExecutor();
        DefaultResourcePackCatalogModel rejectedRangeModel = model(
                new RecordingAccess(
                        cancellation -> supportedIndex(List.of(pack)),
                        DefaultResourcePackCatalogModelTest::itemsForPaths),
                executor);
        rejectedRangeModel.loadIfNeeded();
        executor.reject(new RejectedExecutionException("range executor stopped"));
        CompletionStage<ChoicePage<ResourcePackCatalogItem>> rejectedRange = rejectedRangeModel.load(
                new IndexRange(0, 1),
                new LoadCancellation());
        assertAll(
                () -> assertEquals("range executor stopped", stageFailure(rejectedRange).getMessage()),
                () -> assertEquals(ResourcePackCatalogStatus.READY,
                        rejectedRangeModel.snapshot().status()),
                () -> assertEquals(OptionalInt.of(1), rejectedRangeModel.snapshot().itemCount()));
        rejectedRangeModel.close();
    }

    /// Verifies caller cancellation prevents item access and yields cancellation rather than a fake page.
    @Test
    public void honorsCallerCancellationBeforeRangeExecution() {
        Path pack = testPath("cancelled.zip");
        RecordingAccess access = new RecordingAccess(
                cancellation -> supportedIndex(List.of(pack)),
                DefaultResourcePackCatalogModelTest::itemsForPaths);
        ManualExecutor executor = new ManualExecutor();
        DefaultResourcePackCatalogModel model = model(access, executor);
        model.loadIfNeeded();
        executor.runNext();

        LoadCancellation cancellation = new LoadCancellation();
        CompletionStage<ChoicePage<ResourcePackCatalogItem>> result = model.load(
                new IndexRange(0, 1),
                cancellation);
        cancellation.cancel();
        executor.runNext();

        assertAll(
                () -> assertInstanceOf(CancellationException.class, stageFailure(result)),
                () -> assertEquals(0, access.itemCalls()));
        model.close();
    }

    /// Verifies cancellation while waiting for the submission lock cannot strand a range Future.
    @Test
    public void cancellationBeforeExecutorSubmissionCompletesRangeFuture() {
        Path pack = testPath("cancel-before-submit.zip");
        RecordingAccess access = new RecordingAccess(
                cancellation -> supportedIndex(List.of(pack)),
                DefaultResourcePackCatalogModelTest::itemsForPaths);
        BlockingRangeSubmissionExecutor executor = new BlockingRangeSubmissionExecutor();
        DefaultResourcePackCatalogModel model = model(access, executor);
        model.loadIfNeeded();
        executor.runNext();

        AtomicReference<@Nullable CompletionStage<ChoicePage<ResourcePackCatalogItem>>> firstResult =
                new AtomicReference<>();
        Thread firstLoadThread = daemonThread("resource-pack-first-submission", () -> firstResult.set(
                model.load(new IndexRange(0, 1), new LoadCancellation())));
        firstLoadThread.start();
        executor.awaitBlockedSubmission();

        LoadCancellation secondCancellation = new LoadCancellation();
        AtomicReference<@Nullable CompletionStage<ChoicePage<ResourcePackCatalogItem>>> secondResult =
                new AtomicReference<>();
        Thread secondLoadThread = daemonThread("resource-pack-cancelled-submission", () -> secondResult.set(
                model.load(new IndexRange(0, 1), secondCancellation)));
        secondLoadThread.start();
        awaitCondition(
                () -> secondLoadThread.getState() == Thread.State.BLOCKED,
                "second range did not wait for the submission lock");
        secondCancellation.cancel();
        executor.releaseBlockedSubmission();
        joinThread(firstLoadThread, "first range submission did not return");
        joinThread(secondLoadThread, "cancelled range submission did not return");

        CompletionStage<ChoicePage<ResourcePackCatalogItem>> cancelledStage =
                requireNonNull(secondResult.get());
        assertAll(
                () -> assertInstanceOf(CancellationException.class, stageFailure(cancelledStage)),
                () -> assertEquals(1, executor.taskCount()),
                () -> assertEquals(0, access.itemCalls()));
        executor.runNext();
        assertEquals(List.of(pack), requireNonNull(firstResult.get()).toCompletableFuture().join()
                .items().stream().map(ResourcePackCatalogItem::path).toList());
        model.close();
    }

    /// Verifies refresh immediately cancels an in-flight old range and ignores its late result.
    @Test
    public void refreshCancelsAndRejectsLateRangeFromPreviousIndex() {
        Path oldPack = testPath("old.zip");
        Path currentPack = testPath("current.zip");
        AtomicInteger indexGeneration = new AtomicInteger();
        CountDownLatch itemsEntered = new CountDownLatch(1);
        CountDownLatch releaseItems = new CountDownLatch(1);
        RecordingAccess access = new RecordingAccess(
                cancellation -> supportedIndex(indexGeneration.getAndIncrement() == 0
                        ? List.of(oldPack)
                        : List.of(currentPack)),
                (paths, cancellation) -> {
                    itemsEntered.countDown();
                    awaitLatch(releaseItems, "old range was not released");
                    return itemsForPaths(paths, cancellation);
                });
        ManualExecutor executor = new ManualExecutor();
        DefaultResourcePackCatalogModel model = model(access, executor);
        model.loadIfNeeded();
        executor.runNext();

        LoadCancellation callerCancellation = new LoadCancellation();
        CompletionStage<ChoicePage<ResourcePackCatalogItem>> staleRange = model.load(
                new IndexRange(0, 1),
                callerCancellation);
        Thread staleWorker = daemonThread("resource-pack-stale-range", executor.takeNext());
        staleWorker.start();
        awaitLatch(itemsEntered, "old range did not start");

        model.refresh();
        assertAll(
                () -> assertFalse(callerCancellation.isCancelled()),
                () -> assertInstanceOf(CancellationException.class, stageFailure(staleRange)),
                () -> assertEquals(OptionalInt.empty(), model.snapshot().itemCount()));
        executor.runNext();
        releaseItems.countDown();
        joinThread(staleWorker, "old range did not terminate");

        ChoicePage<ResourcePackCatalogItem> currentPage = loadDirect(model, new IndexRange(0, 1), executor);
        assertAll(
                () -> assertEquals(List.of(currentPack), currentPage.items().stream()
                        .map(ResourcePackCatalogItem::path)
                        .toList()),
                () -> assertEquals(ResourcePackCatalogStatus.READY, model.snapshot().status()));
        model.close();
    }

    /// Verifies refresh cancels a resolved range before its future receives a terminal value.
    @Test
    public void refreshCancelsRangeBetweenResolutionAndFutureCompletion() {
        Path oldPack = testPath("claimed-old.zip");
        Path currentPack = testPath("claimed-current.zip");
        AtomicInteger indexGeneration = new AtomicInteger();
        AtomicInteger terminalHooks = new AtomicInteger();
        AtomicReference<@Nullable DefaultResourcePackCatalogModel> modelReference =
                new AtomicReference<>();
        RecordingAccess access = new RecordingAccess(
                cancellation -> supportedIndex(indexGeneration.getAndIncrement() == 0
                        ? List.of(oldPack)
                        : List.of(currentPack)),
                DefaultResourcePackCatalogModelTest::itemsForPaths);
        DefaultResourcePackCatalogModel model = new DefaultResourcePackCatalogModel(
                access,
                Runnable::run,
                STATUS_STRINGS,
                () -> {
                    if (terminalHooks.getAndIncrement() == 0) {
                        requireNonNull(modelReference.get()).refresh();
                    }
                });
        modelReference.set(model);
        model.loadIfNeeded();

        CompletionStage<ChoicePage<ResourcePackCatalogItem>> stalePage = model.load(
                new IndexRange(0, 1),
                new LoadCancellation());
        ChoicePage<ResourcePackCatalogItem> currentPage = model.load(
                new IndexRange(0, 1),
                new LoadCancellation()).toCompletableFuture().join();

        assertAll(
                () -> assertInstanceOf(CancellationException.class, stageFailure(stalePage)),
                () -> assertEquals(List.of(currentPack), currentPage.items().stream()
                        .map(ResourcePackCatalogItem::path)
                        .toList()),
                () -> assertEquals(ResourcePackCatalogStatus.READY, model.snapshot().status()),
                () -> assertEquals(2, indexGeneration.get()));
        model.close();
    }

    /// Verifies close owns a resolved range before its future receives a terminal value.
    @Test
    public void closeCancelsRangeBetweenResolutionAndFutureCompletion() {
        Path pack = testPath("close-before-terminal.zip");
        AtomicReference<@Nullable DefaultResourcePackCatalogModel> modelReference =
                new AtomicReference<>();
        RecordingAccess access = new RecordingAccess(
                cancellation -> supportedIndex(List.of(pack)),
                DefaultResourcePackCatalogModelTest::itemsForPaths);
        DefaultResourcePackCatalogModel model = new DefaultResourcePackCatalogModel(
                access,
                Runnable::run,
                STATUS_STRINGS,
                () -> requireNonNull(modelReference.get()).close());
        modelReference.set(model);
        model.loadIfNeeded();

        CompletionStage<ChoicePage<ResourcePackCatalogItem>> result = model.load(
                new IndexRange(0, 1),
                new LoadCancellation());

        assertAll(
                () -> assertInstanceOf(CancellationException.class, stageFailure(result)),
                () -> assertFalse(model.snapshot().listEnabled()),
                () -> assertFalse(model.snapshot().refreshEnabled()),
                () -> assertThrows(IllegalStateException.class, model::refresh));
    }

    /// Verifies close revision invalidation rejects success decided before Future publication.
    @Test
    public void closeRevisionRejectsOutcomeDecidedBeforePublication() {
        Path pack = testPath("close-after-decision.zip");
        AtomicReference<@Nullable DefaultResourcePackCatalogModel> modelReference =
                new AtomicReference<>();
        DefaultResourcePackCatalogModel model = new DefaultResourcePackCatalogModel(
                new RecordingAccess(
                        cancellation -> supportedIndex(List.of(pack)),
                        DefaultResourcePackCatalogModelTest::itemsForPaths),
                Runnable::run,
                STATUS_STRINGS,
                () -> { },
                () -> requireNonNull(modelReference.get()).close());
        modelReference.set(model);
        model.loadIfNeeded();
        CountingViewportListener listener = new CountingViewportListener();
        ViewportRequestCoordinator<ResourcePackCatalogItem> coordinator =
                new ViewportRequestCoordinator<>(model, listener);
        IndexRange range = new IndexRange(0, 1);

        coordinator.request(new ViewportLoadPlan(
                range,
                range,
                Set.of(),
                ScrollDirection.STATIONARY,
                0.0,
                0));

        assertAll(
                () -> assertEquals(0, listener.loadedCount()),
                () -> assertEquals(0, listener.failedCount()),
                () -> assertFalse(model.snapshot().listEnabled()),
                () -> assertFalse(model.snapshot().refreshEnabled()));
        coordinator.close();
    }

    /// Verifies close promptly cancels range work, disables flags, and terminates subscriptions.
    @Test
    public void closeCancelsRangesDisablesFlagsAndTerminatesListeners() {
        Path pack = testPath("close.zip");
        CountDownLatch itemsEntered = new CountDownLatch(1);
        CountDownLatch releaseItems = new CountDownLatch(1);
        RecordingAccess access = new RecordingAccess(
                cancellation -> supportedIndex(List.of(pack)),
                (paths, cancellation) -> {
                    itemsEntered.countDown();
                    awaitLatch(releaseItems, "closing range was not released");
                    return itemsForPaths(paths, cancellation);
                });
        ManualExecutor executor = new ManualExecutor();
        DefaultResourcePackCatalogModel model = model(access, executor);
        model.loadIfNeeded();
        executor.runNext();
        AtomicInteger callbacks = new AtomicInteger();
        model.subscribe(change -> callbacks.incrementAndGet());

        CompletionStage<ChoicePage<ResourcePackCatalogItem>> result = model.load(
                new IndexRange(0, 1),
                new LoadCancellation());
        Thread worker = daemonThread("resource-pack-closing-range", executor.takeNext());
        worker.start();
        awaitLatch(itemsEntered, "closing range did not start");
        long revisionBeforeClose = model.snapshot().contentRevision();
        model.close();

        assertAll(
                () -> assertInstanceOf(CancellationException.class, stageFailure(result)),
                () -> assertFalse(model.snapshot().listEnabled()),
                () -> assertFalse(model.snapshot().refreshEnabled()),
                () -> assertEquals(OptionalInt.of(1), model.snapshot().itemCount()),
                () -> assertEquals(revisionBeforeClose + 1L,
                        model.snapshot().contentRevision()),
                () -> assertEquals(0, callbacks.get()),
                () -> assertThrows(IllegalStateException.class, model::refresh),
                () -> assertThrows(IllegalStateException.class,
                        () -> model.subscribe(ignored -> { })),
                () -> assertThrows(IllegalStateException.class,
                        () -> model.load(new IndexRange(0, 1), new LoadCancellation())));
        releaseItems.countDown();
        joinThread(worker, "closing range did not terminate");
    }

    /// Verifies a subscriber cannot observe a transition committed before its sequence lower bound.
    @Test
    public void subscriptionSequenceLowerBoundSkipsAlreadyCommittedTransition() {
        Path pack = testPath("sequence.zip");
        DefaultResourcePackCatalogModel model = model(
                new RecordingAccess(
                        cancellation -> supportedIndex(List.of(pack)),
                        DefaultResourcePackCatalogModelTest::itemsForPaths),
                Runnable::run);
        model.loadIfNeeded();
        CountDownLatch firstListenerEntered = new CountDownLatch(1);
        CountDownLatch releaseFirstListener = new CountDownLatch(1);
        model.subscribe(change -> {
            if (change.currentValue() != null && change.currentValue().selectedIndex().isPresent()) {
                firstListenerEntered.countDown();
                awaitLatch(releaseFirstListener, "first listener was not released");
            }
        });

        Thread selectThread = daemonThread("resource-pack-select-publication", () ->
                model.selectResourcePack(pack));
        selectThread.start();
        awaitLatch(firstListenerEntered, "selection listener did not start");
        Thread clearThread = daemonThread("resource-pack-clear-publication", model::clearSelection);
        clearThread.start();
        awaitCondition(
                () -> model.snapshot().selectedIndex().isEmpty(),
                "clear transition was not committed");

        AtomicInteger lateSubscriberCallbacks = new AtomicInteger();
        model.subscribe(change -> lateSubscriberCallbacks.incrementAndGet());
        releaseFirstListener.countDown();
        joinThread(selectThread, "selection publication did not finish");
        joinThread(clearThread, "clear publication did not finish");
        assertEquals(0, lateSubscriberCallbacks.get());

        model.selectResourcePack(pack);
        assertEquals(1, lateSubscriberCallbacks.get());
        model.close();
    }

    /// Verifies unsubscribe waits for in-flight delivery and prevents later callbacks.
    @Test
    public void unsubscribeCrossesSynchronousListenerTerminationBarrier() {
        Path pack = testPath("unsubscribe.zip");
        DefaultResourcePackCatalogModel model = model(
                new RecordingAccess(
                        cancellation -> supportedIndex(List.of(pack)),
                        DefaultResourcePackCatalogModelTest::itemsForPaths),
                Runnable::run);
        model.loadIfNeeded();
        CountDownLatch listenerEntered = new CountDownLatch(1);
        CountDownLatch releaseListener = new CountDownLatch(1);
        AtomicInteger callbacks = new AtomicInteger();
        var subscription = model.subscribe(change -> {
            callbacks.incrementAndGet();
            listenerEntered.countDown();
            awaitLatch(releaseListener, "listener was not released");
        });
        Thread selectThread = daemonThread("resource-pack-blocked-listener", () ->
                model.selectResourcePack(pack));
        selectThread.start();
        awaitLatch(listenerEntered, "listener did not start");

        CountDownLatch unsubscribeReturned = new CountDownLatch(1);
        Thread unsubscribeThread = daemonThread("resource-pack-unsubscribe", () -> {
            subscription.unsubscribe();
            unsubscribeReturned.countDown();
        });
        unsubscribeThread.start();
        assertFalse(awaitBriefly(unsubscribeReturned), "unsubscribe returned during active delivery");
        releaseListener.countDown();
        joinThread(selectThread, "blocked listener publication did not finish");
        joinThread(unsubscribeThread, "unsubscribe did not cross termination barrier");

        model.clearSelection();
        assertEquals(1, callbacks.get());
        model.close();
    }

    /// Verifies listener callbacks hold no publication lock needed by another state-changing thread.
    @Test
    public void listenerCallbackAllowsConcurrentSelectionPublication() {
        Path pack = testPath("listener-reentry.zip");
        DefaultResourcePackCatalogModel model = model(
                new RecordingAccess(
                        cancellation -> supportedIndex(List.of(pack)),
                        DefaultResourcePackCatalogModelTest::itemsForPaths),
                Runnable::run);
        model.loadIfNeeded();
        model.selectResourcePack(pack);
        AtomicInteger callbacks = new AtomicInteger();
        model.subscribe(change -> {
            if (callbacks.getAndIncrement() != 0) {
                return;
            }
            Thread selectionThread = daemonThread(
                    "resource-pack-listener-selection",
                    () -> model.selectResourcePack(pack));
            selectionThread.start();
            joinThread(selectionThread, "listener-held lock blocked selection publication");
        });

        Thread publicationThread = daemonThread(
                "resource-pack-listener-publication",
                model::clearSelection);
        publicationThread.start();
        joinThread(publicationThread, "listener publication did not finish");

        assertAll(
                () -> assertEquals(OptionalInt.of(0), model.snapshot().selectedIndex()),
                () -> assertEquals(2, callbacks.get()));
        model.close();
    }

    /// Verifies concurrent transitions never execute different listener slots at the same time.
    @Test
    public void listenerCallbacksUseOneGlobalDeliveryOwner() {
        Path pack = testPath("listener-owner.zip");
        DefaultResourcePackCatalogModel model = model(
                new RecordingAccess(
                        cancellation -> supportedIndex(List.of(pack)),
                        DefaultResourcePackCatalogModelTest::itemsForPaths),
                Runnable::run);
        model.loadIfNeeded();
        model.selectResourcePack(pack);
        AtomicInteger activeCallbacks = new AtomicInteger();
        AtomicInteger maximumCallbacks = new AtomicInteger();
        CountDownLatch firstListenerEntered = new CountDownLatch(1);
        CountDownLatch secondListenerEntered = new CountDownLatch(1);
        CountDownLatch releaseFirstListener = new CountDownLatch(1);
        model.subscribe(change -> {
            int active = activeCallbacks.incrementAndGet();
            maximumCallbacks.accumulateAndGet(active, Math::max);
            firstListenerEntered.countDown();
            awaitLatch(releaseFirstListener, "first listener was not released");
            activeCallbacks.decrementAndGet();
        });
        model.subscribe(change -> {
            int active = activeCallbacks.incrementAndGet();
            maximumCallbacks.accumulateAndGet(active, Math::max);
            secondListenerEntered.countDown();
            activeCallbacks.decrementAndGet();
        });

        Thread firstTransition = daemonThread(
                "resource-pack-first-listener-transition",
                model::clearSelection);
        firstTransition.start();
        awaitLatch(firstListenerEntered, "first listener did not start");
        Thread secondTransition = daemonThread(
                "resource-pack-second-listener-transition",
                () -> model.selectResourcePack(pack));
        secondTransition.start();
        joinThread(secondTransition, "second transition did not enqueue");
        assertFalse(awaitBriefly(secondListenerEntered),
                "second listener ran concurrently with the first listener");

        releaseFirstListener.countDown();
        joinThread(firstTransition, "first transition did not finish");
        awaitLatch(secondListenerEntered, "second listener was not delivered");
        assertEquals(1, maximumCallbacks.get());
        model.close();
    }

    /// Verifies one successful write owns BUSY exclusively and replaces the index exactly once.
    @Test
    public void successfulMutationPublishesBusyThenOneExactRefreshedRevision() {
        Path first = testPath("first.zip");
        Path second = testPath("second.zip");
        Path source = testPath("source.zip");
        RecordingAccess access = new RecordingAccess(
                cancellation -> supportedIndex(List.of(first)),
                DefaultResourcePackCatalogModelTest::itemsForPaths,
                (mutation, cancellation, commitPoint) -> {
                    assertInstanceOf(ResourcePackImportMutation.class, mutation);
                    cancellation.throwIfCancelled();
                    commitPoint.run();
                    return new ResourcePackCatalogMutationAccessResult(
                            supportedIndex(List.of(first, second)),
                            null);
                });
        ManualExecutor executor = new ManualExecutor();
        DefaultResourcePackCatalogModel model = model(access, executor);
        model.loadIfNeeded();
        executor.runNext();
        model.selectResourcePack(first);

        CompletionStage<ResourcePackCatalogSnapshot> duplicateImport =
                model.importResourcePacks(List.of(source, source));
        assertInstanceOf(IllegalArgumentException.class, stageFailure(duplicateImport));

        CompletionStage<ResourcePackCatalogSnapshot> completion =
                model.importResourcePacks(List.of(source));
        ResourcePackCatalogSnapshot busy = model.snapshot();
        CompletionStage<ChoicePage<ResourcePackCatalogItem>> blockedRange = model.load(
                new IndexRange(0, 1),
                new LoadCancellation());

        assertAll(
                () -> assertEquals(ResourcePackCatalogWriteStatus.BUSY, busy.writeStatus()),
                () -> assertEquals("writing", busy.writeStatusText()),
                () -> assertEquals(1L, busy.contentRevision()),
                () -> assertEquals(OptionalInt.of(1), busy.itemCount()),
                () -> assertFalse(busy.listEnabled()),
                () -> assertFalse(busy.refreshEnabled()),
                () -> assertFalse(completion.toCompletableFuture().isDone()),
                () -> assertInstanceOf(IllegalStateException.class, stageFailure(blockedRange)),
                () -> assertThrows(IllegalStateException.class, model::refresh),
                () -> assertThrows(
                        IllegalStateException.class,
                        () -> model.enableResourcePack(first)));

        executor.runNext();
        ResourcePackCatalogSnapshot terminal = completion.toCompletableFuture().join();
        assertAll(
                () -> assertEquals(terminal, model.snapshot()),
                () -> assertEquals(ResourcePackCatalogWriteStatus.IDLE, terminal.writeStatus()),
                () -> assertEquals("", terminal.writeStatusText()),
                () -> assertEquals(ResourcePackCatalogStatus.READY, terminal.status()),
                () -> assertEquals(OptionalInt.of(2), terminal.itemCount()),
                () -> assertEquals(OptionalInt.of(0), terminal.selectedIndex()),
                () -> assertEquals(2L, terminal.contentRevision()),
                () -> assertEquals(1, access.mutationCalls()));
        model.close();
    }

    /// Verifies a failed write still publishes the real post-failure index and clears deletion selection.
    @Test
    public void mutationFailurePublishesRescannedRealityAndErrorState() {
        Path retained = testPath("retained.zip");
        Path deleted = testPath("deleted.zip");
        IOException writeFailure = new IOException("delete failed after persistence");
        RecordingAccess access = new RecordingAccess(
                cancellation -> supportedIndex(List.of(retained, deleted)),
                DefaultResourcePackCatalogModelTest::itemsForPaths,
                (mutation, cancellation, commitPoint) -> {
                    assertInstanceOf(ResourcePackDeleteMutation.class, mutation);
                    commitPoint.run();
                    return new ResourcePackCatalogMutationAccessResult(
                            supportedIndex(List.of(retained)),
                            writeFailure);
                });
        ManualExecutor executor = new ManualExecutor();
        DefaultResourcePackCatalogModel model = model(access, executor);
        model.loadIfNeeded();
        executor.runNext();
        model.selectResourcePack(deleted);

        CompletionStage<ResourcePackCatalogSnapshot> completion =
                model.deleteResourcePack(deleted);
        executor.runNext();
        Throwable observed = stageFailure(completion);
        ResourcePackCatalogSnapshot terminal = model.snapshot();

        assertAll(
                () -> assertEquals(writeFailure, observed),
                () -> assertEquals(ResourcePackCatalogStatus.READY, terminal.status()),
                () -> assertEquals(ResourcePackCatalogWriteStatus.ERROR, terminal.writeStatus()),
                () -> assertTrue(terminal.writeStatusText().contains("delete failed")),
                () -> assertEquals(OptionalInt.of(1), terminal.itemCount()),
                () -> assertEquals(OptionalInt.empty(), terminal.selectedIndex()),
                () -> assertEquals(2L, terminal.contentRevision()),
                () -> assertTrue(terminal.listEnabled()),
                () -> assertTrue(terminal.refreshEnabled()));
        model.close();
    }

    /// Verifies executor rejection reports a write error without pretending indexed content changed.
    @Test
    public void mutationExecutorRejectionPreservesExactIndexAndRevision() {
        Path pack = testPath("rejected.zip");
        RecordingAccess access = new RecordingAccess(
                cancellation -> supportedIndex(List.of(pack)),
                DefaultResourcePackCatalogModelTest::itemsForPaths,
                (mutation, cancellation, commitPoint) -> {
                    throw new AssertionError("Rejected mutation must not reach source access");
                });
        SwitchableExecutor executor = new SwitchableExecutor();
        DefaultResourcePackCatalogModel model = model(access, executor);
        model.loadIfNeeded();
        RejectedExecutionException rejection = new RejectedExecutionException("write rejected");
        executor.reject(rejection);

        CompletionStage<ResourcePackCatalogSnapshot> completion = model.enableResourcePack(pack);
        ResourcePackCatalogSnapshot terminal = model.snapshot();

        assertAll(
                () -> assertEquals(rejection, stageFailure(completion)),
                () -> assertEquals(ResourcePackCatalogStatus.READY, terminal.status()),
                () -> assertEquals(ResourcePackCatalogWriteStatus.ERROR, terminal.writeStatus()),
                () -> assertEquals(OptionalInt.of(1), terminal.itemCount()),
                () -> assertEquals(1L, terminal.contentRevision()),
                () -> assertTrue(terminal.listEnabled()),
                () -> assertTrue(terminal.refreshEnabled()),
                () -> assertEquals(0, access.mutationCalls()));
        model.close();
    }

    /// Verifies close cancels queued pre-commit work and rejects its late executor task.
    @Test
    public void closeCancelsPreCommitMutationAndRejectsLateSourceWork() {
        Path pack = testPath("pre-commit.zip");
        RecordingAccess access = new RecordingAccess(
                cancellation -> supportedIndex(List.of(pack)),
                DefaultResourcePackCatalogModelTest::itemsForPaths,
                (mutation, cancellation, commitPoint) -> {
                    throw new AssertionError("Closed pre-commit mutation reached source access");
                });
        ManualExecutor executor = new ManualExecutor();
        DefaultResourcePackCatalogModel model = model(access, executor);
        model.loadIfNeeded();
        executor.runNext();

        CompletionStage<ResourcePackCatalogSnapshot> completion = model.disableResourcePack(pack);
        model.close();
        Throwable observed = stageFailure(completion);
        executor.runNext();

        assertAll(
                () -> assertInstanceOf(CancellationException.class, observed),
                () -> assertEquals(0, access.mutationCalls()),
                () -> assertEquals(2L, model.snapshot().contentRevision()),
                () -> assertFalse(model.snapshot().listEnabled()),
                () -> assertFalse(model.snapshot().refreshEnabled()));
    }

    /// Verifies close cannot misreport cancellation after source access crosses its commit point.
    @Test
    public void closeAllowsCommittedMutationFutureToReportActualCompletion() {
        Path first = testPath("committed-first.zip");
        Path second = testPath("committed-second.zip");
        CountDownLatch committed = new CountDownLatch(1);
        CountDownLatch releaseMutation = new CountDownLatch(1);
        RecordingAccess access = new RecordingAccess(
                cancellation -> supportedIndex(List.of(first)),
                DefaultResourcePackCatalogModelTest::itemsForPaths,
                (mutation, cancellation, commitPoint) -> {
                    commitPoint.run();
                    committed.countDown();
                    awaitLatch(releaseMutation, "committed mutation was not released");
                    return new ResourcePackCatalogMutationAccessResult(
                            supportedIndex(List.of(first, second)),
                            null);
                });
        ManualExecutor executor = new ManualExecutor();
        DefaultResourcePackCatalogModel model = model(access, executor);
        model.loadIfNeeded();
        executor.runNext();
        CompletionStage<ResourcePackCatalogSnapshot> completion = model.enableResourcePack(first);
        Thread worker = daemonThread("resource-pack-committed-mutation", executor.takeNext());

        worker.start();
        awaitLatch(committed, "mutation did not cross its commit point");
        model.close();
        assertFalse(completion.toCompletableFuture().isDone(),
                "close misreported committed mutation as cancelled");
        releaseMutation.countDown();
        joinThread(worker, "committed mutation did not finish");
        ResourcePackCatalogSnapshot completedSnapshot = completion.toCompletableFuture().join();

        assertAll(
                () -> assertEquals(model.snapshot(), completedSnapshot),
                () -> assertEquals(1, access.mutationCalls()),
                () -> assertEquals(2L, model.snapshot().contentRevision()),
                () -> assertFalse(model.snapshot().refreshEnabled()));
    }

    /// Verifies listener and Future callbacks may reenter model commands without lock inversion.
    @Test
    public void mutationListenerAndFutureCompletionAllowReentrantCommands() {
        Path pack = testPath("reentrant-write.zip");
        RecordingAccess access = new RecordingAccess(
                cancellation -> supportedIndex(List.of(pack)),
                DefaultResourcePackCatalogModelTest::itemsForPaths,
                (mutation, cancellation, commitPoint) -> {
                    commitPoint.run();
                    return new ResourcePackCatalogMutationAccessResult(
                            supportedIndex(List.of(pack)),
                            null);
                });
        ManualExecutor executor = new ManualExecutor();
        DefaultResourcePackCatalogModel model = model(access, executor);
        model.loadIfNeeded();
        executor.runNext();
        model.selectResourcePack(pack);
        AtomicInteger terminalListenerCalls = new AtomicInteger();
        model.subscribe(change -> {
            if (change.previousValue().writeStatus() == ResourcePackCatalogWriteStatus.BUSY
                    && change.currentValue().writeStatus() == ResourcePackCatalogWriteStatus.IDLE) {
                terminalListenerCalls.incrementAndGet();
                model.clearSelection();
            }
        });

        CompletionStage<ResourcePackCatalogSnapshot> completion = model.disableResourcePack(pack);
        CompletionStage<ResourcePackCatalogSnapshot> reentrant = completion.thenApply(snapshot -> {
            model.refresh();
            return snapshot;
        });
        executor.runNext();

        assertAll(
                () -> assertEquals(1, terminalListenerCalls.get()),
                () -> assertEquals(OptionalInt.empty(), model.snapshot().selectedIndex()),
                () -> assertEquals(ResourcePackCatalogStatus.LOADING, model.snapshot().status()),
                () -> assertEquals(1, executor.taskCount()),
                () -> assertEquals(2L,
                        reentrant.toCompletableFuture().join().contentRevision()));
        executor.runNext();
        model.close();
    }

    /// Verifies listener Errors and diagnostic-handler Errors cannot interrupt write progression.
    @Test
    public void listenerErrorsCannotBlockMutationSubmissionOrFutureCompletion() {
        Path pack = testPath("listener-error.zip");
        RecordingAccess access = new RecordingAccess(
                cancellation -> supportedIndex(List.of(pack)),
                DefaultResourcePackCatalogModelTest::itemsForPaths,
                (mutation, cancellation, commitPoint) -> {
                    commitPoint.run();
                    return new ResourcePackCatalogMutationAccessResult(
                            supportedIndex(List.of(pack)),
                            null);
                });
        ManualExecutor executor = new ManualExecutor();
        DefaultResourcePackCatalogModel model = model(access, executor);
        model.loadIfNeeded();
        executor.runNext();
        AtomicInteger diagnostics = new AtomicInteger();
        AtomicInteger healthyListenerCalls = new AtomicInteger();
        model.subscribe(change -> {
            throw new AssertionError("listener failure " + change.currentValue().writeStatus());
        });
        model.subscribe(change -> healthyListenerCalls.incrementAndGet());
        Thread currentThread = Thread.currentThread();
        @Nullable Thread.UncaughtExceptionHandler previousHandler =
                currentThread.getUncaughtExceptionHandler();

        try {
            currentThread.setUncaughtExceptionHandler((thread, failure) -> {
                diagnostics.incrementAndGet();
                throw new AssertionError("diagnostic handler failure", failure);
            });
            CompletionStage<ResourcePackCatalogSnapshot> completion =
                    model.enableResourcePack(pack);
            assertAll(
                    () -> assertEquals(ResourcePackCatalogWriteStatus.BUSY,
                            model.snapshot().writeStatus()),
                    () -> assertEquals(1, executor.taskCount()),
                    () -> assertFalse(completion.toCompletableFuture().isDone()));

            executor.runNext();
            ResourcePackCatalogSnapshot terminal = completion.toCompletableFuture().join();
            assertAll(
                    () -> assertEquals(ResourcePackCatalogWriteStatus.IDLE,
                            terminal.writeStatus()),
                    () -> assertEquals(2, diagnostics.get()),
                    () -> assertEquals(2, healthyListenerCalls.get()),
                    () -> assertEquals(1, access.mutationCalls()));
        } finally {
            currentThread.setUncaughtExceptionHandler(previousHandler);
            model.close();
        }
    }

    /// Verifies production access maps compatibility, enabled state, and isolated corrupt candidates.
    @Test
    public void productionAccessMapsRealLocalResourcePackState() throws IOException {
        Path corruptZip = temporaryDirectory.resolve("a-corrupt.zip");
        Files.writeString(corruptZip, "not a zip archive");
        Path compatibleDirectory = temporaryDirectory.resolve("b-compatible-enabled");
        Files.createDirectories(compatibleDirectory);
        Files.writeString(
                compatibleDirectory.resolve("pack.mcmeta"),
                "{\"pack\":{\"pack_format\":15,"
                        + "\"description\":\"Compatible pack\\nSecond line\"}}");
        Path tooOldDirectory = temporaryDirectory.resolve("c-too-old-disabled");
        Files.createDirectories(tooOldDirectory);
        Files.writeString(
                tooOldDirectory.resolve("pack.mcmeta"),
                "{\"pack\":{\"pack_format\":14,\"description\":\"Too old\"}}");
        writeVersionJar(temporaryDirectory.resolve("version.jar"));
        Files.writeString(
                temporaryDirectory.resolve("options.txt"),
                "resourcePacks:[\"file/b-compatible-enabled\"]\n"
                        + "incompatibleResourcePacks:[]\n");
        GameRepository repository = localRepository(temporaryDirectory);
        FileSystemResourcePackCatalogAccess access =
                new FileSystemResourcePackCatalogAccess(repository, "test-instance");
        LoadCancellation cancellation = new LoadCancellation();

        ResourcePackCatalogIndex index = access.loadIndex(cancellation);
        @Unmodifiable List<ResourcePackCatalogItem> items = access.loadItems(index.paths(), cancellation);

        assertAll(
                () -> assertEquals(List.of(corruptZip, compatibleDirectory, tooOldDirectory).stream()
                        .map(path -> path.toAbsolutePath().normalize())
                        .toList(), index.paths()),
                () -> assertEquals(3, items.size()),
                () -> assertEquals(ResourcePackCompatibility.INVALID, items.get(0).compatibility()),
                () -> assertEquals(corruptZip.toAbsolutePath().normalize(), items.get(0).path()),
                () -> assertEquals(ResourcePackCompatibility.COMPATIBLE,
                        items.get(1).compatibility()),
                () -> assertTrue(items.get(1).enabled()),
                () -> assertEquals("Compatible pack\nSecond line", items.get(1).description()),
                () -> assertEquals(ResourcePackCompatibility.TOO_OLD,
                        items.get(2).compatibility()),
                () -> assertFalse(items.get(2).enabled()));
    }

    /// Writes a minimal Minecraft version archive declaring resource-pack format 15.
    ///
    /// @param versionJar target archive path
    /// @throws IOException when the archive cannot be written
    private static void writeVersionJar(Path versionJar) throws IOException {
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(versionJar))) {
            output.putNextEntry(new ZipEntry("version.json"));
            output.write(("{\"pack_version\":{\"resource\":15,"
                    + "\"resource_major\":15,\"resource_minor\":0}}")
                    .getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }
    }

    /// Creates one model around an explicit test access and executor.
    ///
    /// @param access two-stage test access
    /// @param executor controlled executor
    /// @return idle catalog model
    private static DefaultResourcePackCatalogModel model(
            ResourcePackCatalogAccess access,
            Executor executor) {
        return new DefaultResourcePackCatalogModel(access, executor, STATUS_STRINGS);
    }

    /// Creates one shallow supported index.
    ///
    /// @param paths candidate paths
    /// @return supported source index
    private static ResourcePackCatalogIndex supportedIndex(
            @Unmodifiable List<Path> paths) {
        return new ResourcePackCatalogIndex(true, paths);
    }

    /// Creates one ordinary parsed row per exact supplied path.
    ///
    /// @param paths exact viewport paths
    /// @param cancellation cooperative cancellation
    /// @return rows in identical order
    private static @Unmodifiable List<ResourcePackCatalogItem> itemsForPaths(
            @Unmodifiable List<Path> paths,
            LoadCancellation cancellation) {
        return paths.stream().map(path -> {
            cancellation.throwIfCancelled();
            String fileName = requireNonNull(path.getFileName()).toString();
            return new ResourcePackCatalogItem(
                    path,
                    fileName,
                    fileName,
                    "description for " + fileName,
                    ResourcePackCompatibility.COMPATIBLE,
                    false);
        }).toList();
    }

    /// Loads one exact range using a manual executor.
    ///
    /// @param model ready model
    /// @param range desired range
    /// @param executor manual executor receiving the range task
    /// @return completed exact page
    private static ChoicePage<ResourcePackCatalogItem> loadDirect(
            DefaultResourcePackCatalogModel model,
            IndexRange range,
            ManualExecutor executor) {
        CompletionStage<ChoicePage<ResourcePackCatalogItem>> stage = model.load(
                range,
                new LoadCancellation());
        executor.runNext();
        return stage.toCompletableFuture().join();
    }

    /// Creates one normalized test path without touching disk.
    ///
    /// @param fileName final file name
    /// @return normalized absolute path
    private static Path testPath(String fileName) {
        return Path.of("build", "resource-pack-model-tests", fileName)
                .toAbsolutePath()
                .normalize();
    }

    /// Returns the underlying failure from one completed exceptional stage.
    ///
    /// @param stage exceptional stage
    /// @return underlying failure
    private static Throwable stageFailure(CompletionStage<?> stage) {
        try {
            stage.toCompletableFuture().join();
            throw new AssertionError("Expected stage failure");
        } catch (CancellationException failure) {
            return failure;
        } catch (CompletionException failure) {
            return requireNonNull(failure.getCause());
        }
    }

    /// Creates a non-networking repository proxy for production local access.
    ///
    /// @param root resource-pack and run directory
    /// @return local repository fixture
    private static GameRepository localRepository(Path root) {
        return (GameRepository) Proxy.newProxyInstance(
                GameRepository.class.getClassLoader(),
                new Class<?>[]{GameRepository.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "getResourcePackDirectory", "getRunDirectory" -> root;
                    case "getVersionJar" -> root.resolve("version.jar");
                    case "getGameVersion" -> Optional.of("1.20.1");
                    case "toString" -> "LocalResourcePackRepository";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == requireNonNull(arguments)[0];
                    default -> defaultValue(method.getReturnType());
                });
    }

    /// Returns a harmless default proxy value for an unused repository method.
    ///
    /// @param returnType reflected return type
    /// @return primitive zero, false, or null
    private static @Nullable Object defaultValue(Class<?> returnType) {
        if (returnType == boolean.class) {
            return false;
        }
        if (returnType == int.class) {
            return 0;
        }
        if (returnType == long.class) {
            return 0L;
        }
        return null;
    }

    /// Invokes one action synchronously on Swing EDT.
    ///
    /// @param action action to invoke
    private static void invokeOnEdt(Runnable action) {
        try {
            SwingUtilities.invokeAndWait(action);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while invoking Swing EDT", interrupted);
        } catch (InvocationTargetException failure) {
            throw new AssertionError("Swing EDT action failed", failure.getCause());
        }
    }

    /// Runs one task on a daemon worker and waits for completion.
    ///
    /// @param task worker task
    /// @param name diagnostic thread name
    private static void runOnWorker(Runnable task, String name) {
        Thread thread = daemonThread(name, task);
        thread.start();
        joinThread(thread, name + " did not finish");
    }

    /// Creates one unstarted daemon thread.
    ///
    /// @param name diagnostic thread name
    /// @param task thread task
    /// @return unstarted daemon thread
    private static Thread daemonThread(String name, Runnable task) {
        Thread thread = new Thread(task, name);
        thread.setDaemon(true);
        return thread;
    }

    /// Waits for one checkpoint with a finite bound.
    ///
    /// @param latch checkpoint signal
    /// @param timeoutMessage assertion message after timeout
    private static void awaitLatch(CountDownLatch latch, String timeoutMessage) {
        try {
            assertTrue(
                    latch.await(CONCURRENCY_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                    timeoutMessage);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while waiting for checkpoint", interrupted);
        }
    }

    /// Waits briefly to determine whether a barrier returned too early.
    ///
    /// @param latch completion signal
    /// @return whether completion occurred during the brief interval
    private static boolean awaitBriefly(CountDownLatch latch) {
        try {
            return latch.await(100L, TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while checking termination barrier", interrupted);
        }
    }

    /// Waits until one observable condition becomes true.
    ///
    /// @param condition condition to poll
    /// @param timeoutMessage assertion message after timeout
    private static void awaitCondition(BooleanSupplier condition, String timeoutMessage) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(CONCURRENCY_TIMEOUT_SECONDS);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertTrue(condition.getAsBoolean(), timeoutMessage);
    }

    /// Joins one daemon thread with a finite bound.
    ///
    /// @param thread thread to join
    /// @param timeoutMessage assertion message when it remains alive
    private static void joinThread(Thread thread, String timeoutMessage) {
        try {
            thread.join(TimeUnit.SECONDS.toMillis(CONCURRENCY_TIMEOUT_SECONDS));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while joining test thread", interrupted);
        }
        assertFalse(thread.isAlive(), timeoutMessage);
    }

    /// Returns one non-null value or fails immediately.
    ///
    /// @param value nullable source value
    /// @param <T> source type
    /// @return non-null source value
    private static <T extends Object> T requireNonNull(@Nullable T value) {
        if (value == null) {
            throw new AssertionError("Expected non-null test value");
        }
        return value;
    }

    /// Blocking shallow-index test function.
    @FunctionalInterface
    @NotNullByDefault
    private interface IndexLoader {
        /// Loads one test index.
        ///
        /// @param cancellation cooperative cancellation
        /// @return source index
        /// @throws IOException test I/O failure
        ResourcePackCatalogIndex load(
                LoadCancellation cancellation) throws IOException;
    }

    /// Blocking exact-items test function.
    @FunctionalInterface
    @NotNullByDefault
    private interface ItemLoader {
        /// Loads exact test paths.
        ///
        /// @param paths exact requested paths
        /// @param cancellation cooperative cancellation
        /// @return exact ordered rows
        /// @throws IOException test I/O failure
        @Unmodifiable List<ResourcePackCatalogItem> load(
                @Unmodifiable List<Path> paths,
                LoadCancellation cancellation) throws IOException;
    }

    /// Blocking write-and-rescan test function.
    @FunctionalInterface
    @NotNullByDefault
    private interface MutationLoader {
        /// Applies one test mutation and returns its mandatory refreshed index.
        ///
        /// @param mutation requested mutation
        /// @param cancellation cooperative pre-commit cancellation
        /// @param commitPoint irreversible commit callback
        /// @return refreshed index and optional mutation failure
        /// @throws IOException test I/O failure
        ResourcePackCatalogMutationAccessResult load(
                ResourcePackCatalogMutationRequest mutation,
                LoadCancellation cancellation,
                Runnable commitPoint) throws IOException;
    }

    /// Viewport listener counting accepted late successes and failures.
    @NotNullByDefault
    private static final class CountingViewportListener
            implements ViewportLoadListener<ResourcePackCatalogItem> {
        /// Number of accepted successful pages.
        private int loadedCount;

        /// Number of accepted failed pages.
        private int failedCount;

        /// Ignores loading geometry for the revision-rejection test.
        ///
        /// @param generation request generation
        /// @param ranges requested ranges
        @Override
        public void loading(long generation, @Unmodifiable List<IndexRange> ranges) {
        }

        /// Counts one accepted successful page.
        ///
        /// @param generation request generation
        /// @param requestedRange requested range
        /// @param page accepted source page
        @Override
        public void loaded(
                long generation,
                IndexRange requestedRange,
                ChoicePage<ResourcePackCatalogItem> page) {
            loadedCount++;
        }

        /// Counts one accepted failed page.
        ///
        /// @param generation request generation
        /// @param requestedRange requested range
        /// @param failure accepted source failure
        @Override
        public void failed(
                long generation,
                IndexRange requestedRange,
                Throwable failure) {
            failedCount++;
        }

        /// Ignores latency observations for the revision-rejection test.
        ///
        /// @param latency observed request latency
        @Override
        public void latencyObserved(Duration latency) {
        }

        /// Returns accepted success count.
        ///
        /// @return accepted success count
        private int loadedCount() {
            return loadedCount;
        }

        /// Returns accepted failure count.
        ///
        /// @return accepted failure count
        private int failedCount() {
            return failedCount;
        }
    }

    /// Two-stage access that records every invocation and exact item request.
    @NotNullByDefault
    private static final class RecordingAccess implements ResourcePackCatalogAccess {
        /// Injected shallow-index behavior.
        private final IndexLoader indexLoader;

        /// Injected exact-items behavior.
        private final ItemLoader itemLoader;

        /// Injected mutation and mandatory-rescan behavior.
        private final MutationLoader mutationLoader;

        /// Number of shallow index calls.
        private final AtomicInteger indexCalls = new AtomicInteger();

        /// Number of exact item calls.
        private final AtomicInteger itemCalls = new AtomicInteger();

        /// Number of serialized mutation calls.
        private final AtomicInteger mutationCalls = new AtomicInteger();

        /// Immutable path requests in invocation order.
        private final List<@Unmodifiable List<Path>> requestedPaths = new ArrayList<>();

        /// Creates one recording access.
        ///
        /// @param indexLoader shallow-index behavior
        /// @param itemLoader exact-items behavior
        private RecordingAccess(IndexLoader indexLoader, ItemLoader itemLoader) {
            this(
                    indexLoader,
                    itemLoader,
                    (mutation, cancellation, commitPoint) -> {
                        throw new UnsupportedOperationException(
                                "Mutation behavior was not configured");
                    });
        }

        /// Creates one recording access with explicit mutation behavior.
        ///
        /// @param indexLoader shallow-index behavior
        /// @param itemLoader exact-items behavior
        /// @param mutationLoader mutation and mandatory-rescan behavior
        private RecordingAccess(
                IndexLoader indexLoader,
                ItemLoader itemLoader,
                MutationLoader mutationLoader) {
            this.indexLoader = indexLoader;
            this.itemLoader = itemLoader;
            this.mutationLoader = mutationLoader;
        }

        /// Records and delegates one shallow index call.
        @Override
        public ResourcePackCatalogIndex loadIndex(
                LoadCancellation cancellation) throws IOException {
            indexCalls.incrementAndGet();
            return indexLoader.load(cancellation);
        }

        /// Records exact paths and delegates one item call.
        @Override
        public synchronized @Unmodifiable List<ResourcePackCatalogItem> loadItems(
                @Unmodifiable List<Path> paths,
                LoadCancellation cancellation) throws IOException {
            itemCalls.incrementAndGet();
            requestedPaths.add(List.copyOf(paths));
            return itemLoader.load(paths, cancellation);
        }

        /// Records and delegates one serialized mutation.
        ///
        /// @param mutation requested write
        /// @param cancellation cooperative cancellation
        /// @param commitPoint irreversible commit callback
        /// @return refreshed index and optional mutation failure
        /// @throws IOException configured test I/O failure
        @Override
        public ResourcePackCatalogMutationAccessResult mutateAndLoadIndex(
                ResourcePackCatalogMutationRequest mutation,
                LoadCancellation cancellation,
                Runnable commitPoint) throws IOException {
            mutationCalls.incrementAndGet();
            return mutationLoader.load(mutation, cancellation, commitPoint);
        }

        /// Returns shallow index call count.
        ///
        /// @return index call count
        private int indexCalls() {
            return indexCalls.get();
        }

        /// Returns exact item call count.
        ///
        /// @return item call count
        private int itemCalls() {
            return itemCalls.get();
        }

        /// Returns serialized mutation call count.
        ///
        /// @return mutation call count
        private int mutationCalls() {
            return mutationCalls.get();
        }

        /// Returns immutable copies of exact path requests.
        ///
        /// @return path requests
        private synchronized @Unmodifiable List<@Unmodifiable List<Path>> requestedPaths() {
            return requestedPaths.stream().map(List::copyOf).toList();
        }
    }

    /// FIFO executor whose queued tasks are released explicitly.
    @NotNullByDefault
    private static final class ManualExecutor implements Executor {
        /// Tasks captured in scheduling order.
        private final List<Runnable> tasks = new ArrayList<>();

        /// Captures one task without running it.
        @Override
        public synchronized void execute(Runnable command) {
            tasks.add(command);
        }

        /// Removes and returns the oldest task.
        ///
        /// @return oldest queued task
        private synchronized Runnable takeNext() {
            if (tasks.isEmpty()) {
                throw new AssertionError("No executor task was queued");
            }
            return tasks.remove(0);
        }

        /// Runs the oldest task on the calling thread.
        private void runNext() {
            takeNext().run();
        }

        /// Returns queued task count.
        ///
        /// @return queued task count
        private synchronized int taskCount() {
            return tasks.size();
        }
    }

    /// Executor that blocks its first range submission while retaining deterministic queued work.
    @NotNullByDefault
    private static final class BlockingRangeSubmissionExecutor implements Executor {
        /// Submitted tasks retained in order.
        private final List<Runnable> tasks = new ArrayList<>();

        /// Signals entry into the first range submission.
        private final CountDownLatch blockedSubmission = new CountDownLatch(1);

        /// Releases the blocked first range submission.
        private final CountDownLatch releaseSubmission = new CountDownLatch(1);

        /// Number of index and range submissions seen.
        private final AtomicInteger submissions = new AtomicInteger();

        /// Blocks submission number two, then queues every accepted command.
        @Override
        public void execute(Runnable command) {
            if (submissions.incrementAndGet() == 2) {
                blockedSubmission.countDown();
                awaitLatch(releaseSubmission, "blocked range submission was not released");
            }
            synchronized (tasks) {
                tasks.add(command);
            }
        }

        /// Waits until the first range owns the model's submission lock.
        private void awaitBlockedSubmission() {
            awaitLatch(blockedSubmission, "first range submission did not block");
        }

        /// Allows the first range submission to enqueue and return.
        private void releaseBlockedSubmission() {
            releaseSubmission.countDown();
        }

        /// Runs the oldest queued task.
        private void runNext() {
            Runnable task;
            synchronized (tasks) {
                if (tasks.isEmpty()) {
                    throw new AssertionError("No executor task was queued");
                }
                task = tasks.remove(0);
            }
            task.run();
        }

        /// Returns the current queued task count.
        ///
        /// @return queued task count
        private int taskCount() {
            synchronized (tasks) {
                return tasks.size();
            }
        }
    }

    /// Direct executor that can switch to deterministic rejection after indexing.
    @NotNullByDefault
    private static final class SwitchableExecutor implements Executor {
        /// Rejection installed after successful direct work, or null while accepting.
        private @Nullable RejectedExecutionException rejection;

        /// Runs directly unless rejection has been installed.
        @Override
        public void execute(Runnable command) {
            @Nullable RejectedExecutionException current = rejection;
            if (current != null) {
                throw current;
            }
            command.run();
        }

        /// Installs the rejection used by later submissions.
        ///
        /// @param failure rejection to throw
        private void reject(RejectedExecutionException failure) {
            rejection = failure;
        }
    }
}
