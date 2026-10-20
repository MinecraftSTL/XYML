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
