// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.instances.importing;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import space.minecraftstl.xyml.task.Task;
import space.minecraftstl.xyml.task.TaskResource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies resource-safe orchestration inside repository-backed instance JSON imports.
@NotNullByDefault
final class RepositoryInstanceJsonImportServiceTest {
    /// Optional download failure remains non-fatal without introducing a conservative continuation.
    @Test
    void optionalDownloadFailureUsesOrchestrationBarrier() {
        Task<@Nullable Void> optionalDownloads = Task.runAsync(
                Runnable::run,
                () -> {
                    throw new IllegalStateException("optional download failed");
                }).asOrchestration();

        Task<@Nullable Void> barrier = RepositoryInstanceJsonImportService.ignoreOptionalDownloadFailure(
                optionalDownloads,
                Runnable::run);

        assertEquals(1, barrier.getResources().size());
        assertEquals(TaskResource.Kind.ORCHESTRATION, barrier.getResources().iterator().next().getKind());
        assertTrue(barrier.executor().test());
    }
}
