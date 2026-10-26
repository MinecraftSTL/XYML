// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.runtime;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import space.minecraftstl.xyml.game.GameInstanceID;
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

/// Verifies stable request capture and close cancellation for the local script-export task service.
@NotNullByDefault
public final class LaunchScriptExportServiceTest {
    /// Uses the exact task result path and immutable selection supplied to the service boundary.
    @Test
    public void exportsCapturedScriptTaskResult() throws Exception {
        AtomicReference<@Nullable LaunchRequest> capturedRequest = new AtomicReference<>();
        AtomicReference<@Nullable Path> capturedTarget = new AtomicReference<>();
        LaunchScriptExportService service = new LaunchScriptExportService(
                (request, scriptFile) -> {
                    capturedRequest.set(request);
                    capturedTarget.set(scriptFile);
                    return Task.completed(scriptFile);
                },
                Runnable::run);
        LaunchRequest request = new LaunchRequest(
                "account-a", "directory-a", new GameInstanceID("instance-a"));
        Path target = Path.of("build", "launcher-export-service-test.bat").toAbsolutePath().normalize();
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

    /// Closing a queued export cancels its result without constructing or starting the launcher task.
    @Test
    public void closeCancelsQueuedExportBeforeTaskConstruction() {
        AtomicReference<@Nullable Runnable> queuedCommand = new AtomicReference<>();
        AtomicBoolean taskBuilderCalled = new AtomicBoolean();
        LaunchScriptExportService service = new LaunchScriptExportService(
                (request, scriptFile) -> {
                    taskBuilderCalled.set(true);
                    return Task.completed(scriptFile);
                },
                command -> queuedCommand.set(command));
        Path target = Path.of("build", "launcher-export-service-cancelled.bat").toAbsolutePath().normalize();
        CompletionStage<Path> completion = service.export(
                new LaunchRequest(
                        "account-a", "directory-a", new GameInstanceID("instance-a")),
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
