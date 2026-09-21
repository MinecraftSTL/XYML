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
import space.minecraftstl.xyml.util.io.TrashMoveException;

import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
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
