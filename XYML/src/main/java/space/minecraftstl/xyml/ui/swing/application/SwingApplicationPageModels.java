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
package space.minecraftstl.xyml.ui.swing.application;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.ui.swing.page.accounts.AccountsModel;
import space.minecraftstl.xyml.ui.swing.page.downloads.GameVersionCatalogModel;
import space.minecraftstl.xyml.ui.swing.page.home.HomeModel;
import space.minecraftstl.xyml.ui.swing.page.instances.InstancesModel;
import space.minecraftstl.xyml.ui.swing.page.settings.AppearanceSettingsModel;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/// Owns the five toolkit-neutral page models and their ordered cleanup resources.
///
/// Resources are closed in list order. Production supplies models before their data sources and
/// backing stores so subscriptions are removed before lower-level adapters are detached.
@NotNullByDefault
public final class SwingApplicationPageModels implements AutoCloseable {
    /// Launcher-home state and commands.
    private final HomeModel home;

    /// Installed-instance state, viewport source, and commands.
    private final InstancesModel instances;

    /// Lazy game-version catalog state and viewport source.
    private final GameVersionCatalogModel gameVersions;

    /// Account state, viewport source, and commands.
    private final AccountsModel accounts;

    /// Persisted appearance state and commands.
    private final AppearanceSettingsModel appearance;

    /// Ordered model and store resources owned by this bundle.
    private final @Unmodifiable List<AutoCloseable> ownedResources;

    /// Prevents repeated resource cleanup.
    private final AtomicBoolean closed = new AtomicBoolean();

    /// Creates an explicitly owned model bundle.
    ///
    /// @param home launcher-home model
    /// @param instances installed-instance model
    /// @param gameVersions lazy game-version catalog model
    /// @param accounts account-selection model
    /// @param appearance appearance-settings model
    /// @param ownedResources resources closed in the supplied order
    public SwingApplicationPageModels(
            HomeModel home,
            InstancesModel instances,
            GameVersionCatalogModel gameVersions,
            AccountsModel accounts,
            AppearanceSettingsModel appearance,
            List<? extends AutoCloseable> ownedResources) {
        this.home = Objects.requireNonNull(home, "home");
        this.instances = Objects.requireNonNull(instances, "instances");
        this.gameVersions = Objects.requireNonNull(gameVersions, "gameVersions");
        this.accounts = Objects.requireNonNull(accounts, "accounts");
        this.appearance = Objects.requireNonNull(appearance, "appearance");
        Objects.requireNonNull(ownedResources, "ownedResources");
        this.ownedResources = List.copyOf(ownedResources);
    }

    /// Returns the launcher-home model.
    ///
    /// @return launcher-home model
    public HomeModel home() {
        return home;
    }

    /// Returns the installed-instance model.
    ///
    /// @return installed-instance model
    public InstancesModel instances() {
        return instances;
    }

    /// Returns the lazy game-version catalog model.
    ///
    /// @return game-version catalog model
    public GameVersionCatalogModel gameVersions() {
        return gameVersions;
    }

    /// Returns the account-selection model.
    ///
    /// @return account-selection model
    public AccountsModel accounts() {
        return accounts;
    }

    /// Returns the appearance-settings model.
    ///
    /// @return appearance-settings model
    public AppearanceSettingsModel appearance() {
        return appearance;
    }

    /// Closes every model and store at most once, attempting all cleanup after a failure.
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }

        @Nullable Throwable failure = null;
        for (AutoCloseable resource : ownedResources) {
            try {
                resource.close();
            } catch (Throwable closingFailure) {
                failure = accumulateFailure(failure, closingFailure);
            }
        }
        rethrowFailure(failure);
    }

    /// Accumulates one cleanup failure without skipping later resources.
    ///
    /// @param previous first failure, or null
    /// @param current next failure
    /// @return the first failure with later failures suppressed
    private static Throwable accumulateFailure(@Nullable Throwable previous, Throwable current) {
        Objects.requireNonNull(current, "current");
        if (previous == null) {
            return current;
        }
        previous.addSuppressed(current);
        return previous;
    }

    /// Rethrows the accumulated cleanup result without losing unchecked failure types.
    ///
    /// @param failure accumulated failure, or null when cleanup succeeded
    private static void rethrowFailure(@Nullable Throwable failure) {
        if (failure == null) {
            return;
        }
        if (failure instanceof RuntimeException runtimeException) {
            throw runtimeException;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        throw new IllegalStateException("Failed to close Swing page models", failure);
    }
}
