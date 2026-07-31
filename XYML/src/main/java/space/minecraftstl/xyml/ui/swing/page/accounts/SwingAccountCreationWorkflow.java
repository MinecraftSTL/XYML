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
package space.minecraftstl.xyml.ui.swing.page.accounts;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;

import java.awt.Component;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static space.minecraftstl.xyml.util.logging.Logger.LOG;

/// Production entry point for opening the native add-account dialog.
///
/// The caller supplies the executor and remains responsible for its lifecycle. This facade intentionally
/// does not reference `Launcher` or `Controllers`, allowing production wiring to replace the launcher dialog
/// in one later composition change without reintroducing JavaFX into Swing UI classes.
@NotNullByDefault
public final class SwingAccountCreationWorkflow {
    /// Prevents utility instantiation.
    private SwingAccountCreationWorkflow() {
    }

    /// Creates an owned dialog on the Swing EDT without opening it.
    ///
    /// The returned dialog owns its coordinator but not `executor`. The caller may invoke
    /// [SwingAccountCreationDialog#open()] and must invoke [SwingAccountCreationDialog#close()]
    /// if the surrounding application shuts down while the dialog is active.
    ///
    /// @param owner owning component, or null for an unowned dialog
    /// @param initialMethod explicit method selected when the dialog opens
    /// @param executor caller-owned executor for authentication and launcher bridge work
    /// @return newly created native dialog
    public static SwingAccountCreationDialog create(
            @Nullable Component owner,
            AccountCreationMethod initialMethod,
            ExecutorService executor) {
        EdtDispatcher.requireEventDispatchThread();
        return new SwingAccountCreationDialog(
                owner,
                Objects.requireNonNull(initialMethod, "initialMethod"),
                new LauncherAccountCreationGateway(),
                Objects.requireNonNull(executor, "executor"));
    }

    /// Opens a native modal add-account workflow on the Swing EDT.
    ///
    /// @param owner owning component, or null for an unowned dialog
    /// @param initialMethod explicit method selected when the dialog opens
    /// @param executor caller-owned executor for authentication and launcher bridge work
    public static void open(
            @Nullable Component owner,
            AccountCreationMethod initialMethod,
            ExecutorService executor) {
        Objects.requireNonNull(initialMethod, "initialMethod");
        Objects.requireNonNull(executor, "executor");
        EdtDispatcher.execute(() -> {
            SwingAccountCreationDialog dialog = create(owner, initialMethod, executor);
            dialog.open();
        });
    }

    /// Resolves the launcher preferred method and restriction policy off the EDT, then opens the native dialog.
    ///
    /// This is the production command intended to replace the launcher `CreateAccountPane` entry point. Preference
    /// reads happen on `executor`; the gateway confines launcher-state property access to
    /// [space.minecraftstl.xyml.ui.swing.runtime.LauncherStateDispatcher]. A restricted environment displays only
    /// Microsoft, while an unrestricted environment persists later tab changes through the same gateway.
    ///
    /// @param owner owning component, or null for an unowned dialog
    /// @param executor caller-owned executor for preference loading, authentication, and launcher bridge work
    public static AccountCreationWorkflowHandle openPreferred(
            @Nullable Component owner,
            ExecutorService executor) {
        Objects.requireNonNull(executor, "executor");
        LauncherAccountCreationGateway gateway = new LauncherAccountCreationGateway();
        DefaultWorkflowHandle handle = new DefaultWorkflowHandle();
        Future<?> preferenceLoad = executor.submit(() -> {
            try {
                AccountCreationMethod preferredMethod = gateway.preferredMethod();
                boolean microsoftOnly = gateway.isMicrosoftOnly();
                EdtDispatcher.execute(() -> handle.openDialog(
                        owner,
                        preferredMethod,
                        microsoftOnly,
                        gateway,
                        executor));
            } catch (RuntimeException failure) {
                if (!handle.isClosed()) {
                    LOG.warning("Failed to resolve preferred account creation method", failure);
                    handle.close();
                }
            }
        });
        handle.attachPreferenceLoad(preferenceLoad);
        return handle;
    }

    /// Default lifecycle handle spanning preference loading and modal dialog ownership.
    @NotNullByDefault
    private static final class DefaultWorkflowHandle implements AccountCreationWorkflowHandle {
        /// Whether cancellation or natural dialog closure has completed.
        private final AtomicBoolean closed = new AtomicBoolean();

        /// Submitted preference-load task, or null during submission.
        private final AtomicReference<@Nullable Future<?>> preferenceLoad = new AtomicReference<>();

        /// Created native dialog, or null before creation and after closure.
        private final AtomicReference<@Nullable SwingAccountCreationDialog> dialog = new AtomicReference<>();

        /// Attaches the preference task and cancels it if closure won the submission race.
        ///
        /// @param load submitted preference task
        private void attachPreferenceLoad(Future<?> load) {
            if (!preferenceLoad.compareAndSet(null, Objects.requireNonNull(load, "load"))) {
                throw new IllegalStateException("Preference load was already attached");
            }
            if (closed.get()) {
                load.cancel(true);
            }
        }

        /// Creates, tracks, and opens the modal dialog unless the runtime already closed this workflow.
        ///
        /// @param owner owning component, or null
        /// @param initialMethod resolved initial method
        /// @param microsoftOnly resolved restriction policy
        /// @param gateway launcher gateway used during preference resolution
        /// @param executor caller-owned executor
        private void openDialog(
                @Nullable Component owner,
                AccountCreationMethod initialMethod,
                boolean microsoftOnly,
                AccountCreationGateway gateway,
                ExecutorService executor) {
            EdtDispatcher.requireEventDispatchThread();
            if (closed.get()) {
                return;
            }
            SwingAccountCreationDialog created = new SwingAccountCreationDialog(
                    owner,
                    initialMethod,
                    microsoftOnly,
                    gateway,
                    executor);
            if (!dialog.compareAndSet(null, created)) {
                created.close();
                throw new IllegalStateException("Account creation dialog was already created");
            }
            if (closed.get()) {
                created.close();
                dialog.compareAndSet(created, null);
                return;
            }
            try {
                created.open();
            } finally {
                created.close();
                dialog.compareAndSet(created, null);
                closed.set(true);
            }
        }

        /// Returns whether the workflow has reached terminal closure.
        @Override
        public boolean isClosed() {
            return closed.get();
        }

        /// Cancels preference loading and closes a created dialog without blocking the caller.
        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            @Nullable Future<?> load = preferenceLoad.get();
            if (load != null) {
                load.cancel(true);
            }
            @Nullable SwingAccountCreationDialog created = dialog.getAndSet(null);
            if (created != null) {
                created.close();
            }
        }
    }
}
