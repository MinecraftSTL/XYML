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
package space.minecraftstl.xyml.game.install;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/// Enforces one in-flight game installation while exposing a stable session before asynchronous preparation.
@NotNullByDefault
public final class DefaultGameInstallService implements GameInstallService {
    /// Factory invoked only by the session that wins the installation slot.
    private final GameInstallTaskFactory taskFactory;

    /// Caller-owned executor used for task creation and executor startup.
    private final Executor preparationExecutor;

    /// Explicit localized title shared by session task presentations.
    private final String presentationTitle;

    /// Explicit localized phase shown before the core task becomes active.
    private final String waitingPhase;

    /// Current active session, or null while another request may start.
    private final AtomicReference<@Nullable DefaultGameInstallSession> activeInstallation =
            new AtomicReference<>();

    /// Permanent closed state used to reject future requests.
    private final AtomicBoolean closed = new AtomicBoolean();

    /// Creates a single-flight installation service.
    ///
    /// @param taskFactory request-specific stopped-task factory
    /// @param preparationExecutor caller-owned executor; this service never closes it
    /// @param presentationTitle explicit localized task title
    /// @param waitingPhase explicit localized pre-task phase
    public DefaultGameInstallService(
            GameInstallTaskFactory taskFactory,
            Executor preparationExecutor,
            String presentationTitle,
            String waitingPhase) {
        this.taskFactory = Objects.requireNonNull(taskFactory, "taskFactory");
        this.preparationExecutor = Objects.requireNonNull(preparationExecutor, "preparationExecutor");
        this.presentationTitle = requirePresentationText(presentationTitle, "presentationTitle");
        this.waitingPhase = requirePresentationText(waitingPhase, "waitingPhase");
    }

    /// Claims the single-flight slot before scheduling task preparation.
    @Override
    public GameInstallSession install(GameInstallRequest request) {
        Objects.requireNonNull(request, "request");
        requireOpen();

        DefaultGameInstallSession session = new DefaultGameInstallSession(
                request,
                taskFactory,
                presentationTitle,
                waitingPhase,
                finished -> activeInstallation.compareAndSet(finished, null));
        @Nullable DefaultGameInstallSession existing = activeInstallation.compareAndExchange(null, session);
        if (existing != null) {
            throw new GameInstallAlreadyRunningException(existing.request());
        }

        if (closed.get()) {
            session.cancel();
            throw new IllegalStateException("Game installation service is closed");
        }

        try {
            preparationExecutor.execute(session::start);
        } catch (RejectedExecutionException schedulingFailure) {
            session.failBeforeStart(schedulingFailure);
        } catch (RuntimeException schedulingFailure) {
            session.failBeforeStart(schedulingFailure);
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

    /// Returns the active session without retaining terminal sessions.
    @Override
    public Optional<GameInstallSession> activeInstallation() {
        return Optional.ofNullable(activeInstallation.get());
    }

    /// Permanently closes this service and cooperatively cancels its active session.
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        @Nullable DefaultGameInstallSession session = activeInstallation.get();
        if (session != null) {
            session.cancel();
        }
    }

    /// Rejects requests after the permanent close transition.
    private void requireOpen() {
        if (closed.get()) {
            throw new IllegalStateException("Game installation service is closed");
        }
    }

    /// Rejects blank localized text instead of inventing a fallback.
    ///
    /// @param value caller-provided presentation text
    /// @param name constructor parameter name
    /// @return exact validated text
    private static String requirePresentationText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
