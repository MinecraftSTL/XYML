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

    /// Observes one deletion and retries permanently after an approved recycle-bin fallback warning.
    ///
    /// @param initial initial deletion stage
    /// @param permanentRetry permanent deletion retry
    /// @param confirmFallback original-warning confirmation
    /// @param allowFallback whether recycle-bin failure may prompt again
    /// @param clearWriteGate callback clearing the panel write gate
    /// @param onFailure callback reporting a non-fallback failure
    static void observe(
            CompletionStage<ResourcePackCatalogSnapshot> initial,
            Supplier<CompletionStage<ResourcePackCatalogSnapshot>> permanentRetry,
            BooleanSupplier confirmFallback,
            boolean allowFallback,
            Runnable clearWriteGate,
            Consumer<Throwable> onFailure) {
        Objects.requireNonNull(initial, "initial").whenComplete((
                @Nullable ResourcePackCatalogSnapshot ignored,
                @Nullable Throwable failure) -> EdtDispatcher.execute(() -> {
            if (failure == null) {
                onFailure.accept(null);
                return;
            }
            Throwable resolved = unwrapFailure(failure);
            if (allowFallback && resolved instanceof TrashMoveException) {
                clearWriteGate.run();
                if (confirmFallback.getAsBoolean()) {
                    observe(
                            permanentRetry.get(),
                            permanentRetry,
                            confirmFallback,
                            false,
                            clearWriteGate,
                            onFailure);
                }
                return;
            }
            onFailure.accept(failure);
        }));
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
            if (mode == DeletionMode.RECYCLE_BIN_FIRST) {
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
