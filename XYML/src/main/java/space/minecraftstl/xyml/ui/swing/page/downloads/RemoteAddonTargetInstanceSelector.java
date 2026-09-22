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
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;
import space.minecraftstl.xyml.ui.swing.SwingUiDispatcher;
import space.minecraftstl.xyml.ui.swing.page.instances.InstanceSearchEntry;
import space.minecraftstl.xyml.ui.swing.page.instances.InstancesModel;
import space.minecraftstl.xyml.ui.swing.page.instances.InstancesSnapshot;

import javax.swing.DefaultListCellRenderer;
import javax.swing.JComboBox;
import javax.swing.JList;
import java.awt.Component;
import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;

/// Keeps a download page's target-instance choice independent from the launcher's global selection.
///
/// The selector borrows an application-owned [InstancesModel]. Content refreshes retain a still-existing local
/// choice, while a game-directory context change resets it to that directory's current instance. Selecting an item
/// never calls [InstancesModel#selectInstance(GameInstanceID)].
@NotNullByDefault
final class RemoteAddonTargetInstanceSelector implements AutoCloseable {
    /// Borrowed application-owned installed-instance model.
    private final InstancesModel model;

    /// Compact local selector rendered with each instance's cheap display name.
    private final JComboBox<InstanceSearchEntry> comboBox = new JComboBox<>();

    /// Independently removable model listener, or null before the page becomes displayable.
    private @Nullable Subscription modelSubscription;

    /// Whether the borrowed model has been attached for this visible page.
    private boolean started;

    /// Last repository context represented by the local items.
    private long selectionContextRevision = -1L;

    /// Local target retained across ordinary instance-list refreshes.
    private @Nullable GameInstanceID selectedInstanceId;

    /// Whether combo-box events currently reflect an internal item publication.
    private boolean applyingEntries;

    /// Whether model notifications and user selections are permanently rejected.
    private volatile boolean closed;

    /// Creates a local target selector around a borrowed installed-instance model.
    ///
    /// @param model application-owned source of installed-instance identities
    RemoteAddonTargetInstanceSelector(InstancesModel model) {
        EdtDispatcher.requireEventDispatchThread();
        this.model = Objects.requireNonNull(model, "model");
        comboBox.setRenderer(new InstanceRenderer());
        comboBox.setMinimumSize(new java.awt.Dimension(0, 0));
        comboBox.addActionListener(event -> selectedEntryChanged());
    }

    /// Returns the stable Swing control embedded by the owning catalog panel.
    ///
    /// @return local target-instance combo box
    JComboBox<InstanceSearchEntry> component() {
        return comboBox;
    }

    /// Attaches the borrowed model when the owning page becomes displayable.
    ///
    /// Delaying this operation keeps page construction lazy: composing a download page must not invoke
    /// any instance-model method before Swing has attached that page to the window.
    void start() {
        EdtDispatcher.requireEventDispatchThread();
        if (closed || started) {
            return;
        }
        Subscription subscription = Objects.requireNonNull(
                model.subscribe(change -> SwingUiDispatcher.INSTANCE.dispatchOrRun(this::synchronizeFromModel)),
                "instances model returned null subscription");
        modelSubscription = subscription;
        started = true;
        synchronizeFromModel();
    }

    /// Returns the exact locally selected instance without changing global launcher state.
    ///
    /// @return selected instance ID, or null when the current directory has no selected target
    @Nullable GameInstanceID selectedInstanceId() {
        EdtDispatcher.requireEventDispatchThread();
        return selectedInstanceId;
    }

    /// Reconciles local options with the model's latest repository context and content snapshot.
    void synchronizeFromModel() {
        EdtDispatcher.requireEventDispatchThread();
        if (closed || !started) {
            return;
        }
        InstancesSnapshot snapshot = model.snapshot();
        @Unmodifiable List<InstanceSearchEntry> entries = model.searchEntries();
        if (entries.size() != snapshot.itemCount()) {
            return;
        }

        long currentContextRevision = model.selectionContextRevision();
        @Nullable GameInstanceID retainedSelection = currentContextRevision == selectionContextRevision
                ? selectedInstanceId
                : null;
        @Nullable GameInstanceID preferredSelection = preferredSelection(snapshot, entries);
        @Nullable GameInstanceID nextSelection = contains(entries, retainedSelection)
                ? retainedSelection
                : preferredSelection;

        applyingEntries = true;
        try {
            comboBox.removeAllItems();
            for (InstanceSearchEntry entry : entries) {
                comboBox.addItem(entry);
            }
            selectEntry(nextSelection);
        } finally {
            applyingEntries = false;
        }
        selectionContextRevision = currentContextRevision;
        selectedInstanceId = nextSelection;
    }

    /// Releases the borrowed model subscription without closing the application-owned model.
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        @Nullable Subscription subscription = modelSubscription;
        modelSubscription = null;
        if (subscription != null) {
            subscription.unsubscribe();
        }
    }

    /// Captures one explicit combo-box selection as local page state.
    private void selectedEntryChanged() {
        EdtDispatcher.requireEventDispatchThread();
        if (closed || applyingEntries) {
            return;
        }
        @Nullable Object selected = comboBox.getSelectedItem();
        selectedInstanceId = selected instanceof InstanceSearchEntry entry ? entry.stableId() : null;
    }

    /// Returns the model-selected instance represented by one consistent identity list.
    ///
    /// @param snapshot current exact model snapshot
    /// @param entries current cheap identity list
    /// @return current model selection, or null when no row is selected
    private static @Nullable GameInstanceID preferredSelection(
            InstancesSnapshot snapshot,
            @Unmodifiable List<InstanceSearchEntry> entries) {
        OptionalInt selectedIndex = snapshot.selectedIndex();
        return selectedIndex.isPresent() ? entries.get(selectedIndex.getAsInt()).stableId() : null;
    }

    /// Returns whether an identity list contains one optional instance ID.
    ///
    /// @param entries current cheap identity list
    /// @param instanceId optional retained local ID
    /// @return true only when the non-null ID remains present
    private static boolean contains(
            @Unmodifiable List<InstanceSearchEntry> entries,
            @Nullable GameInstanceID instanceId) {
        return instanceId != null && entries.stream().anyMatch(entry -> entry.stableId().equals(instanceId));
    }

    /// Selects the entry matching one optional instance ID in the freshly published combo-box model.
    ///
    /// @param instanceId desired local selection, or null to leave the selector empty
    private void selectEntry(@Nullable GameInstanceID instanceId) {
        comboBox.setSelectedItem(null);
        if (instanceId == null) {
            return;
        }
        for (int index = 0; index < comboBox.getItemCount(); index++) {
            InstanceSearchEntry entry = comboBox.getItemAt(index);
            if (entry.stableId().equals(instanceId)) {
                comboBox.setSelectedIndex(index);
                return;
            }
        }
    }

    /// Renders compact instance identities without exposing record implementation text.
    @NotNullByDefault
    private static final class InstanceRenderer extends DefaultListCellRenderer {
        /// Applies the instance display name to one combo-box row.
        ///
        /// @param list owning list
        /// @param value row value
        /// @param index row index, or -1 for the closed selector value
        /// @param isSelected whether the row is selected
        /// @param cellHasFocus whether the row owns keyboard focus
        /// @return configured renderer component
        @Override
        public Component getListCellRendererComponent(
                JList<?> list,
                @Nullable Object value,
                int index,
                boolean isSelected,
                boolean cellHasFocus) {
            super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
            setText(value instanceof InstanceSearchEntry entry ? entry.displayName() : "");
            return this;
        }
    }
}
