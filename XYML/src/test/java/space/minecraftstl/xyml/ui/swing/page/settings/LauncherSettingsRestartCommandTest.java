// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.settings;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import javax.swing.JPanel;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Verifies settings restart lifecycle ordering without starting a real launcher process.
@NotNullByDefault
public final class LauncherSettingsRestartCommandTest {
    /// Executor that runs injected worker and UI actions deterministically on the caller thread.
    private static final Executor DIRECT_EXECUTOR = Runnable::run;

    /// A successful restart waits for persistence, starts the replacement, and only then closes the owner.
    @Test
    public void waitsStartsAndClosesInStrictOrder() {
        List<String> events = new ArrayList<>();
        JPanel owner = new JPanel();
        LauncherSettingsRestartCommand command = new LauncherSettingsRestartCommand(
                DIRECT_EXECUTOR,
                DIRECT_EXECUTOR,
                () -> events.add("wait"),
                () -> events.add("start"),
                component -> {
                    assertSame(owner, component);
                    events.add("close");
                });

        command.restart(owner).toCompletableFuture().join();

        assertEquals(List.of("wait", "start", "close"), events);
    }

    /// A replacement-process failure keeps the current window open and preserves the original failure.
    @Test
    public void processStartFailureDoesNotCloseCurrentWindow() {
        List<String> events = new ArrayList<>();
        IOException expected = new IOException("replacement failed");
        LauncherSettingsRestartCommand command = new LauncherSettingsRestartCommand(
                DIRECT_EXECUTOR,
                DIRECT_EXECUTOR,
                () -> events.add("wait"),
                () -> {
                    events.add("start");
                    throw expected;
                },
                component -> events.add("close"));

        CompletionException failure = assertThrows(
                CompletionException.class,
                () -> command.restart(new JPanel()).toCompletableFuture().join());

        assertSame(expected, failure.getCause());
        assertEquals(List.of("wait", "start"), events);
    }
}
