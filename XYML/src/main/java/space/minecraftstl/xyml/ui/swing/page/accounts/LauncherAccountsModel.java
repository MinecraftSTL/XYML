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
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.observable.Subscription;
import space.minecraftstl.xyml.observable.ValueChange;
import space.minecraftstl.xyml.observable.ValueChangeListener;
import space.minecraftstl.xyml.observable.ValueChangeSupport;
import space.minecraftstl.xyml.ui.swing.choice.ChoicePage;
import space.minecraftstl.xyml.ui.swing.choice.IndexRange;
import space.minecraftstl.xyml.ui.swing.choice.LoadCancellation;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/// Maps a toolkit-neutral account store to exact viewport rows and minimal selection state.
///
/// Each published content revision owns a defensive copy of fully rendered text. Range loads only
/// slice that captured source and therefore never touch mutable account or authentication objects.
@NotNullByDefault
public final class LauncherAccountsModel implements AccountsModel, AutoCloseable {
    /// Serializes store transitions, listener registration, selection validation, and closure.
    private final Object stateLock = new Object();

    /// Toolkit-neutral account source and selection sink.
    private final AccountStore accountStore;

    /// Command that opens the real add-account workflow.
    private final Runnable addAccountCommand;

    /// Caller-owned nonblocking authentication refresh command.
    private final AccountRefreshCommand refreshAccountCommand;

    /// Thread-safe account-snapshot publisher.
    private final ValueChangeSupport<AccountsSnapshot> changes = new ValueChangeSupport<>(this);

    /// Owned account-store subscription.
    private final Subscription storeSubscription;

    /// Atomically published indexed source and its matching minimal snapshot.
    private volatile ModelState state;

    /// Whether later commands, loads, subscriptions, and store events are rejected.
    private volatile boolean closed;

    /// Creates an account model and reconciles a transition that may occur during subscription.
    ///
    /// @param accountStore immutable account descriptor source and selection sink
    /// @param addAccountCommand add-account workflow command
    /// @param refreshAccountCommand caller-owned account refresh command
    public LauncherAccountsModel(
            AccountStore accountStore,
            Runnable addAccountCommand,
            AccountRefreshCommand refreshAccountCommand) {
        this.accountStore = Objects.requireNonNull(accountStore, "accountStore");
        this.addAccountCommand = Objects.requireNonNull(addAccountCommand, "addAccountCommand");
        this.refreshAccountCommand = Objects.requireNonNull(refreshAccountCommand, "refreshAccountCommand");
        state = initialState(accountStore.snapshot());
        storeSubscription = accountStore.subscribe(this::storeChanged);
        reconcileAccountStore();
    }

    /// Creates a model with an unavailable refresh command for focused legacy callers and tests.
    ///
    /// @param accountStore immutable account descriptor source and selection sink
    /// @param addAccountCommand add-account workflow command
    LauncherAccountsModel(AccountStore accountStore, Runnable addAccountCommand) {
        this(accountStore, addAccountCommand, AccountRefreshCommand.unavailable());
    }

    /// Returns the latest minimal account-list state.
    @Override
    public AccountsSnapshot snapshot() {
        return state.snapshot();
    }

    /// Registers a listener for future account-list transitions.
    @Override
    public Subscription subscribe(ValueChangeListener<AccountsSnapshot> listener) {
        Objects.requireNonNull(listener, "listener");
        synchronized (stateLock) {
            requireOpen();
            return changes.subscribe(listener);
        }
    }

    /// Returns the exact count belonging to the atomically published current source.
    @Override
    public OptionalInt exactItemCount() {
        return OptionalInt.of(state.source().items().size());
    }

    /// Returns stable account identifiers without resolving avatars or authentication state.
    @Override
    public @Unmodifiable List<String> stableItemIds() {
        SourceSnapshot source = state.source();
        List<String> identifiers = new ArrayList<>(source.items().size());
        for (AccountListItem item : source.items()) {
            identifiers.add(item.accountId());
        }
        return List.copyOf(identifiers);
    }

    /// Loads one immutable account row by stable identifier.
    @Override
    public CompletionStage<AccountListItem> loadItem(
            String stableId,
            LoadCancellation cancellation) {
        Objects.requireNonNull(stableId, "stableId");
        Objects.requireNonNull(cancellation, "cancellation");
        SourceSnapshot source;
        synchronized (stateLock) {
            requireOpen();
            source = state.source();
        }
        try {
            checkLoadActive(cancellation);
            int index = indexOf(source.items(), stableId);
            if (index < 0) {
                throw new IllegalArgumentException("Unknown account: " + stableId);
            }
            AccountListItem item = source.items().get(index);
            checkLoadActive(cancellation);
            return CompletableFuture.completedFuture(item);
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(failure);
        }
    }

    /// Returns a completed range load sliced from one captured immutable text source.
    @Override
    public CompletionStage<ChoicePage<AccountListItem>> load(
            IndexRange desiredRange,
            LoadCancellation cancellation) {
        Objects.requireNonNull(desiredRange, "desiredRange");
        Objects.requireNonNull(cancellation, "cancellation");
        SourceSnapshot source;
        synchronized (stateLock) {
            requireOpen();
            source = state.source();
        }

        try {
            checkLoadActive(cancellation);
            IndexRange actualRange = desiredRange.clampToItemCount(source.items().size());
            @Unmodifiable List<AccountListItem> items = List.copyOf(
                    source.items().subList(actualRange.startInclusive(), actualRange.endExclusive()));
            checkLoadActive(cancellation);
            ChoicePage<AccountListItem> page = new ChoicePage<>(
                    actualRange,
                    items,
                    OptionalInt.of(source.items().size()),
                    actualRange.endExclusive() == source.items().size());
            return CompletableFuture.completedFuture(page);
        } catch (CancellationException failure) {
            return CompletableFuture.failedFuture(failure);
        }
    }

    /// Validates a stable identifier against the current revision before delegating selection.
    @Override
    public void selectAccount(String accountId) {
        Objects.requireNonNull(accountId, "accountId");
        synchronized (stateLock) {
            requireOpen();
            if (indexOf(state.source().items(), accountId) < 0) {
                throw new IllegalArgumentException("Unknown account: " + accountId);
            }
        }
        accountStore.selectAccount(accountId);
    }

    /// Delegates to the real add-account workflow while this model is open.
    @Override
    public void addAccount() {
        synchronized (stateLock) {
            requireOpen();
        }
        addAccountCommand.run();
    }

    /// Validates one stable identifier before delegating permanent removal to the account store.
    @Override
    public void removeAccount(String accountId, boolean allowReadOnlyOverwrite) {
        validateCurrentAccount(accountId);
        accountStore.removeAccount(accountId, allowReadOnlyOverwrite);
    }

    /// Validates one stable identifier before starting caller-owned asynchronous reauthentication.
    @Override
    public CompletionStage<Void> refreshAccount(String accountId) {
        validateCurrentAccount(accountId);
        return Objects.requireNonNull(
                refreshAccountCommand.refresh(accountId),
                "refreshAccountCommand returned null");
    }

    /// Returns the optional persistent server manager exposed by the underlying launcher account store.
    ///
    /// @return available authlib-injector server store, or empty for generic account sources
    @Override
    public Optional<AuthlibServerStore> authlibServerStore() {
        if (accountStore instanceof AuthlibServerStoreProvider provider) {
            return Optional.of(provider.authlibServerStore());
        }
        return Optional.empty();
    }

    /// Returns the optional offline-skin bridge exposed by the underlying launcher account store.
    ///
    /// @return available offline-skin store, or empty for generic account sources
    @Override
    public Optional<OfflineSkinStore> offlineSkinStore() {
        if (accountStore instanceof OfflineSkinStoreProvider provider) {
            return Optional.of(provider.offlineSkinStore());
        }
        return Optional.empty();
    }

    /// Returns the optional portable/global account bridge exposed by the underlying store.
    ///
    /// @return available portability store, or empty for generic account sources
    @Override
    public Optional<AccountPortabilityStore> accountPortabilityStore() {
        if (accountStore instanceof AccountPortabilityStoreProvider provider) {
            return Optional.of(provider.accountPortabilityStore());
        }
        return Optional.empty();
    }

    /// Returns the optional online skin-upload bridge exposed by the underlying store.
    ///
    /// @return available upload store, or empty for generic account sources
    @Override
    public Optional<AccountSkinUploadStore> accountSkinUploadStore() {
        if (accountStore instanceof AccountSkinUploadStoreProvider provider) {
            return Optional.of(provider.accountSkinUploadStore());
        }
        return Optional.empty();
    }

    /// Releases the account-store subscription exactly once.
    @Override
    public void close() {
        synchronized (stateLock) {
            if (closed) {
                return;
            }
            closed = true;
        }
        storeSubscription.unsubscribe();
    }

    /// Applies the current store value from one synchronous transition.
    ///
    /// @param change account-store transition
    private void storeChanged(ValueChange<AccountStoreState> change) {
        AccountStoreState replacement = Objects.requireNonNull(
                change.currentValue(), "account store emitted null");
        applyStoreState(replacement);
    }

    /// Recovers a store transition that occurred between the initial read and listener registration.
    private void reconcileAccountStore() {
        SnapshotTransition transition;
        synchronized (stateLock) {
            if (closed) {
                return;
            }
            transition = replaceStateLocked(accountStore.snapshot());
        }
        changes.fireChange(transition.previous(), transition.replacement());
    }

    /// Replaces selection alone or publishes a newly numbered immutable content source.
    ///
    /// @param storeState latest account-store state
    private void applyStoreState(AccountStoreState storeState) {
        SnapshotTransition transition;
        synchronized (stateLock) {
            if (closed) {
                return;
            }
            transition = replaceStateLocked(storeState);
        }
        changes.fireChange(transition.previous(), transition.replacement());
    }

    /// Replaces model state from one store snapshot while the caller holds [#stateLock].
    ///
    /// @param storeState latest account-store state
    /// @return previous and replacement snapshots for publication after unlocking
    private SnapshotTransition replaceStateLocked(AccountStoreState storeState) {
        ModelState current = state;
        @Unmodifiable List<AccountListItem> mappedItems = mapItems(storeState.accounts());
        SourceSnapshot source = current.source().items().equals(mappedItems)
                ? current.source()
                : new SourceSnapshot(
                        Math.addExact(current.source().contentRevision(), 1L),
                        mappedItems);
        AccountsSnapshot replacement = new AccountsSnapshot(
                selectedIndex(source.items(), storeState.selectedAccountId()),
                source.items().size(),
                source.contentRevision());
        state = new ModelState(source, replacement);
        return new SnapshotTransition(current.snapshot(), replacement);
    }

    /// Creates revision zero from the first immutable store snapshot.
    ///
    /// @param storeState first store state
    /// @return initial atomically consistent model state
    private static ModelState initialState(AccountStoreState storeState) {
        Objects.requireNonNull(storeState, "storeState");
        SourceSnapshot source = new SourceSnapshot(0L, mapItems(storeState.accounts()));
        AccountsSnapshot snapshot = new AccountsSnapshot(
                selectedIndex(source.items(), storeState.selectedAccountId()),
                source.items().size(),
                source.contentRevision());
        return new ModelState(source, snapshot);
    }

    /// Copies presentation-safe descriptors into immutable viewport rows.
    ///
    /// @param descriptors immutable account descriptors
    /// @return immutable viewport rows
    private static @Unmodifiable List<AccountListItem> mapItems(
            @Unmodifiable List<AccountDescriptor> descriptors) {
        List<AccountListItem> items = new ArrayList<>(descriptors.size());
        for (AccountDescriptor descriptor : descriptors) {
            items.add(new AccountListItem(
                    descriptor.id(),
                    descriptor.title(),
                    descriptor.detail(),
                    descriptor.profileId()));
        }
        return List.copyOf(items);
    }

    /// Finds the selected identifier in current immutable source order.
    ///
    /// @param items immutable account rows
    /// @param selectedAccountId selected stable identifier, or null for none
    /// @return selected source index, or empty when the identifier is absent
    private static OptionalInt selectedIndex(
            @Unmodifiable List<AccountListItem> items,
            @Nullable String selectedAccountId) {
        int index = indexOf(items, selectedAccountId);
        return index < 0 ? OptionalInt.empty() : OptionalInt.of(index);
    }

    /// Finds a stable identifier in immutable source order.
    ///
    /// @param items immutable account rows
    /// @param accountId stable identifier, or null for none
    /// @return zero-based source index, or -1 when absent
    private static int indexOf(
            @Unmodifiable List<AccountListItem> items,
            @Nullable String accountId) {
        if (accountId == null) {
            return -1;
        }
        for (int index = 0; index < items.size(); index++) {
            if (items.get(index).accountId().equals(accountId)) {
                return index;
            }
        }
        return -1;
    }

    /// Rejects a load cancelled by either the viewport or model lifecycle.
    ///
    /// @param cancellation viewport cancellation signal
    private void checkLoadActive(LoadCancellation cancellation) {
        if (closed || cancellation.isCancelled()) {
            throw new CancellationException("Viewport account load is no longer active");
        }
    }

    /// Rejects commands, range loads, and subscriptions after closure.
    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("Launcher accounts model is closed");
        }
    }

    /// Requires an open model containing one exact stable account identifier.
    ///
    /// @param accountId stable identifier to validate
    private void validateCurrentAccount(String accountId) {
        Objects.requireNonNull(accountId, "accountId");
        synchronized (stateLock) {
            requireOpen();
            if (indexOf(state.source().items(), accountId) < 0) {
                throw new IllegalArgumentException("Unknown account: " + accountId);
            }
        }
    }

    /// Exact immutable account rows associated with one content revision.
    ///
    /// @param contentRevision non-negative content revision
    /// @param items immutable presentation-safe rows in source order
    @NotNullByDefault
    private record SourceSnapshot(
            long contentRevision,
            @Unmodifiable List<AccountListItem> items) {
        /// Validates and defensively copies one source snapshot.
        private SourceSnapshot {
            if (contentRevision < 0L) {
                throw new IllegalArgumentException("Content revision cannot be negative");
            }
            items = List.copyOf(items);
        }
    }

    /// Atomically published source revision and its exactly matching minimal state.
    ///
    /// @param source exact indexed source
    /// @param snapshot matching account snapshot
    @NotNullByDefault
    private record ModelState(SourceSnapshot source, AccountsSnapshot snapshot) {
        /// Validates count and revision agreement between both state halves.
        private ModelState {
            if (source.items().size() != snapshot.itemCount()) {
                throw new IllegalArgumentException("Snapshot item count must match source items");
            }
            if (source.contentRevision() != snapshot.contentRevision()) {
                throw new IllegalArgumentException("Snapshot revision must match source revision");
            }
        }
    }

    /// One already-applied snapshot transition awaiting publication outside the model lock.
    ///
    /// @param previous snapshot before the state replacement
    /// @param replacement snapshot after the state replacement
    @NotNullByDefault
    private record SnapshotTransition(
            AccountsSnapshot previous,
            AccountsSnapshot replacement) {
        /// Validates one publication transition.
        private SnapshotTransition {
            Objects.requireNonNull(previous, "previous");
            Objects.requireNonNull(replacement, "replacement");
        }
    }
}
