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
package space.minecraftstl.xyml.ui.swing.shell;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import space.minecraftstl.xyml.observable.Subscription;
import space.minecraftstl.xyml.observable.ValueChangeListener;
import space.minecraftstl.xyml.ui.swing.choice.ChoicePage;
import space.minecraftstl.xyml.ui.swing.choice.IndexRange;
import space.minecraftstl.xyml.ui.swing.choice.LoadCancellation;
import space.minecraftstl.xyml.ui.swing.page.accounts.AccountListItem;
import space.minecraftstl.xyml.ui.swing.page.accounts.AccountsModel;
import space.minecraftstl.xyml.ui.swing.page.accounts.AccountsSnapshot;

import javax.swing.SwingUtilities;
import java.awt.Dimension;
import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/// Verifies lazy account selection cannot bypass launcher interaction locks.
@NotNullByDefault
public final class LazyAccountSelectorTest {
    /// A placeholder row selected before loading must not commit after interaction becomes disabled.
    @Test
    public void rejectsLatePlaceholderSelectionAfterInteractionLock() throws Exception {
        DelayedAccountsModel model = new DelayedAccountsModel();
        AtomicReference<@Nullable LazyAccountSelector> selectorReference = new AtomicReference<>();

        SwingUtilities.invokeAndWait(() -> {
            LazyAccountSelector selector = new LazyAccountSelector(
                    model,
                    ShellRecentSelections.transientSelections(),
                    "Account",
                    "No account",
                    "Add account",
                    "Account list",
                    ignored -> { });
            selectorReference.set(selector);
            assertNull(model.requestedRange());

            selector.choiceList().setSize(new Dimension(320, 128));
            selector.choiceList().doLayout();
            selector.choiceList().getViewport().doLayout();
            selector.choiceList().refreshLoadPlan();
            assertNotNull(model.requestedRange());

            selector.choiceList().getList().setSelectedIndex(1);
            selector.setInteractionEnabled(false);
            assertFalse(selector.valueButton().isEnabled());
        });

        model.completeLoad();
        SwingUtilities.invokeAndWait(() -> { });
        assertEquals(0, model.selectionCount());

        SwingUtilities.invokeAndWait(() -> Objects.requireNonNull(selectorReference.get()).close());
    }

    /// Two-account source whose only range request remains pending until the test releases it.
    @NotNullByDefault
    private static final class DelayedAccountsModel implements AccountsModel {
        /// Stable immutable source rows.
        private static final @Unmodifiable List<AccountListItem> ACCOUNTS = List.of(
                new AccountListItem(
                        "account-1",
                        "Player One",
                        "Offline",
                        "00000000-0000-0000-0000-000000000001"),
                new AccountListItem(
                        "account-2",
                        "Player Two",
                        "Offline",
                        "00000000-0000-0000-0000-000000000002"));

        /// Pending load completion controlled by the test.
        private final CompletableFuture<ChoicePage<AccountListItem>> pendingLoad = new CompletableFuture<>();

        /// Requested range captured before completion.
        private final AtomicReference<@Nullable IndexRange> requestedRange = new AtomicReference<>();

        /// Number of account selections accepted by the model.
        private final AtomicInteger selections = new AtomicInteger();

        /// Returns the selected first row and exact two-row count.
        @Override
        public AccountsSnapshot snapshot() {
            return new AccountsSnapshot(OptionalInt.of(0), ACCOUNTS.size(), 0L);
        }

        /// Registers a no-op listener because this fixture never mutates its snapshot.
        ///
        /// @param listener required listener
        /// @return independently cancellable no-op registration
        @Override
        public Subscription subscribe(ValueChangeListener<AccountsSnapshot> listener) {
            Objects.requireNonNull(listener, "listener");
            return Subscription.create(() -> { });
        }

        /// Returns the exact source count.
        @Override
        public OptionalInt exactItemCount() {
            return OptionalInt.of(ACCOUNTS.size());
        }

        /// Captures the viewport request and keeps it pending.
        ///
        /// @param desiredRange measured viewport range
        /// @param cancellation cooperative cancellation signal
        /// @return caller-controlled pending completion
        @Override
        public CompletionStage<ChoicePage<AccountListItem>> load(
                IndexRange desiredRange,
                LoadCancellation cancellation) {
            Objects.requireNonNull(cancellation, "cancellation");
            if (!requestedRange.compareAndSet(null, Objects.requireNonNull(desiredRange, "desiredRange"))) {
                throw new AssertionError("Expected one account range request");
            }
            return pendingLoad;
        }

        /// Records a submitted account selection.
        ///
        /// @param accountId stable selected account identifier
        @Override
        public void selectAccount(String accountId) {
            Objects.requireNonNull(accountId, "accountId");
            selections.incrementAndGet();
        }

        /// Performs no add-account action in this selection-only fixture.
        @Override
        public void addAccount() {
        }

        /// Performs no removal in this selection-only fixture.
        ///
        /// @param accountId stable account identifier
        /// @param allowReadOnlyOverwrite ignored overwrite permission
        @Override
        public void removeAccount(String accountId, boolean allowReadOnlyOverwrite) {
            Objects.requireNonNull(accountId, "accountId");
        }

        /// Completes refresh immediately without external authentication.
        ///
        /// @param accountId stable account identifier
        /// @return already-completed refresh stage
        @Override
        public CompletionStage<Void> refreshAccount(String accountId) {
            Objects.requireNonNull(accountId, "accountId");
            return CompletableFuture.completedFuture(null);
        }

        /// Completes the captured range with its exact source slice.
        private void completeLoad() {
            IndexRange range = Objects.requireNonNull(requestedRange.get(), "account request was not issued");
            @Unmodifiable List<AccountListItem> items = List.copyOf(
                    ACCOUNTS.subList(range.startInclusive(), range.endExclusive()));
            pendingLoad.complete(new ChoicePage<>(
                    range,
                    items,
                    OptionalInt.of(ACCOUNTS.size()),
                    range.endExclusive() == ACCOUNTS.size()));
        }

        /// Returns the captured request range, or null before viewport demand.
        ///
        /// @return requested range or null
        private @Nullable IndexRange requestedRange() {
            return requestedRange.get();
        }

        /// Returns how many selections escaped into the model.
        ///
        /// @return selection command count
        private int selectionCount() {
            return selections.get();
        }
    }
}
