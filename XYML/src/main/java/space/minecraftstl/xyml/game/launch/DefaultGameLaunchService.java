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
package space.minecraftstl.xyml.game.launch;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/// Enforces one in-flight process preparation while leaving created-process ownership to downstream monitoring.
@NotNullByDefault
public final class DefaultGameLaunchService implements GameLaunchService {
    /// Factory invoked only by the session that wins the preparation slot.
    private final LaunchTaskFactory taskFactory;

    /// Caller-owned executor used to run factory invocation and session startup away from the launch caller.
    private final Executor preparationExecutor;

    /// Explicit localized title used by every session presentation.
    private final String presentationTitle;

    /// Explicit localized phase shown before the launch task becomes active.
    private final String waitingPhase;

    /// Current process-preparation owner, or null when another request may start.
    private final AtomicReference<@Nullable DefaultLaunchSession> activePreparation = new AtomicReference<>();

    /// Permanent closed state used to reject later launch requests.
    private final AtomicBoolean closed = new AtomicBoolean();

    /// Creates a launch service backed by the supplied task factory.
    ///
    /// @param taskFactory factory for request-specific launch tasks
    /// @param preparationExecutor caller-owned asynchronous executor; this service never closes it
    /// @param presentationTitle explicit localized task-surface title
    /// @param waitingPhase explicit localized phase shown before task activation
    public DefaultGameLaunchService(
            LaunchTaskFactory taskFactory,
            Executor preparationExecutor,
            String presentationTitle,
            String waitingPhase) {
        this.taskFactory = Objects.requireNonNull(taskFactory, "taskFactory");
        this.preparationExecutor = Objects.requireNonNull(preparationExecutor, "preparationExecutor");
        this.presentationTitle = requirePresentationText(presentationTitle, "presentationTitle");
        this.waitingPhase = requirePresentationText(waitingPhase, "waitingPhase");
    }

    /// Claims the preparation slot with compare-and-set before invoking the task factory.
    @Override
    public LaunchSession launch(LaunchRequest request) {
        Objects.requireNonNull(request, "request");
        requireOpen();

        DefaultLaunchSession session = new DefaultLaunchSession(
                request,
                taskFactory,
                presentationTitle,
                waitingPhase,
                finishedSession -> activePreparation.compareAndSet(finishedSession, null));
        @Nullable DefaultLaunchSession existing = activePreparation.compareAndExchange(null, session);
        if (existing != null) {
            throw new LaunchAlreadyRunningException(existing.request());
        }

        if (closed.get()) {
            session.cancel();
            throw new IllegalStateException("Game launch service is closed");
        }

        try {
            preparationExecutor.execute(session::start);
        } catch (RejectedExecutionException exception) {
            session.failBeforeStart(exception);
        } catch (RuntimeException exception) {
            session.failBeforeStart(exception);
        } catch (Error error) {
            try {
                session.failBeforeStart(error);
            } catch (RuntimeException | Error cleanupFailure) {
                if (cleanupFailure != error) {
                    error.addSuppressed(cleanupFailure);
                }
            }
            throw error;
        }
        return session;
    }

    /// Returns the current preparation owner without retaining completed processes.
    @Override
    public Optional<LaunchSession> activePreparation() {
        return Optional.ofNullable(activePreparation.get());
    }

    /// Permanently closes the service and cooperatively cancels only the current preparation.
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        @Nullable DefaultLaunchSession session = activePreparation.get();
        if (session != null) {
            session.cancel();
        }
    }

    /// Rejects launches after the permanent close transition.
    private void requireOpen() {
        if (closed.get()) {
            throw new IllegalStateException("Game launch service is closed");
        }
    }

    /// Rejects missing or blank localized presentation text instead of inventing a fallback.
    ///
    /// @param value caller-provided text
    /// @param name constructor parameter name
    /// @return validated text without normalization
    private static String requirePresentationText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
