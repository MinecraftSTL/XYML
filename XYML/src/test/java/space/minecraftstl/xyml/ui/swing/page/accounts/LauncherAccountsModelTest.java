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
import org.junit.jupiter.api.Test;
import space.minecraftstl.xyml.observable.Subscription;
import space.minecraftstl.xyml.observable.ValueChangeListener;
import space.minecraftstl.xyml.observable.ValueChangeSupport;
import space.minecraftstl.xyml.ui.swing.choice.ChoicePage;
import space.minecraftstl.xyml.ui.swing.choice.IndexRange;
import space.minecraftstl.xyml.ui.swing.choice.LoadCancellation;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests exact account ranges, content revisions, stable selection, and lifecycle boundaries.
@NotNullByDefault
public final class LauncherAccountsModelTest {
    /// Initial state exposes exact count and returns only the requested clamped text range.
    @Test
    public void exposesExactStateAndCompletedRangeSlices() {
        FakeAccountStore store = new FakeAccountStore(state(
                List.of(
                        account("alpha", "Alex", "Microsoft", "profile-alpha"),
                        account("beta", "Steve", "Offline", "profile-beta"),
                        account("gamma", "Sunny", "External", "profile-gamma")),
                "beta"));
        LauncherAccountsModel model = new LauncherAccountsModel(store, () -> { });

        CompletionStage<ChoicePage<AccountListItem>> load = model.load(
                new IndexRange(1, 8), new LoadCancellation());
        ChoicePage<AccountListItem> page = load.toCompletableFuture().join();

        assertAll(
                () -> assertEquals(new AccountsSnapshot(OptionalInt.of(1), 3, 0L), model.snapshot()),
                () -> assertEquals(OptionalInt.of(3), model.exactItemCount()),
                () -> assertTrue(load.toCompletableFuture().isDone()),
                () -> assertEquals(new IndexRange(1, 3), page.range()),
                () -> assertEquals(List.of("beta", "gamma"), page.items().stream()
                        .map(AccountListItem::accountId)
                        .toList()),
                () -> assertEquals("profile-beta", page.items().get(0).profileId()),
                () -> assertEquals(OptionalInt.of(3), page.exactItemCount()),
                () -> assertTrue(page.endOfData()));
        model.close();
    }

    /// Selection changes retain the revision while count, order, and row text changes increment it.
    @Test
    public void distinguishesSelectionFromContentChanges() {
        @Unmodifiable List<AccountDescriptor> original = List.of(
                account("alpha", "Alex", "Microsoft", "profile-alpha"),
                account("beta", "Steve", "Offline", "profile-beta"));
        FakeAccountStore store = new FakeAccountStore(state(original, "alpha"));
        LauncherAccountsModel model = new LauncherAccountsModel(store, () -> { });
        AtomicInteger publications = new AtomicInteger();
        Subscription subscription = model.subscribe(change -> publications.incrementAndGet());

        store.publish(state(original, "beta"));
        AccountsSnapshot selected = model.snapshot();
        store.publish(state(List.of(
                account("alpha", "Alex", "Microsoft account", "profile-alpha"),
                original.get(1)), "beta"));
        AccountsSnapshot textChanged = model.snapshot();
        store.publish(state(List.of(
                original.get(1),
                account("alpha", "Alex", "Microsoft account", "profile-alpha")), "beta"));
        AccountsSnapshot reordered = model.snapshot();
        store.publish(store.snapshot());

        assertAll(
                () -> assertEquals(OptionalInt.of(1), selected.selectedIndex()),
                () -> assertEquals(0L, selected.contentRevision()),
                () -> assertEquals(1L, textChanged.contentRevision()),
                () -> assertEquals(2L, reordered.contentRevision()),
                () -> assertEquals(OptionalInt.of(0), reordered.selectedIndex()),
                () -> assertEquals(3, publications.get()));
        subscription.unsubscribe();
        model.close();
    }

    /// A page obtained from an older content revision remains detached from later store text.
    @Test
    public void oldRangeRetainsCapturedPureText() {
        FakeAccountStore store = new FakeAccountStore(state(
                List.of(account("alpha", "Old name", "Offline", "profile-alpha")),
                "alpha"));
        LauncherAccountsModel model = new LauncherAccountsModel(store, () -> { });
        ChoicePage<AccountListItem> oldPage = model.load(
                new IndexRange(0, 1), new LoadCancellation()).toCompletableFuture().join();

        store.publish(state(
                List.of(account("alpha", "New name", "Offline", "profile-alpha")),
                "alpha"));
        ChoicePage<AccountListItem> newPage = model.load(
                new IndexRange(0, 1), new LoadCancellation()).toCompletableFuture().join();

        assertAll(
                () -> assertEquals("Old name", oldPage.items().get(0).displayName()),
                () -> assertEquals("New name", newPage.items().get(0).displayName()),
                () -> assertEquals(1L, model.snapshot().contentRevision()));
        model.close();
    }

    /// Avatar-source transitions propagate to viewport rows and advance the content revision.
    @Test
    public void propagatesAvatarSourceUpdates() {
        AccountAvatarSource firstSource = AccountAvatarSource.remoteOrDefault(
                "https://textures.example.invalid/first.png");
        AccountAvatarSource replacementSource = AccountAvatarSource.remoteOrDefault(
                "https://textures.example.invalid/replacement.png");
        AccountDescriptor first = new AccountDescriptor(
                "alpha",
                "Alex",
                "Microsoft",
                "profile-alpha",
                firstSource);
        FakeAccountStore store = new FakeAccountStore(state(List.of(first), "alpha"));
        LauncherAccountsModel model = new LauncherAccountsModel(store, () -> { });

        AccountListItem initial = model.load(new IndexRange(0, 1), new LoadCancellation())
                .toCompletableFuture()
                .join()
                .items()
                .get(0);
        store.publish(state(List.of(new AccountDescriptor(
                first.id(),
                first.title(),
                first.detail(),
                first.profileId(),
                replacementSource)), "alpha"));
        AccountListItem replacement = model.load(new IndexRange(0, 1), new LoadCancellation())
                .toCompletableFuture()
                .join()
                .items()
                .get(0);

        assertAll(
                () -> assertSame(firstSource, initial.avatarSource()),
                () -> assertSame(replacementSource, replacement.avatarSource()),
                () -> assertEquals(1L, model.snapshot().contentRevision()));
        model.close();
    }

    /// Known IDs delegate and update through the store, while an ID removed by a newer revision is rejected.
    @Test
    public void delegatesKnownSelectionAndRejectsStaleIdentifier() {
        AccountDescriptor alpha = account("alpha", "Alex", "Microsoft", "profile-alpha");
        AccountDescriptor beta = account("beta", "Steve", "Offline", "profile-beta");
        FakeAccountStore store = new FakeAccountStore(state(List.of(alpha, beta), "alpha"));
        LauncherAccountsModel model = new LauncherAccountsModel(store, () -> { });

        model.selectAccount("beta");
        AccountsSnapshot selected = model.snapshot();
        store.publish(state(List.of(alpha), "alpha"));

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> model.selectAccount("beta"));
        assertAll(
                () -> assertEquals(List.of("beta"), store.selectedIds()),
                () -> assertEquals(OptionalInt.of(1), selected.selectedIndex()),
                () -> assertEquals(0L, selected.contentRevision()),
                () -> assertTrue(failure.getMessage().contains("beta")));
        model.close();
    }

    /// The model delegates the real add-account command without adding invented state flags.
    @Test
    public void delegatesAddAccountCommand() {
        FakeAccountStore store = new FakeAccountStore(state(List.of(), null));
        AtomicInteger additions = new AtomicInteger();
        LauncherAccountsModel model = new LauncherAccountsModel(store, additions::incrementAndGet);

        model.addAccount();

        assertEquals(1, additions.get());
        model.close();
    }

    /// Removing the selected stable ID publishes new content and selects the first remaining row.
    @Test
    public void delegatesRemovalAndKeepsSelectionConsistent() {
        FakeAccountStore store = new FakeAccountStore(state(
                List.of(
                        account("alpha", "Alex", "Microsoft", "profile-alpha"),
                        account("beta", "Steve", "Offline", "profile-beta"),
                        account("gamma", "Sunny", "External", "profile-gamma")),
                "beta"));
        LauncherAccountsModel model = new LauncherAccountsModel(store, () -> { });

        model.removeAccount("beta", false);
        ChoicePage<AccountListItem> page = model.load(
                new IndexRange(0, 2), new LoadCancellation()).toCompletableFuture().join();

        assertAll(
                () -> assertEquals(List.of("beta"), store.removedIds()),
                () -> assertEquals(List.of(false), store.removalOverwritePermissions()),
                () -> assertEquals(new AccountsSnapshot(OptionalInt.of(0), 2, 1L), model.snapshot()),
                () -> assertEquals(List.of("alpha", "gamma"), page.items().stream()
                        .map(AccountListItem::accountId)
                        .toList()));
        model.close();
    }

    /// Refresh validates one current stable ID and preserves the injected asynchronous completion.
    @Test
    public void delegatesAsynchronousRefreshCommand() {
        FakeAccountStore store = new FakeAccountStore(state(
                List.of(account("alpha", "Alex", "Microsoft", "profile-alpha")),
                "alpha"));
        AtomicReference<@Nullable String> refreshedId = new AtomicReference<>();
        CompletableFuture<Void> pending = new CompletableFuture<>();
        LauncherAccountsModel model = new LauncherAccountsModel(
                store,
                () -> { },
                accountId -> {
                    refreshedId.set(accountId);
                    return pending;
                });

        CompletionStage<Void> completion = model.refreshAccount("alpha");

        assertAll(
                () -> assertEquals("alpha", refreshedId.get()),
                () -> assertSame(pending, completion),
                () -> assertFalse(completion.toCompletableFuture().isDone()));
        pending.complete(null);
        completion.toCompletableFuture().join();
        model.close();
    }

    /// Removal and refresh reject identifiers removed by a newer revision before delegation.
    @Test
    public void rejectsStaleManagementIdentifiers() {
        AccountDescriptor alpha = account("alpha", "Alex", "Microsoft", "profile-alpha");
        AccountDescriptor beta = account("beta", "Steve", "Offline", "profile-beta");
        FakeAccountStore store = new FakeAccountStore(state(List.of(alpha, beta), "alpha"));
        AtomicInteger refreshes = new AtomicInteger();
        LauncherAccountsModel model = new LauncherAccountsModel(
                store,
                () -> { },
                accountId -> {
                    refreshes.incrementAndGet();
                    return CompletableFuture.completedFuture(null);
                });
        store.publish(state(List.of(alpha), "alpha"));

        assertAll(
                () -> assertThrows(IllegalArgumentException.class,
                        () -> model.removeAccount("beta", false)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> model.refreshAccount("beta")),
                () -> assertEquals(List.of(), store.removedIds()),
                () -> assertEquals(0, refreshes.get()));
        model.close();
    }

    /// Cancellation returns an already-failed stage without reading a partial source range.
    @Test
    public void observesViewportCancellation() {
        FakeAccountStore store = new FakeAccountStore(state(
                List.of(account("alpha", "Alex", "Microsoft", "profile-alpha")),
                "alpha"));
        LauncherAccountsModel model = new LauncherAccountsModel(store, () -> { });
        LoadCancellation cancellation = new LoadCancellation();
        cancellation.cancel();

        CompletionStage<ChoicePage<AccountListItem>> load = model.load(
                new IndexRange(0, 1), cancellation);

        assertAll(
                () -> assertTrue(load.toCompletableFuture().isDone()),
                () -> assertThrows(CancellationException.class,
                        () -> load.toCompletableFuture().join()));
        model.close();
    }

    /// Construction reconciles a transition occurring after its first read but before subscription.
    @Test
    public void reconcilesTransitionDuringSubscription() {
        FakeAccountStore store = new FakeAccountStore(state(
                List.of(account("alpha", "Alex", "Microsoft", "profile-alpha")),
                "alpha"));
        store.transitionBeforeNextSubscription(state(
                List.of(
                        account("alpha", "Alex", "Microsoft", "profile-alpha"),
                        account("beta", "Steve", "Offline", "profile-beta")),
                "beta"));

        LauncherAccountsModel model = new LauncherAccountsModel(store, () -> { });

        assertEquals(new AccountsSnapshot(OptionalInt.of(1), 2, 1L), model.snapshot());
        model.close();
    }

    /// Closing is idempotent, detaches the store, freezes readable state, and rejects later work.
    @Test
    public void closesSubscriptionAndRejectsFurtherUse() {
        FakeAccountStore store = new FakeAccountStore(state(
                List.of(account("alpha", "Alex", "Microsoft", "profile-alpha")),
                "alpha"));
        AtomicInteger additions = new AtomicInteger();
        LauncherAccountsModel model = new LauncherAccountsModel(store, additions::incrementAndGet);
        AccountsSnapshot beforeClose = model.snapshot();

        model.close();
        model.close();
        store.publish(state(
                List.of(account("beta", "Steve", "Offline", "profile-beta")),
                "beta"));

        assertAll(
                () -> assertFalse(store.hasSubscribers()),
                () -> assertEquals(beforeClose, model.snapshot()),
                () -> assertEquals(OptionalInt.of(1), model.exactItemCount()),
                () -> assertThrows(IllegalStateException.class,
                        () -> model.subscribe(change -> { })),
                () -> assertThrows(IllegalStateException.class,
                        () -> model.load(new IndexRange(0, 1), new LoadCancellation())),
                () -> assertThrows(IllegalStateException.class,
                        () -> model.selectAccount("alpha")),
                () -> assertThrows(IllegalStateException.class, model::addAccount),
                () -> assertThrows(IllegalStateException.class,
                        () -> model.removeAccount("alpha", false)),
                () -> assertThrows(IllegalStateException.class,
                        () -> model.refreshAccount("alpha")),
                () -> assertEquals(0, additions.get()));
    }

    /// Store state defensively copies descriptors and rejects duplicate stable account IDs.
    @Test
    public void validatesImmutableUniqueStoreContent() {
        List<AccountDescriptor> mutable = new ArrayList<>();
        mutable.add(account("alpha", "Alex", "Microsoft", "profile-alpha"));
        AccountStoreState state = state(mutable, "missing");
        mutable.clear();

        assertAll(
                () -> assertEquals(1, state.accounts().size()),
                () -> assertEquals("missing", state.selectedAccountId()),
                () -> assertThrows(IllegalArgumentException.class, () -> state(List.of(
                        account("alpha", "Alex", "Microsoft", "profile-alpha"),
                        account("alpha", "Steve", "Offline", "profile-beta")), null)));
    }

    /// Creates one pure-text account descriptor.
    ///
    /// @param id stable account ID
    /// @param title display title
    /// @param detail provider and storage detail
    /// @param profileId stable profile ID
    /// @return immutable descriptor
    private static AccountDescriptor account(
            String id,
            String title,
            String detail,
            String profileId) {
        return new AccountDescriptor(id, title, detail, profileId);
    }

    /// Creates one immutable fake store state.
    ///
    /// @param accounts account descriptors
    /// @param selectedAccountId selected stable ID, or null for none
    /// @return immutable store state
    private static AccountStoreState state(
            @Unmodifiable List<AccountDescriptor> accounts,
            @Nullable String selectedAccountId) {
        return new AccountStoreState(accounts, selectedAccountId);
    }

    /// Mutable toolkit-neutral store used to verify model state and subscription ownership.
    @NotNullByDefault
    private static final class FakeAccountStore implements AccountStore {
        /// Account-state transition publisher.
        private final ValueChangeSupport<AccountStoreState> changes = new ValueChangeSupport<>(this);

        /// Latest fake store state.
        private final AtomicReference<AccountStoreState> current;

        /// Stable IDs delegated through selection commands.
        private final List<String> selectedIds = new ArrayList<>();

        /// Stable IDs delegated through permanent removal commands.
        private final List<String> removedIds = new ArrayList<>();

        /// Backup-and-overwrite permissions delegated with removals.
        private final List<Boolean> removalOverwritePermissions = new ArrayList<>();

        /// Replacement installed before the next subscription, or null when absent.
        private final AtomicReference<@Nullable AccountStoreState> transitionBeforeSubscription =
                new AtomicReference<>();

        /// Creates a fake store with one initial state.
        ///
        /// @param initialState initial account state
        private FakeAccountStore(AccountStoreState initialState) {
            current = new AtomicReference<>(initialState);
        }

        /// Returns the latest fake account state.
        @Override
        public AccountStoreState snapshot() {
            return current.get();
        }

        /// Registers a listener after applying any scheduled unannounced transition.
        @Override
        public Subscription subscribe(ValueChangeListener<AccountStoreState> listener) {
            @Nullable AccountStoreState replacement = transitionBeforeSubscription.getAndSet(null);
            if (replacement != null) {
                current.set(replacement);
            }
            return changes.subscribe(listener);
        }

        /// Records and publishes selection of a known stable ID.
        @Override
        public synchronized void selectAccount(String accountId) {
            selectedIds.add(accountId);
            AccountStoreState before = current.get();
            publish(new AccountStoreState(before.accounts(), accountId));
        }

        /// Removes one stable account and selects the first remaining descriptor.
        @Override
        public synchronized void removeAccount(String accountId, boolean allowReadOnlyOverwrite) {
            removedIds.add(accountId);
            removalOverwritePermissions.add(allowReadOnlyOverwrite);
            AccountStoreState before = current.get();
            @Unmodifiable List<AccountDescriptor> remaining = before.accounts().stream()
                    .filter(account -> !account.id().equals(accountId))
                    .toList();
            @Nullable String selected = Objects.equals(before.selectedAccountId(), accountId)
                    ? remaining.stream().findFirst().map(AccountDescriptor::id).orElse(null)
                    : before.selectedAccountId();
            publish(new AccountStoreState(remaining, selected));
        }

        /// Publishes one replacement store state synchronously.
        ///
        /// @param replacement replacement state
        private void publish(AccountStoreState replacement) {
            AccountStoreState previous = current.getAndSet(replacement);
            changes.fireChange(previous, replacement);
        }

        /// Schedules an unannounced transition at the next subscription boundary.
        ///
        /// @param replacement replacement state
        private void transitionBeforeNextSubscription(AccountStoreState replacement) {
            transitionBeforeSubscription.set(replacement);
        }

        /// Returns the immutable delegated selection history.
        ///
        /// @return stable selected IDs in call order
        private synchronized @Unmodifiable List<String> selectedIds() {
            return List.copyOf(selectedIds);
        }

        /// Returns the immutable delegated removal history.
        ///
        /// @return stable removed IDs in call order
        private synchronized @Unmodifiable List<String> removedIds() {
            return List.copyOf(removedIds);
        }

        /// Returns delegated backup-and-overwrite permissions.
        ///
        /// @return immutable permissions in call order
        private synchronized @Unmodifiable List<Boolean> removalOverwritePermissions() {
            return List.copyOf(removalOverwritePermissions);
        }

        /// Returns whether the model still owns a store registration.
        ///
        /// @return true when at least one registration remains
        private boolean hasSubscribers() {
            return changes.hasSubscribers();
        }
    }
}
