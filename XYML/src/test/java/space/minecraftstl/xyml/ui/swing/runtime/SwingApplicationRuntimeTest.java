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
package space.minecraftstl.xyml.ui.swing.runtime;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import space.minecraftstl.xyml.ui.swing.application.SwingApplicationCommands;
import space.minecraftstl.xyml.ui.swing.page.accounts.AccountRefreshCommand;
import space.minecraftstl.xyml.ui.swing.page.home.HomeLaunchScriptExportCommand;

import javax.swing.JPanel;
import java.awt.Component;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies the Swing runtime ownership protocol without creating native windows.
@NotNullByDefault
final class SwingApplicationRuntimeTest {
    /// Confirms that each open call is delegated exactly once to the composition lifecycle.
    @Test
    void delegatesOpenExactlyToComposition() {
        List<String> events = new ArrayList<>();
        RecordingLifecycle composition = new RecordingLifecycle(events, "composition", null, null);
        RecordingCloseable commandOwner = new RecordingCloseable(events, "commands", null);
        SwingApplicationRuntime runtime = createRuntime(composition, commandOwner, () -> { });

        runtime.open();
        runtime.open();

        assertEquals(2, composition.openCount());
        assertFalse(runtime.isClosed());
        runtime.close();
    }

    /// Confirms that hide requests preserve the runtime and delegate exactly to the composition.
    @Test
    void delegatesHideWithoutClosingComposition() {
        List<String> events = new ArrayList<>();
        RecordingLifecycle composition = new RecordingLifecycle(events, "composition", null, null);
        RecordingCloseable commandOwner = new RecordingCloseable(events, "commands", null);
        SwingApplicationRuntime runtime = createRuntime(composition, commandOwner, () -> { });

        runtime.open();
        runtime.hide();

        assertEquals(1, composition.openCount());
        assertEquals(1, composition.hideCount());
        assertFalse(runtime.isClosed());
        runtime.close();
    }

    /// Confirms that dialog ownership and agreement-gate interaction state delegate to the composition.
    @Test
    void delegatesDialogOwnerAndInteractionState() {
        List<String> events = new ArrayList<>();
        RecordingLifecycle composition = new RecordingLifecycle(events, "composition", null, null);
        RecordingCloseable commandOwner = new RecordingCloseable(events, "commands", null);
        SwingApplicationRuntime runtime = createRuntime(composition, commandOwner, () -> { });

        assertSame(composition.dialogOwner(), runtime.dialogOwner());
        runtime.setInteractionEnabled(false);
        assertFalse(composition.interactionEnabled());
        runtime.setInteractionEnabled(true);
        assertTrue(composition.interactionEnabled());

        runtime.close();
    }

    /// Confirms that process-exit show and hide callbacks cannot revive a closed runtime.
    @Test
    void lateVisibilityCallbacksDoNotReviveClosedRuntime() {
        List<String> events = new ArrayList<>();
        RecordingLifecycle composition = new RecordingLifecycle(events, "composition", null, null);
        RecordingCloseable commandOwner = new RecordingCloseable(events, "commands", null);
        AtomicReference<@Nullable LaunchVisibilityActions> actionsReference = new AtomicReference<>();
        SwingApplicationRuntime runtime = SwingApplicationRuntime.createForCollaborators(
                visibilityActions -> {
                    actionsReference.set(visibilityActions);
                    return new SwingApplicationRuntime.CommandOwnerHandle(commands(), commandOwner);
                },
                (applicationCommands, closeCommand) -> composition,
                () -> events.add("exit"));
        runtime.open();
        LaunchVisibilityActions actions = Objects.requireNonNull(actionsReference.get());
        actions.hide().run();

        runtime.close();
        actions.show().run();
        actions.hide().run();

        assertEquals(1, composition.openCount());
        assertEquals(1, composition.hideCount());
        assertEquals(List.of("composition", "commands", "exit"), events);
    }

    /// Confirms that a visibility callback never holds a runtime lock while waiting on window work.
    @Test
    void closeCanCompleteWhileHideWaitsForWindowCallback() throws Exception {
        AtomicReference<@Nullable LaunchVisibilityActions> actionsReference = new AtomicReference<>();
        CloseDuringHideLifecycle composition = new CloseDuringHideLifecycle();
        SwingApplicationRuntime runtime = SwingApplicationRuntime.createForCollaborators(
                visibilityActions -> {
                    actionsReference.set(visibilityActions);
                    return new SwingApplicationRuntime.CommandOwnerHandle(
                            commands(),
                            () -> { });
                },
                (applicationCommands, closeCommand) -> composition,
                () -> { });

        CompletableFuture<Void> hideFuture = CompletableFuture.runAsync(
                Objects.requireNonNull(actionsReference.get()).hide());
        assertTrue(composition.awaitHideEntered());
        CompletableFuture<Void> closeFuture = CompletableFuture.runAsync(runtime::close);

        closeFuture.get(2L, TimeUnit.SECONDS);
        hideFuture.get(2L, TimeUnit.SECONDS);
        assertTrue(runtime.isClosed());
    }

    /// Confirms ordered cleanup, idempotence, and reentrant composition close notification handling.
    @Test
    void closesInOrderOnceWhenCompositionNotifiesReentrantly() {
        List<String> events = new ArrayList<>();
        AtomicReference<@Nullable RecordingLifecycle> compositionReference = new AtomicReference<>();
        RecordingCloseable commandOwner = new RecordingCloseable(events, "commands", null);

        SwingApplicationRuntime runtime = SwingApplicationRuntime.createForCollaborators(
                closeCommand -> new SwingApplicationRuntime.CommandOwnerHandle(
                        commands(),
                        commandOwner),
                (applicationCommands, closeCommand) -> {
                    RecordingLifecycle composition = new RecordingLifecycle(
                            events,
                            "composition",
                            closeCommand,
                            null);
                    compositionReference.set(composition);
                    return composition;
                },
                () -> events.add("exit"));

        runtime.close();
        runtime.close();

        assertEquals(List.of("composition", "commands", "exit"), events);
        assertEquals(1, Objects.requireNonNull(compositionReference.get()).closeCount());
        assertEquals(1, commandOwner.closeCount());
        assertTrue(runtime.isClosed());
    }

    /// Confirms that a native-close relay attached to the composition closes the same runtime.
    @Test
    void nativeCompositionCloseRelayClosesRuntime() {
        List<String> events = new ArrayList<>();
        AtomicReference<@Nullable Runnable> nativeCloseRelay = new AtomicReference<>();
        RecordingLifecycle composition = new RecordingLifecycle(events, "composition", null, null);
        RecordingCloseable commandOwner = new RecordingCloseable(events, "commands", null);

        SwingApplicationRuntime runtime = SwingApplicationRuntime.createForCollaborators(
                closeCommand -> new SwingApplicationRuntime.CommandOwnerHandle(
                        commands(),
                        commandOwner),
                (applicationCommands, closeCommand) -> {
                    nativeCloseRelay.set(closeCommand);
                    return composition;
                },
                () -> events.add("exit"));

        Objects.requireNonNull(nativeCloseRelay.get()).run();
        runtime.close();

        assertEquals(List.of("composition", "commands", "exit"), events);
        assertTrue(runtime.isClosed());
    }

    /// Confirms that a close request arriving before runtime attachment is delivered afterward.
    @Test
    void deliversCloseRequestedDuringAttachment() {
        List<String> events = new ArrayList<>();
        RecordingLifecycle composition = new RecordingLifecycle(events, "composition", null, null);
        RecordingCloseable commandOwner = new RecordingCloseable(events, "commands", null);

        SwingApplicationRuntime runtime = SwingApplicationRuntime.createForCollaborators(
                closeCommand -> new SwingApplicationRuntime.CommandOwnerHandle(
                        commands(),
                        commandOwner),
                (applicationCommands, closeCommand) -> {
                    closeCommand.run();
                    return composition;
                },
                () -> events.add("exit"));

        assertTrue(runtime.isClosed());
        assertEquals(List.of("composition", "commands", "exit"), events);
        runtime.close();
        assertEquals(List.of("composition", "commands", "exit"), events);
    }

    /// Confirms that all cleanup steps run and later failures are suppressed in ownership order.
    @Test
    void aggregatesCloseFailuresInOwnershipOrder() {
        List<String> events = new ArrayList<>();
        RuntimeException compositionFailure = new RuntimeException("composition failure");
        RuntimeException commandFailure = new RuntimeException("command failure");
        RuntimeException exitFailure = new RuntimeException("exit failure");
        RecordingLifecycle composition = new RecordingLifecycle(
                events,
                "composition",
                null,
                compositionFailure);
        RecordingCloseable commandOwner = new RecordingCloseable(events, "commands", commandFailure);
        SwingApplicationRuntime runtime = createRuntime(
                composition,
                commandOwner,
                () -> {
                    events.add("exit");
                    throw exitFailure;
                });

        RuntimeException thrown = assertThrows(RuntimeException.class, runtime::close);

        assertSame(compositionFailure, thrown);
        assertArrayEquals(new Throwable[] {commandFailure, exitFailure}, thrown.getSuppressed());
        assertEquals(List.of("composition", "commands", "exit"), events);
        runtime.close();
        assertEquals(List.of("composition", "commands", "exit"), events);
    }

    /// Confirms that one exception instance reused by every cleanup step retains its identity.
    @Test
    void toleratesSameFailureInstanceAcrossRuntimeCleanup() {
        List<String> events = new ArrayList<>();
        RuntimeException sharedFailure = new RuntimeException("shared failure");
        RecordingLifecycle composition = new RecordingLifecycle(
                events,
                "composition",
                null,
                sharedFailure);
        RecordingCloseable commandOwner = new RecordingCloseable(events, "commands", sharedFailure);
        SwingApplicationRuntime runtime = createRuntime(
                composition,
                commandOwner,
                () -> {
                    events.add("exit");
                    throw sharedFailure;
                });

        RuntimeException thrown = assertThrows(RuntimeException.class, runtime::close);

        assertSame(sharedFailure, thrown);
        assertArrayEquals(new Throwable[0], thrown.getSuppressed());
        assertEquals(List.of("composition", "commands", "exit"), events);
    }

    /// Confirms that failed construction closes returned resources without exiting the toolkit.
    @Test
    void constructionFailureClosesOwnerWithoutToolkitExit() {
        List<String> events = new ArrayList<>();
        RuntimeException constructionFailure = new RuntimeException("construction failure");
        RuntimeException ownerCloseFailure = new RuntimeException("owner close failure");
        RecordingCloseable commandOwner = new RecordingCloseable(
                events,
                "commands",
                ownerCloseFailure);
        AtomicInteger toolkitExitCount = new AtomicInteger();

        RuntimeException thrown = assertThrows(
                RuntimeException.class,
                () -> SwingApplicationRuntime.createForCollaborators(
                        closeCommand -> new SwingApplicationRuntime.CommandOwnerHandle(
                                commands(),
                                commandOwner),
                        (applicationCommands, closeCommand) -> {
                            closeCommand.run();
                            throw constructionFailure;
                        },
                        toolkitExitCount::incrementAndGet));

        assertSame(constructionFailure, thrown);
        assertArrayEquals(new Throwable[] {ownerCloseFailure}, thrown.getSuppressed());
        assertEquals(List.of("commands"), events);
        assertEquals(1, commandOwner.closeCount());
        assertEquals(0, toolkitExitCount.get());
    }

    /// Confirms construction cleanup cannot obscure a reused original exception through self-suppression.
    @Test
    void toleratesConstructionFailureReusedByOwnerCleanup() {
        List<String> events = new ArrayList<>();
        RuntimeException sharedFailure = new RuntimeException("shared construction failure");
        RecordingCloseable commandOwner = new RecordingCloseable(events, "commands", sharedFailure);
        AtomicInteger toolkitExitCount = new AtomicInteger();

        RuntimeException thrown = assertThrows(
                RuntimeException.class,
                () -> SwingApplicationRuntime.createForCollaborators(
                        closeCommand -> new SwingApplicationRuntime.CommandOwnerHandle(
                                commands(),
                                commandOwner),
                        (applicationCommands, closeCommand) -> {
                            throw sharedFailure;
                        },
                        toolkitExitCount::incrementAndGet));

        assertSame(sharedFailure, thrown);
        assertArrayEquals(new Throwable[0], thrown.getSuppressed());
        assertEquals(List.of("commands"), events);
        assertEquals(0, toolkitExitCount.get());
    }

    /// Creates a runtime from reusable recording collaborators.
    ///
    /// @param composition recording application lifecycle
    /// @param commandOwner recording command owner
    /// @param toolkitExitCommand final toolkit command
    /// @return attached runtime
    private static SwingApplicationRuntime createRuntime(
            RecordingLifecycle composition,
            RecordingCloseable commandOwner,
            Runnable toolkitExitCommand) {
        return SwingApplicationRuntime.createForCollaborators(
                closeCommand -> new SwingApplicationRuntime.CommandOwnerHandle(
                        commands(),
                        commandOwner),
                (applicationCommands, closeCommand) -> composition,
                toolkitExitCommand);
    }

    /// Creates application commands that must never be invoked by lifecycle tests.
    ///
    /// @return inert command boundaries
    private static SwingApplicationCommands commands() {
        return new SwingApplicationCommands(
                () -> {
                    throw new AssertionError("add-account command must not run");
                },
                AccountRefreshCommand.unavailable(),
                request -> {
                    throw new AssertionError("launch command must not run");
                },
                HomeLaunchScriptExportCommand.unavailable());
    }

    /// Records application lifecycle calls and optionally reports close notification or failure.
    @NotNullByDefault
    private static final class RecordingLifecycle
            implements SwingApplicationRuntime.ApplicationLifecycle {
        /// Shared ordered event sink.
        private final List<String> events;

        /// Event name appended by close.
        private final String closeEvent;

        /// Optional callback simulating composition final-close notification.
        private final @Nullable Runnable closeNotification;

        /// Optional close failure.
        private final @Nullable RuntimeException closeFailure;

        /// Stable headless native-dialog owner.
        private final Component dialogOwner = new JPanel();

        /// Number of delegated open calls.
        private int openCount;

        /// Number of delegated hide calls.
        private int hideCount;

        /// Whether application interaction is currently enabled.
        private boolean interactionEnabled = true;

        /// Number of delegated close calls.
        private int closeCount;

        /// Creates a recording application lifecycle.
        ///
        /// @param events shared ordered event sink
        /// @param closeEvent event name appended by close
        /// @param closeNotification optional close notification callback
        /// @param closeFailure optional close failure
        private RecordingLifecycle(
                List<String> events,
                String closeEvent,
                @Nullable Runnable closeNotification,
                @Nullable RuntimeException closeFailure) {
            this.events = Objects.requireNonNull(events, "events");
            this.closeEvent = Objects.requireNonNull(closeEvent, "closeEvent");
            this.closeNotification = closeNotification;
            this.closeFailure = closeFailure;
        }

        /// Records one delegated open call.
        @Override
        public void open() {
            openCount++;
        }

        /// Records one delegated non-destructive hide call.
        @Override
        public void hide() {
            hideCount++;
        }

        /// Returns the stable headless dialog owner.
        ///
        /// @return headless dialog owner
        @Override
        public Component dialogOwner() {
            return dialogOwner;
        }

        /// Records application interaction state.
        ///
        /// @param enabled whether application interaction is enabled
        @Override
        public void setInteractionEnabled(boolean enabled) {
            interactionEnabled = enabled;
        }

        /// Records one delegated close call, notifies the relay, and reports configured failure.
        @Override
        public void close() {
            closeCount++;
            events.add(closeEvent);
            if (closeNotification != null) {
                closeNotification.run();
            }
            if (closeFailure != null) {
                throw closeFailure;
            }
        }

        /// Returns the delegated open-call count.
        ///
        /// @return open-call count
        private int openCount() {
            return openCount;
        }

        /// Returns the delegated hide-call count.
        ///
        /// @return hide-call count
        private int hideCount() {
            return hideCount;
        }

        /// Returns whether application interaction is enabled.
        ///
        /// @return current interaction state
        private boolean interactionEnabled() {
            return interactionEnabled;
        }

        /// Returns the delegated close-call count.
        ///
        /// @return close-call count
        private int closeCount() {
            return closeCount;
        }
    }

    /// Lifecycle fixture whose hide operation waits until concurrent close reaches the window.
    @NotNullByDefault
    private static final class CloseDuringHideLifecycle
            implements SwingApplicationRuntime.ApplicationLifecycle {
        /// Stable headless owner unused by the concurrency scenario.
        private final Component dialogOwner = new JPanel();

        /// Signals that hide has entered its simulated blocking native-window call.
        private final CountDownLatch hideEntered = new CountDownLatch(1);

        /// Signals that close reached the simulated native window.
        private final CountDownLatch closeReachedWindow = new CountDownLatch(1);

        /// No-op open operation unused by this concurrency fixture.
        @Override
        public void open() {
        }

        /// Waits for close to prove runtime synchronization does not block the opposite transition.
        @Override
        public void hide() {
            hideEntered.countDown();
            try {
                if (!closeReachedWindow.await(2L, TimeUnit.SECONDS)) {
                    throw new AssertionError("Runtime close could not reach the window while hide was active");
                }
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while waiting for runtime close", failure);
            }
        }

        /// Returns the stable headless dialog owner.
        ///
        /// @return headless owner component
        @Override
        public Component dialogOwner() {
            return dialogOwner;
        }

        /// Ignores interaction state in this hide/close concurrency fixture.
        ///
        /// @param enabled whether application interaction is enabled
        @Override
        public void setInteractionEnabled(boolean enabled) {
        }

        /// Releases the simulated hide operation.
        @Override
        public void close() {
            closeReachedWindow.countDown();
        }

        /// Waits until the simulated hide operation has entered the native-window boundary.
        ///
        /// @return true when hide entered before the timeout
        private boolean awaitHideEntered() throws InterruptedException {
            return hideEntered.await(2L, TimeUnit.SECONDS);
        }
    }

    /// Records command-owner cleanup and optionally fails.
    @NotNullByDefault
    private static final class RecordingCloseable implements AutoCloseable {
        /// Shared ordered event sink.
        private final List<String> events;

        /// Event name appended by close.
        private final String closeEvent;

        /// Optional close failure.
        private final @Nullable RuntimeException closeFailure;

        /// Number of delegated close calls.
        private int closeCount;

        /// Creates a recording closeable.
        ///
        /// @param events shared ordered event sink
        /// @param closeEvent event name appended by close
        /// @param closeFailure optional close failure
        private RecordingCloseable(
                List<String> events,
                String closeEvent,
                @Nullable RuntimeException closeFailure) {
            this.events = Objects.requireNonNull(events, "events");
            this.closeEvent = Objects.requireNonNull(closeEvent, "closeEvent");
            this.closeFailure = closeFailure;
        }

        /// Records one delegated close call and reports the configured failure.
        @Override
        public void close() {
            closeCount++;
            events.add(closeEvent);
            if (closeFailure != null) {
                throw closeFailure;
            }
        }

        /// Returns the delegated close-call count.
        ///
        /// @return close-call count
        private int closeCount() {
            return closeCount;
        }
    }
}
