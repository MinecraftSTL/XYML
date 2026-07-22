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
package space.minecraftstl.xyml.ui.swing.page.home;

import org.jetbrains.annotations.NotNullByDefault;
import space.minecraftstl.xyml.observable.Subscription;
import space.minecraftstl.xyml.observable.ValueChange;
import space.minecraftstl.xyml.observable.ValueChangeListener;
import space.minecraftstl.xyml.observable.ValueChangeSupport;

import java.util.Objects;

/// Maps launcher selections to home readiness and delegates navigation and launch commands.
@NotNullByDefault
public final class LauncherHomeModel implements HomeModel, AutoCloseable {
    /// Serializes constructor reconciliation, selection events, subscriptions, and closure.
    private final Object stateLock = new Object();

    /// Selected account and instance state store.
    private final HomeSelectionStore selectionStore;

    /// Localized readiness status text.
    private final HomeStatusStrings statusStrings;

    /// Account-page navigation command.
    private final Runnable selectAccountCommand;

    /// Instance-page navigation command.
    private final Runnable selectInstanceCommand;

    /// New-instance workflow command.
    private final Runnable addInstanceCommand;

    /// Selected-game launch command.
    private final Runnable launchCommand;

    /// Home-state transition publisher.
    private final ValueChangeSupport<HomeSnapshot> changes = new ValueChangeSupport<>(this);

    /// Owned selection-store subscription.
    private final Subscription selectionSubscription;

    /// Latest mapped home state.
    private volatile HomeSnapshot currentSnapshot;

    /// Whether the model has released its store subscription.
    private volatile boolean closed;

    /// Creates a launcher home model.
    ///
    /// @param selectionStore account and instance state store
    /// @param statusStrings localized readiness text
    /// @param selectAccountCommand account navigation command
    /// @param selectInstanceCommand instance navigation command
    /// @param addInstanceCommand add-instance workflow command
    /// @param launchCommand launch command
    public LauncherHomeModel(
            HomeSelectionStore selectionStore,
            HomeStatusStrings statusStrings,
            Runnable selectAccountCommand,
            Runnable selectInstanceCommand,
            Runnable addInstanceCommand,
            Runnable launchCommand) {
        this.selectionStore = Objects.requireNonNull(selectionStore, "selectionStore");
        this.statusStrings = Objects.requireNonNull(statusStrings, "statusStrings");
        this.selectAccountCommand = Objects.requireNonNull(selectAccountCommand, "selectAccountCommand");
        this.selectInstanceCommand = Objects.requireNonNull(selectInstanceCommand, "selectInstanceCommand");
        this.addInstanceCommand = Objects.requireNonNull(addInstanceCommand, "addInstanceCommand");
        this.launchCommand = Objects.requireNonNull(launchCommand, "launchCommand");
        currentSnapshot = map(selectionStore.snapshot());
        selectionSubscription = selectionStore.subscribe(this::selectionChanged);
        reconcileSelectionStore();
    }

    /// Returns the latest mapped home state.
    @Override
    public HomeSnapshot snapshot() {
        return currentSnapshot;
    }

    /// Registers a mapped home-state listener.
    @Override
    public Subscription subscribe(ValueChangeListener<HomeSnapshot> listener) {
        Objects.requireNonNull(listener, "listener");
        synchronized (stateLock) {
            requireOpen();
            return changes.subscribe(listener);
        }
    }

    /// Opens account selection.
    @Override
    public void selectAccount() {
        requireOpen();
        selectAccountCommand.run();
    }

    /// Opens instance selection.
    @Override
    public void selectInstance() {
        requireOpen();
        selectInstanceCommand.run();
    }

    /// Opens new-instance creation.
    @Override
    public void addInstance() {
        requireOpen();
        addInstanceCommand.run();
    }

    /// Launches only when the latest confirmed selections are ready.
    @Override
    public void launch() {
        synchronized (stateLock) {
            requireOpen();
            if (!currentSnapshot.launchEnabled()) {
                return;
            }
        }
        launchCommand.run();
    }

    /// Releases the selection-store subscription exactly once.
    @Override
    public void close() {
        synchronized (stateLock) {
            if (closed) {
                return;
            }
            closed = true;
        }
        selectionSubscription.unsubscribe();
    }

    /// Maps and publishes one selection transition.
    ///
    /// @param change raw selection transition
    private void selectionChanged(ValueChange<HomeSelectionState> change) {
        HomeSelectionState selection = Objects.requireNonNull(
                change.currentValue(), "home selection store emitted null");
        applySelection(selection);
    }

    /// Reads the store and applies its latest state while holding the same lock used by event delivery.
    ///
    /// A later store mutation may occur while this method holds the model lock, but its subscribed event
    /// then waits and applies after this reconciliation, so an older snapshot cannot overwrite it.
    private void reconcileSelectionStore() {
        HomeSnapshot previous;
        HomeSnapshot replacement;
        synchronized (stateLock) {
            previous = currentSnapshot;
            replacement = map(selectionStore.snapshot());
            currentSnapshot = replacement;
        }
        changes.fireChange(previous, replacement);
    }

    /// Applies one selection under the model lock and publishes after releasing it.
    ///
    /// @param selection selected account and instance presentation
    private void applySelection(HomeSelectionState selection) {
        HomeSnapshot previous;
        HomeSnapshot replacement;
        synchronized (stateLock) {
            if (closed) {
                return;
            }
            previous = currentSnapshot;
            replacement = map(Objects.requireNonNull(selection, "selection"));
            currentSnapshot = replacement;
        }
        changes.fireChange(previous, replacement);
    }

    /// Maps selected values to launch readiness and localized status.
    ///
    /// @param selection selected account and instance presentation
    /// @return mapped home state
    private HomeSnapshot map(HomeSelectionState selection) {
        boolean hasAccount = !selection.accountName().isBlank();
        boolean hasInstance = !selection.instanceName().isBlank();
        String status = !hasAccount
                ? statusStrings.missingAccountStatus()
                : !hasInstance ? statusStrings.missingInstanceStatus() : statusStrings.readyStatus();
        return new HomeSnapshot(
                selection.accountName(),
                selection.accountDetail(),
                selection.instanceName(),
                selection.instanceDetail(),
                status,
                hasAccount && hasInstance,
                false,
                true);
    }

    /// Rejects commands and subscriptions after closure.
    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("Launcher home model is closed");
        }
    }
}
