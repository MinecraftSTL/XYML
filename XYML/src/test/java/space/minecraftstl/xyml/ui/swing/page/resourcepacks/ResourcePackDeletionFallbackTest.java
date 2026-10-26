// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.resourcepacks;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import space.minecraftstl.xyml.util.io.DeletionMode;
import space.minecraftstl.xyml.util.io.TrashMoveException;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Verifies that serial resource-pack deletion attempts every target and aggregates failures.
@NotNullByDefault
final class ResourcePackDeletionFallbackTest {
    /// A failed middle target does not prevent the final target from being attempted.
    @Test
    void attemptsEveryTargetAndAggregatesFailure() {
        Path first = Path.of("first.zip").toAbsolutePath().normalize();
        Path failed = Path.of("failed.zip").toAbsolutePath().normalize();
        Path last = Path.of("last.zip").toAbsolutePath().normalize();
        List<Path> attempts = new ArrayList<>();
        ResourcePackCatalogSnapshot snapshot = snapshot();
        CompletionStage<ResourcePackCatalogSnapshot> result = ResourcePackDeletionFallback.deleteSerially(
                List.of(first, failed, last),
                DeletionMode.RECYCLE_BIN_FIRST,
                path -> {
                    attempts.add(path);
                    if (path.equals(failed)) {
                        return CompletableFuture.failedFuture(new TrashMoveException(List.of(path)));
                    }
                    return CompletableFuture.completedFuture(snapshot);
                },
                () -> snapshot);

        CompletionException completion = assertThrows(
                CompletionException.class,
                () -> result.toCompletableFuture().join());
        TrashMoveException failure = assertInstanceOf(TrashMoveException.class, completion.getCause());

        assertEquals(List.of(first, failed, last), attempts);
        assertEquals(List.of(failed), failure.failedPaths());
    }

    /// Creates one valid terminal snapshot for deterministic stages.
    ///
    /// @return empty ready snapshot
    private static ResourcePackCatalogSnapshot snapshot() {
        return new ResourcePackCatalogSnapshot(
                OptionalInt.empty(),
                OptionalInt.of(0),
                0L,
                ResourcePackCatalogStatus.READY,
                "",
                ResourcePackCatalogWriteStatus.IDLE,
                "",
                false,
                false);
    }
}
