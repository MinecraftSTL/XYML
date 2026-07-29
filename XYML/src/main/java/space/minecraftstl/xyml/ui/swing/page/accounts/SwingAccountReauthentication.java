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
import space.minecraftstl.xyml.auth.AuthInfo;
import space.minecraftstl.xyml.ui.swing.SwingUiDispatcher;

import java.awt.Component;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/// Production native Swing implementation of credential-expiry recovery.
///
/// [#reauthenticate(String)] is safe to call from a background launch task and returns immediately.
/// The owner supplier is evaluated only on the Swing EDT. Closing this object cancels any active
/// password or device-code operation but never shuts down the caller-owned executor.
@NotNullByDefault
public final class SwingAccountReauthentication implements AccountReauthentication {
    /// Native prompt implementation.
    private final SwingAccountReauthenticationInteraction interaction;

    /// Toolkit-neutral state machine.
    private final DefaultAccountReauthentication delegate;

    /// Active public completion used by native cancel buttons.
    private final AtomicReference<@Nullable CompletableFuture<AuthInfo>> active = new AtomicReference<>();

    /// Whether this production adapter is closed.
    private final AtomicBoolean closed = new AtomicBoolean();

    /// Creates a production reauthentication boundary.
    ///
    /// @param ownerSupplier current owner component supplier evaluated only on the EDT
    /// @param executor caller-owned authentication executor
    /// @return native reauthentication boundary
    public static SwingAccountReauthentication create(
            Supplier<@Nullable Component> ownerSupplier,
            ExecutorService executor) {
        return new SwingAccountReauthentication(ownerSupplier, executor);
    }

    /// Wires native prompts, launcher account access, caller execution, and Swing dispatch.
    ///
    /// @param ownerSupplier current owner component supplier
    /// @param executor caller-owned authentication executor
    private SwingAccountReauthentication(
            Supplier<@Nullable Component> ownerSupplier,
            ExecutorService executor) {
        Objects.requireNonNull(ownerSupplier, "ownerSupplier");
        Objects.requireNonNull(executor, "executor");
        interaction = new SwingAccountReauthenticationInteraction(
                ownerSupplier,
                this::cancelActive);
        delegate = new DefaultAccountReauthentication(
                new LauncherAccountReauthenticationGateway(),
                interaction,
                executor,
                SwingUiDispatcher.INSTANCE);
    }

    /// Starts one background-safe credential-expiry recovery operation.
    @Override
    public CompletionStage<AuthInfo> reauthenticate(String accountId) {
        Objects.requireNonNull(accountId, "accountId");
        if (closed.get()) {
            throw new IllegalStateException("Swing account reauthentication is closed");
        }
        CompletableFuture<AuthInfo> completion = delegate.reauthenticate(accountId)
                .toCompletableFuture();
        if (!active.compareAndSet(null, completion)) {
            completion.cancel(true);
            throw new IllegalStateException("Another Swing account reauthentication is active");
        }
        completion.whenComplete((result, failure) -> active.compareAndSet(completion, null));
        return completion;
    }

    /// Cancels active work and closes every prompt once.
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        cancelActive();
        delegate.close();
        interaction.closeCurrentInteraction();
    }

    /// Cancels the active completion from a native cancel or close action.
    private void cancelActive() {
        @Nullable CompletableFuture<AuthInfo> completion = active.get();
        if (completion != null) {
            completion.cancel(true);
        }
    }
}
