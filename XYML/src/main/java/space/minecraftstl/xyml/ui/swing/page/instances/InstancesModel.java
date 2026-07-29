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
package space.minecraftstl.xyml.ui.swing.page.instances;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.game.GameInstanceID;
import space.minecraftstl.xyml.observable.Subscription;
import space.minecraftstl.xyml.observable.ValueChangeListener;
import space.minecraftstl.xyml.ui.swing.choice.IdentifiedChoiceDataSource;

import java.util.List;

/// Supplies installed-instance state, viewport data, and commands without exposing JavaFX or Swing types.
///
/// Implementations must expose an exact item count and keep count and indexed order stable within one
/// [InstancesSnapshot#contentRevision()] value. [InstancesSnapshot#itemCount()] must equal [#exactItemCount()]
/// when that snapshot is current. Range loading must not block the Swing event dispatch thread.
@NotNullByDefault
public interface InstancesModel extends IdentifiedChoiceDataSource<InstanceListItem> {
    /// Returns the latest immutable instance-page state.
    ///
    /// @return current page snapshot
    InstancesSnapshot snapshot();

    /// Registers for future instance-page transitions on the publishing thread.
    ///
    /// @param listener snapshot transition listener
    /// @return independently cancellable listener registration
    Subscription subscribe(ValueChangeListener<InstancesSnapshot> listener);

    /// Returns cheap searchable identities in the exact current source order.
    ///
    /// Entries must align one-for-one with [#stableItemIds()] and must not resolve icons, inspect archives, or
    /// perform other row-detail I/O. This separate index lets a view search user-visible names without loading
    /// every installed instance.
    ///
    /// @return immutable current search index
    @Unmodifiable List<InstanceSearchEntry> searchEntries();

    /// Selects a loaded instance by its stable repository identifier.
    ///
    /// @param instanceId stable instance identifier
    void selectInstance(GameInstanceID instanceId);

    /// Refreshes installed instances from their repository.
    void refreshInstances();

    /// Opens the new-instance workflow.
    void addInstance();

    /// Opens management for the currently selected instance.
    void manageSelectedInstance();

    /// Returns a revision that changes when selection IDs must be interpreted in a new repository context.
    ///
    /// Static repository models retain zero. A directory-switching model increments this value so a persistent
    /// instances page can replace management even when two directories select the same row index or instance ID.
    ///
    /// @return non-negative selection-context revision
    default long selectionContextRevision() {
        return 0L;
    }
}
