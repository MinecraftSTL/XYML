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
