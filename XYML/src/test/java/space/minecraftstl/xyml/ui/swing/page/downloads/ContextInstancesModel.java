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
package space.minecraftstl.xyml.ui.swing.page.downloads;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.game.GameInstanceID;
import space.minecraftstl.xyml.observable.Subscription;
import space.minecraftstl.xyml.observable.ValueChangeListener;
import space.minecraftstl.xyml.ui.swing.choice.ChoicePage;
import space.minecraftstl.xyml.ui.swing.choice.IndexRange;
import space.minecraftstl.xyml.ui.swing.choice.LoadCancellation;
import space.minecraftstl.xyml.ui.swing.page.instances.InstanceAddonContext;
import space.minecraftstl.xyml.ui.swing.page.instances.InstanceListItem;
import space.minecraftstl.xyml.ui.swing.page.instances.InstanceSearchEntry;
import space.minecraftstl.xyml.ui.swing.page.instances.InstancesModel;
import space.minecraftstl.xyml.ui.swing.page.instances.InstancesSnapshot;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/// Installed-instance test model supplying one fixed analyzed add-on context per instance.
@NotNullByDefault
final class ContextInstancesModel implements InstancesModel {
    /// Current source-aligned search entries.
    private final @Unmodifiable List<InstanceSearchEntry> entries;

    /// Analyzed add-on context per instance identifier.
    private final @Unmodifiable Map<GameInstanceID, InstanceAddonContext> contexts;

    /// Current immutable instance snapshot.
    private InstancesSnapshot snapshot;

    /// Captured view listener, or null after unsubscription.
    private @Nullable ValueChangeListener<InstancesSnapshot> listener;

    /// Creates a model with one current global selection.
    ///
    /// @param entries initial immutable entries
    /// @param selectedIndex initially selected source index
    /// @param contexts analyzed add-on context per instance identifier
    ContextInstancesModel(
            List<InstanceSearchEntry> entries,
            int selectedIndex,
            Map<GameInstanceID, InstanceAddonContext> contexts) {
        this.entries = List.copyOf(entries);
        this.contexts = Map.copyOf(contexts);
        snapshot = snapshot(selectedIndex, this.entries.size(), 0L);
    }

    /// Creates one cheap installed-instance identity.
    ///
    /// @param id stable instance ID
    /// @param name visible instance name
    /// @return immutable search entry
    static InstanceSearchEntry entry(GameInstanceID id, String name) {
        return new InstanceSearchEntry(Objects.requireNonNull(id, "id"), Objects.requireNonNull(name, "name"));
    }

    /// Returns the latest exact snapshot.
    @Override
    public InstancesSnapshot snapshot() {
        return snapshot;
    }

    /// Captures the view listener and records unsubscription.
    ///
    /// @param listener snapshot transition listener
    /// @return independently cancellable registration
    @Override
    public Subscription subscribe(ValueChangeListener<InstancesSnapshot> listener) {
        this.listener = Objects.requireNonNull(listener, "listener");
        return Subscription.create(() -> this.listener = null);
    }

    /// Reports the current exact item count.
    @Override
    public OptionalInt exactItemCount() {
        return OptionalInt.of(entries.size());
    }

    /// Returns current cheap identities in exact source order.
    @Override
    public @Unmodifiable List<InstanceSearchEntry> searchEntries() {
        return entries;
    }

    /// Returns an unused failed row-load stage for this context-only fake.
    ///
    /// @param desiredRange unused requested range
    /// @param cancellation unused cancellation signal
    /// @return failed stage because this fake supplies identities only
    @Override
    public CompletionStage<ChoicePage<InstanceListItem>> load(
            IndexRange desiredRange,
            LoadCancellation cancellation) {
        Objects.requireNonNull(desiredRange, "desiredRange");
        Objects.requireNonNull(cancellation, "cancellation");
        return CompletableFuture.failedFuture(new UnsupportedOperationException("Rows are not loaded"));
    }

    /// Accepts an unused shared-selection command.
    ///
    /// @param instanceId selected stable identifier
    @Override
    public void selectInstance(GameInstanceID instanceId) {
        Objects.requireNonNull(instanceId, "instanceId");
    }

    /// Accepts an unused refresh command.
    @Override
    public void refreshInstances() {
    }

    /// Accepts an unused add command.
    @Override
    public void addInstance() {
    }

    /// Accepts an unused management command.
    @Override
    public void manageSelectedInstance() {
    }

    /// Returns the configured analyzed context for one instance.
    ///
    /// @param instanceId stable instance identifier
    /// @return completed analyzed context, or an unresolved one for an unknown instance
    @Override
    public CompletionStage<InstanceAddonContext> resolveAddonContext(GameInstanceID instanceId) {
        GameInstanceID checked = Objects.requireNonNull(instanceId, "instanceId");
        @Nullable InstanceAddonContext context = contexts.get(checked);
        return CompletableFuture.completedFuture(
                context == null ? new InstanceAddonContext(checked, null, null) : context);
    }

    /// Creates one enabled exact-count snapshot.
    ///
    /// @param selectedIndex selected source index
    /// @param itemCount exact item count
    /// @param contentRevision matching content revision
    /// @return immutable enabled snapshot
    private static InstancesSnapshot snapshot(int selectedIndex, int itemCount, long contentRevision) {
        return new InstancesSnapshot(
                OptionalInt.of(selectedIndex),
                itemCount,
                contentRevision,
                "Ready",
                false,
                true,
                true,
                true,
                true);
    }
}
