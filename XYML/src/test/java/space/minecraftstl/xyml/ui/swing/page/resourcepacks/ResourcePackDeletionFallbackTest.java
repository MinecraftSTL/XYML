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
