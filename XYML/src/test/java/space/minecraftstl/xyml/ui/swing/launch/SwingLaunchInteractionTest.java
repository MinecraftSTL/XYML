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
package space.minecraftstl.xyml.ui.swing.launch;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import space.minecraftstl.xyml.ui.UiDispatcher;
import space.minecraftstl.xyml.ui.launch.LaunchInteractionPrompt;
import space.minecraftstl.xyml.ui.swing.SwingUiDispatcher;

import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies EDT dispatch, deterministic close behavior, and headless safety for launch prompts.
@NotNullByDefault
class SwingLaunchInteractionTest {
    /// Uses the real Swing dispatcher while replacing only the modal native call.
    @Test
    void workerCallPresentsOnRealSwingEventDispatchThread() throws Exception {
        JPanel owner = new JPanel();
        AtomicBoolean ownerResolvedOnEdt = new AtomicBoolean();
        AtomicBoolean dialogShownOnEdt = new AtomicBoolean();
        SwingLaunchInteraction interaction = new SwingLaunchInteraction(
                () -> {
                    ownerResolvedOnEdt.set(SwingUtilities.isEventDispatchThread());
                    return owner;
                },
                SwingUiDispatcher.INSTANCE,
                (actualOwner, message, title, messageType, options, initialValue) -> {
                    dialogShownOnEdt.set(SwingUtilities.isEventDispatchThread());
                    assertSame(owner, actualOwner);
                    assertEquals("Java", title);
                    assertEquals(JOptionPane.WARNING_MESSAGE, messageType);
                    assertEquals("Cancel", initialValue);
                    return 0;
                },
                () -> false);

        LaunchInteractionPrompt.Action result = interaction.present(javaPrompt())
                .toCompletableFuture()
                .get(5, TimeUnit.SECONDS);

        assertEquals(LaunchInteractionPrompt.Action.USE_RECOMMENDED_JAVA, result);
        assertTrue(ownerResolvedOnEdt.get());
        assertTrue(dialogShownOnEdt.get());
    }

    /// Returns an incomplete stage to a worker until its queued UI operation runs.
    @Test
    void workerCallReturnsBeforeQueuedPresentation() {
        RecordingUiDispatcher dispatcher = new RecordingUiDispatcher();
        SwingLaunchInteraction interaction = new SwingLaunchInteraction(
                () -> null,
                dispatcher,
                (owner, message, title, messageType, options, initialValue) -> 1,
                () -> false);

        CompletionStage<LaunchInteractionPrompt.Action> completion = interaction.present(javaPrompt());

        assertFalse(completion.toCompletableFuture().isDone());
        dispatcher.runQueued();
        assertEquals(
                LaunchInteractionPrompt.Action.CANCEL,
                completion.toCompletableFuture().join());
    }

    /// Maps native window close to the request's explicit close action.
    @Test
    void nativeCloseUsesExplicitCloseAction() {
        RecordingUiDispatcher dispatcher = RecordingUiDispatcher.onDispatchThread();
        SwingLaunchInteraction interaction = new SwingLaunchInteraction(
                () -> null,
                dispatcher,
                (owner, message, title, messageType, options, initialValue) ->
                        JOptionPane.CLOSED_OPTION,
                () -> false);

        LaunchInteractionPrompt.Action result = interaction.present(
                LaunchInteractionPrompt.acknowledgement(
                        "Error",
                        "Launch cannot continue",
                        LaunchInteractionPrompt.Severity.ERROR,
                        "OK"))
                .toCompletableFuture()
                .join();

        assertEquals(LaunchInteractionPrompt.Action.ACKNOWLEDGE, result);
    }

    /// Avoids owner resolution, dispatch, and native dialogs in a headless environment.
    @Test
    void headlessEnvironmentUsesCloseActionWithoutUiAccess() {
        AtomicBoolean ownerResolved = new AtomicBoolean();
        AtomicBoolean dialogShown = new AtomicBoolean();
        RecordingUiDispatcher dispatcher = new RecordingUiDispatcher();
        SwingLaunchInteraction interaction = new SwingLaunchInteraction(
                () -> {
                    ownerResolved.set(true);
                    return null;
                },
                dispatcher,
                (owner, message, title, messageType, options, initialValue) -> {
                    dialogShown.set(true);
                    return 0;
                },
                () -> true);

        LaunchInteractionPrompt.Action result = interaction.present(javaPrompt())
                .toCompletableFuture()
                .join();

        assertEquals(LaunchInteractionPrompt.Action.CANCEL, result);
        assertFalse(ownerResolved.get());
        assertFalse(dialogShown.get());
        assertEquals(0, dispatcher.submissionCount());
    }

    /// Converts native presentation failures to exceptional completion without escaping the EDT callback.
    @Test
    void dialogFailureCompletesStageExceptionally() {
        RuntimeException failure = new IllegalStateException("dialog failed");
        RecordingUiDispatcher dispatcher = RecordingUiDispatcher.onDispatchThread();
        SwingLaunchInteraction interaction = new SwingLaunchInteraction(
                () -> null,
                dispatcher,
                (owner, message, title, messageType, options, initialValue) -> {
                    throw failure;
                },
                () -> false);

        CompletionException completionFailure = assertThrows(
                CompletionException.class,
                () -> interaction.present(javaPrompt()).toCompletableFuture().join());

        assertSame(failure, completionFailure.getCause());
    }

    /// Converts dispatcher rejection to exceptional completion instead of throwing from the public presenter call.
    @Test
    void dispatcherFailureCompletesStageExceptionally() {
        RuntimeException failure = new IllegalStateException("dispatcher rejected prompt");
        RecordingUiDispatcher dispatcher = new RecordingUiDispatcher();
        dispatcher.rejectWith(failure);
        SwingLaunchInteraction interaction = new SwingLaunchInteraction(
                () -> null,
                dispatcher,
                (owner, message, title, messageType, options, initialValue) -> 0,
                () -> false);

        CompletionException completionFailure = assertThrows(
                CompletionException.class,
                () -> interaction.present(javaPrompt()).toCompletableFuture().join());

        assertSame(failure, completionFailure.getCause());
    }

    /// Creates the representative Java recommendation prompt shared by threading tests.
    ///
    /// @return immutable Java recommendation decision
    private static LaunchInteractionPrompt javaPrompt() {
        return LaunchInteractionPrompt.confirmation(
                "Java",
                "Use the recommended runtime?",
                LaunchInteractionPrompt.Severity.WARNING,
                LaunchInteractionPrompt.Action.USE_RECOMMENDED_JAVA,
                "Use recommended",
                "Cancel");
    }

    /// Deterministic queue-backed dispatcher used to separate caller and presentation timing.
    @NotNullByDefault
    private static final class RecordingUiDispatcher implements UiDispatcher {
        /// Queued presentation, or null before dispatch and after execution.
        private @Nullable Runnable queued;

        /// Failure raised by the next dispatch, or null when dispatch succeeds.
        private @Nullable RuntimeException rejection;

        /// Whether the current test call represents the UI dispatch thread.
        private boolean dispatchThread;

        /// Number of accepted or rejected dispatch attempts.
        private int submissions;

        /// Creates a worker-thread dispatcher with no queued work.
        private RecordingUiDispatcher() {
        }

        /// Creates a dispatcher that treats the current test thread as its UI thread.
        ///
        /// @return direct dispatcher
        private static RecordingUiDispatcher onDispatchThread() {
            RecordingUiDispatcher dispatcher = new RecordingUiDispatcher();
            dispatcher.dispatchThread = true;
            return dispatcher;
        }

        /// Returns the configured dispatch-thread state.
        ///
        /// @return whether the current test call is UI-confined
        @Override
        public boolean isDispatchThread() {
            return dispatchThread;
        }

        /// Queues one operation or raises the configured rejection.
        ///
        /// @param operation operation to queue
        @Override
        public void dispatch(Runnable operation) {
            submissions++;
            if (rejection != null) {
                throw rejection;
            }
            if (queued != null) {
                throw new IllegalStateException("Only one queued operation is supported");
            }
            queued = operation;
        }

        /// Runs the queued operation while reporting UI-thread confinement.
        private void runQueued() {
            Runnable operation = java.util.Objects.requireNonNull(queued, "No operation was queued");
            queued = null;
            dispatchThread = true;
            try {
                operation.run();
            } finally {
                dispatchThread = false;
            }
        }

        /// Configures the next dispatch to fail.
        ///
        /// @param failure dispatcher rejection
        private void rejectWith(RuntimeException failure) {
            rejection = failure;
        }

        /// Returns the number of dispatch attempts.
        ///
        /// @return dispatch-attempt count
        private int submissionCount() {
            return submissions;
        }
    }
}
