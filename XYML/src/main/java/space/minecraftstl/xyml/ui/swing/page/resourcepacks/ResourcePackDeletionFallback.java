// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.resourcepacks;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;
import space.minecraftstl.xyml.util.io.DeletionBatchException;
import space.minecraftstl.xyml.util.io.DeletionMode;
import space.minecraftstl.xyml.util.io.TrashMoveException;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/// Coordinates the recycle-bin-first failure boundary for one resource-pack deletion.
@NotNullByDefault
final class ResourcePackDeletionFallback {
    /// Prevents utility construction.
    private ResourcePackDeletionFallback() {
    }

    /// Starts a deletion and releases the write gate before a three-way fallback decision.
    ///
    /// @param operation exact current deletion
    /// @param allowFallback whether recycle-bin failures allow recovery
    /// @param fallback callback receiving the actual failed targets
    /// @param canStart current panel write predicate
    /// @param beginWrite enters the panel busy state
    /// @param clearWriteGate releases the panel busy state
    /// @param onFailure receives ordinary failures or null on success
    static void startWithFallback(
            Supplier<CompletionStage<ResourcePackCatalogSnapshot>> operation,
            boolean allowFallback,
            Consumer<TrashMoveException> fallback,
            BooleanSupplier canStart,
            Runnable beginWrite,
            Runnable clearWriteGate,
            Consumer<@Nullable Throwable> onFailure) {
        EdtDispatcher.requireEventDispatchThread();
        if (!canStart.getAsBoolean()) {
            return;
        }
        beginWrite.run();
        CompletionStage<ResourcePackCatalogSnapshot> stage;
        try {
            stage = Objects.requireNonNull(operation.get(), "resource-pack deletion returned null");
        } catch (RuntimeException failure) {
            stage = CompletableFuture.failedFuture(failure);
        } catch (Error failure) {
            clearWriteGate.run();
            throw failure;
        }
        stage.whenComplete((@Nullable ResourcePackCatalogSnapshot ignored, @Nullable Throwable failure) -> {
            Runnable complete = () -> {
                @Nullable Throwable resolved = failure == null ? null : unwrapFailure(failure);
                if (allowFallback && resolved instanceof TrashMoveException trashFailure) {
                    clearWriteGate.run();
                    fallback.accept(trashFailure);
                } else {
                    onFailure.accept(resolved);
                }
            };
            if (failure == null) {
                EdtDispatcher.execute(complete);
            } else {
                EdtDispatcher.executeLater(complete);
            }
        });
    }

    /// Retains only captured targets that appear in the normalized failed-path set.
    ///
    /// @param paths immutable current deletion targets
    /// @param failure actual failed recycle-bin targets
    /// @return immutable failed subset in original target order
    static @Unmodifiable List<Path> failedPaths(@Unmodifiable List<Path> paths, TrashMoveException failure) {
        return paths.stream().filter(path -> failure.failedPaths().stream().anyMatch(failed ->
                failed.toAbsolutePath().normalize().equals(path.toAbsolutePath().normalize()))).toList();
    }

    /// Deletes paths serially while attempting every target and aggregating all failures.
    ///
    /// @param paths immutable deletion targets
    /// @param mode selected deletion behavior
    /// @param deletion one-path asynchronous deletion
    /// @param snapshot current snapshot supplier
    /// @return terminal stage after every target or the aggregate failure
    static CompletionStage<ResourcePackCatalogSnapshot> deleteSerially(
            List<Path> paths,
            DeletionMode mode,
            Function<Path, CompletionStage<ResourcePackCatalogSnapshot>> deletion,
            Supplier<ResourcePackCatalogSnapshot> snapshot) {
        return deleteSerially(
                List.copyOf(Objects.requireNonNull(paths, "paths")),
                0,
                Objects.requireNonNull(mode, "mode"),
                Objects.requireNonNull(deletion, "deletion"),
                Objects.requireNonNull(snapshot, "snapshot"),
                new ArrayList<>(),
                new ArrayList<>());
    }

    /// Recursively attempts the next path and retains every encountered failure.
    ///
    /// @param paths immutable deletion targets
    /// @param index next target index
    /// @param mode selected deletion behavior
    /// @param deletion one-path asynchronous deletion
    /// @param snapshot current snapshot supplier
    /// @param failedPaths accumulated failed targets
    /// @param failures accumulated individual failures
    /// @return terminal stage
    private static CompletionStage<ResourcePackCatalogSnapshot> deleteSerially(
            List<Path> paths,
            int index,
            DeletionMode mode,
            Function<Path, CompletionStage<ResourcePackCatalogSnapshot>> deletion,
            Supplier<ResourcePackCatalogSnapshot> snapshot,
            List<Path> failedPaths,
            List<IOException> failures) {
        if (index == paths.size()) {
            if (failedPaths.isEmpty()) {
                return CompletableFuture.completedFuture(snapshot.get());
            }
            if (mode == DeletionMode.RECYCLE_BIN_FIRST
                    && failures.stream().allMatch(TrashMoveException.class::isInstance)) {
                TrashMoveException aggregate = new TrashMoveException(failedPaths);
                addSuppressedFailures(aggregate, failures);
                return CompletableFuture.failedFuture(aggregate);
            }
            DeletionBatchException aggregate = new DeletionBatchException(failedPaths);
            addSuppressedFailures(aggregate, failures);
            return CompletableFuture.failedFuture(aggregate);
        }
        Path path = paths.get(index);
        return Objects.requireNonNull(deletion.apply(path), "deletion returned null")
                .handle((@Nullable ResourcePackCatalogSnapshot ignored, @Nullable Throwable failure) -> {
                    if (failure == null) {
                        return ignored;
                    }
                    Throwable resolved = unwrapFailure(failure);
                    if (resolved instanceof IOException ioFailure) {
                        failedPaths.add(path);
                        failures.add(ioFailure);
                        return null;
                    }
                    throw new CompletionException(resolved);
                })
                .thenCompose(ignored -> deleteSerially(
                        paths,
                        index + 1,
                        mode,
                        deletion,
                        snapshot,
                        failedPaths,
                        failures));
    }

    /// Adds individual failures without changing the aggregate public contract.
    ///
    /// @param aggregate aggregate failure
    /// @param failures individual failures
    private static void addSuppressedFailures(
            IOException aggregate,
            List<? extends IOException> failures) {
        for (IOException failure : failures) {
            if (failure != aggregate) {
                aggregate.addSuppressed(failure);
            }
        }
    }

    /// Unwraps one completion exception when present.
    ///
    /// @param failure observed operation failure
    /// @return original failure
    private static Throwable unwrapFailure(Throwable failure) {
        if (failure instanceof java.util.concurrent.CompletionException && failure.getCause() != null) {
            return failure.getCause();
        }
        return failure;
    }
}
