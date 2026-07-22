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
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import space.minecraftstl.xyml.observable.Subscription;
import space.minecraftstl.xyml.observable.ValueChangeListener;
import space.minecraftstl.xyml.observable.ValueChangeSupport;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests launch readiness mapping, command gating, and selection subscription ownership.
@NotNullByDefault
public final class LauncherHomeModelTest {
    /// Localized readiness strings used by every focused model test.
    private static final HomeStatusStrings STATUS_STRINGS =
            new HomeStatusStrings("Ready", "Choose an account", "Choose an instance");

    /// Missing selections map deterministically, with the account requirement taking precedence.
    @Test
    public void mapsMissingSelectionsAndReadyTransition() {
        FakeSelectionStore store = new FakeSelectionStore(new HomeSelectionState("", "", "", ""));
        AtomicReference<HomeSnapshot> published = new AtomicReference<>();
        LauncherHomeModel model = createModel(store, new AtomicInteger(), new AtomicInteger(),
                new AtomicInteger(), new AtomicInteger());
        Subscription subscription = model.subscribe(change -> published.set(change.currentValue()));

        HomeSnapshot missingAccount = model.snapshot();
        store.publish(new HomeSelectionState("Alex", "microsoft", "", ""));
        HomeSnapshot missingInstance = model.snapshot();
        store.publish(new HomeSelectionState("Alex", "microsoft", "1.21.1", "Default directory"));
        HomeSnapshot ready = model.snapshot();

        assertAll(
                () -> assertEquals("Choose an account", missingAccount.statusText()),
                () -> assertFalse(missingAccount.launchEnabled()),
                () -> assertEquals("Choose an instance", missingInstance.statusText()),
                () -> assertFalse(missingInstance.launchEnabled()),
                () -> assertEquals("Ready", ready.statusText()),
                () -> assertTrue(ready.launchEnabled()),
                () -> assertEquals("Alex", ready.accountName()),
                () -> assertEquals("1.21.1", ready.instanceName()),
                () -> assertEquals(ready, published.get()));

        subscription.unsubscribe();
        model.close();
    }

    /// Navigation commands always delegate, while launch delegates only after both selections exist.
    @Test
    public void delegatesCommandsAndGatesLaunch() {
        FakeSelectionStore store = new FakeSelectionStore(new HomeSelectionState("", "", "", ""));
        AtomicInteger accountSelections = new AtomicInteger();
        AtomicInteger instanceSelections = new AtomicInteger();
        AtomicInteger instanceAdditions = new AtomicInteger();
        AtomicInteger launches = new AtomicInteger();
        LauncherHomeModel model = createModel(
                store, accountSelections, instanceSelections, instanceAdditions, launches);

        model.selectAccount();
        model.selectInstance();
        model.addInstance();
        model.launch();
        store.publish(new HomeSelectionState("Steve", "offline", "1.20.1", "Games"));
        model.launch();

        assertAll(
                () -> assertEquals(1, accountSelections.get()),
                () -> assertEquals(1, instanceSelections.get()),
                () -> assertEquals(1, instanceAdditions.get()),
                () -> assertEquals(1, launches.get()));
        model.close();
    }

    /// Closing removes the owned store registration and rejects every future command or subscription.
    @Test
    public void closesSubscriptionAndRejectsFurtherUse() {
        FakeSelectionStore store = new FakeSelectionStore(
                new HomeSelectionState("Alex", "microsoft", "1.21.1", "Games"));
        AtomicInteger launches = new AtomicInteger();
        LauncherHomeModel model = createModel(
                store, new AtomicInteger(), new AtomicInteger(), new AtomicInteger(), launches);
        HomeSnapshot beforeClose = model.snapshot();

        model.close();
        model.close();
        store.publish(new HomeSelectionState("Steve", "offline", "1.20.1", "Other"));

        assertAll(
                () -> assertFalse(store.hasSubscribers()),
                () -> assertEquals(beforeClose, model.snapshot()),
                () -> assertThrows(IllegalStateException.class, model::selectAccount),
                () -> assertThrows(IllegalStateException.class, model::selectInstance),
                () -> assertThrows(IllegalStateException.class, model::addInstance),
                () -> assertThrows(IllegalStateException.class, model::launch),
                () -> assertThrows(IllegalStateException.class, () -> model.subscribe(change -> { })),
                () -> assertEquals(0, launches.get()));
    }

    /// A transition occurring between the initial snapshot and registration is recovered by post-subscribe reconciliation.
    @Test
    public void reconcilesTransitionDuringSubscription() {
        FakeSelectionStore store = new FakeSelectionStore(new HomeSelectionState("", "", "", ""));
        HomeSelectionState readySelection =
                new HomeSelectionState("Alex", "microsoft", "1.21.1", "Games");
        store.transitionBeforeNextSubscription(readySelection);

        LauncherHomeModel model = createModel(store, new AtomicInteger(), new AtomicInteger(),
                new AtomicInteger(), new AtomicInteger());

        assertAll(
                () -> assertEquals("Alex", model.snapshot().accountName()),
                () -> assertEquals("1.21.1", model.snapshot().instanceName()),
                () -> assertTrue(model.snapshot().launchEnabled()));
        model.close();
    }

    /// Creates a model whose commands increment the supplied counters.
    ///
    /// @param store fake selection source
    /// @param accountSelections account-navigation count
    /// @param instanceSelections instance-navigation count
    /// @param instanceAdditions add-instance count
    /// @param launches launch count
    /// @return configured home model
    private static LauncherHomeModel createModel(
            FakeSelectionStore store,
            AtomicInteger accountSelections,
            AtomicInteger instanceSelections,
            AtomicInteger instanceAdditions,
            AtomicInteger launches) {
        return new LauncherHomeModel(
                store,
                STATUS_STRINGS,
                accountSelections::incrementAndGet,
                instanceSelections::incrementAndGet,
                instanceAdditions::incrementAndGet,
                launches::incrementAndGet);
    }

    /// Mutable toolkit-neutral selection store used to verify model behavior.
    @NotNullByDefault
    private static final class FakeSelectionStore implements HomeSelectionStore {
        /// Selection transition publisher.
        private final ValueChangeSupport<HomeSelectionState> changes = new ValueChangeSupport<>(this);

        /// Latest fake selection state.
        private final AtomicReference<HomeSelectionState> current;

        /// Replacement installed immediately before the next listener registration, or null when absent.
        private final AtomicReference<@Nullable HomeSelectionState> transitionBeforeSubscription =
                new AtomicReference<>();

        /// Creates a fake store with one initial selection state.
        ///
        /// @param initialState initial selection state
        private FakeSelectionStore(HomeSelectionState initialState) {
            current = new AtomicReference<>(initialState);
        }

        /// Returns the latest fake state.
        @Override
        public HomeSelectionState snapshot() {
            return current.get();
        }

        /// Registers a fake selection listener.
        @Override
        public Subscription subscribe(ValueChangeListener<HomeSelectionState> listener) {
            @Nullable HomeSelectionState replacement = transitionBeforeSubscription.getAndSet(null);
            if (replacement != null) {
                current.set(replacement);
            }
            return changes.subscribe(listener);
        }

        /// Publishes one replacement state synchronously.
        ///
        /// @param replacement replacement selection state
        private void publish(HomeSelectionState replacement) {
            HomeSelectionState previous = current.getAndSet(replacement);
            changes.fireChange(previous, replacement);
        }

        /// Schedules one unannounced state transition at the next subscription boundary.
        ///
        /// @param replacement replacement state
        private void transitionBeforeNextSubscription(HomeSelectionState replacement) {
            transitionBeforeSubscription.set(replacement);
        }

        /// Returns whether the model still owns a listener registration.
        ///
        /// @return true when at least one registration remains
        private boolean hasSubscribers() {
            return changes.hasSubscribers();
        }
    }
}
