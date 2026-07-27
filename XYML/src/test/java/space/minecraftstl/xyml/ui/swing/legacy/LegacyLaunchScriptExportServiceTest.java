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
package space.minecraftstl.xyml.ui.swing.legacy;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import space.minecraftstl.xyml.game.launch.LaunchRequest;
import space.minecraftstl.xyml.task.Task;

import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests stable request capture and close cancellation for the local script-export task service.
@NotNullByDefault
public final class LegacyLaunchScriptExportServiceTest {
    /// Uses the exact task result path and immutable selection supplied to the service boundary.
    @Test
    public void exportsCapturedScriptTaskResult() throws Exception {
        AtomicReference<@Nullable LaunchRequest> capturedRequest = new AtomicReference<>();
        AtomicReference<@Nullable Path> capturedTarget = new AtomicReference<>();
        LegacyLaunchScriptExportService service = new LegacyLaunchScriptExportService(
                (request, scriptFile) -> {
                    capturedRequest.set(request);
                    capturedTarget.set(scriptFile);
                    return Task.completed(scriptFile);
                },
                Runnable::run);
        LaunchRequest request = new LaunchRequest("account-a", "directory-a", "instance-a");
        Path target = Path.of("build", "legacy-export-service-test.bat").toAbsolutePath().normalize();
        try {
            CompletionStage<Path> completion = service.export(request, target);
            assertAll(
                    () -> assertEquals(target, completion.toCompletableFuture().get(5, TimeUnit.SECONDS)),
                    () -> assertEquals(request, capturedRequest.get()),
                    () -> assertEquals(target, capturedTarget.get()));
        } finally {
            service.close();
        }
    }

    /// Closing a queued export cancels its result without constructing or starting the legacy task.
    @Test
    public void closeCancelsQueuedExportBeforeTaskConstruction() {
        AtomicReference<@Nullable Runnable> queuedCommand = new AtomicReference<>();
        AtomicBoolean taskBuilderCalled = new AtomicBoolean();
        LegacyLaunchScriptExportService service = new LegacyLaunchScriptExportService(
                (request, scriptFile) -> {
                    taskBuilderCalled.set(true);
                    return Task.completed(scriptFile);
                },
                command -> queuedCommand.set(command));
        Path target = Path.of("build", "legacy-export-service-cancelled.bat").toAbsolutePath().normalize();
        CompletionStage<Path> completion = service.export(
                new LaunchRequest("account-a", "directory-a", "instance-a"),
                target);
        CompletableFuture<Path> exposedCompletion = completion.toCompletableFuture();
        service.close();
        Objects.requireNonNull(queuedCommand.get(), "queued export command").run();

        assertAll(
                () -> assertThrows(CancellationException.class, exposedCompletion::join),
                () -> assertTrue(exposedCompletion.isCancelled()),
                () -> assertFalse(taskBuilderCalled.get()));
    }
}
