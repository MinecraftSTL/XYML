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
package space.minecraftstl.xyml.ui.swing.page.resourcepacks;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import space.minecraftstl.xyml.ui.swing.choice.ChoiceListEntry;
import space.minecraftstl.xyml.ui.swing.choice.ChoiceLoadStatus;
import space.minecraftstl.xyml.ui.swing.choice.RichChoiceListCellRenderer;

import javax.swing.JComponent;
import javax.swing.JList;
import javax.swing.TransferHandler;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.nio.file.Path;

/// Installs handle-only resource-pack priority dragging without exposing Swing to the model.
@NotNullByDefault
final class ResourcePackCatalogReorderSupport {
    /// Prevents utility-class construction.
    private ResourcePackCatalogReorderSupport() {
    }

    /// Installs drag support on the panel-owned enabled-row list.
    ///
    /// @param panel owning resource-pack catalog panel
    static void install(ResourcePackCatalogPanel panel) {
        JList<ChoiceListEntry<ResourcePackCatalogItem>> list = panel.resourcePackList();
        ResourcePackReorderDragController controller = new ResourcePackReorderDragController(panel);
        list.setDragEnabled(false);
        list.setDropMode(javax.swing.DropMode.INSERT);
        list.setTransferHandler(new ResourcePackReorderTransferHandler(panel));
        list.addMouseListener(controller);
        list.addMouseMotionListener(controller);
    }


    /// Mouse gesture controller that starts dragging only from an enabled row handle.
    @NotNullByDefault
    private static final class ResourcePackReorderDragController extends MouseAdapter {
        /// Owning catalog panel.
        private final ResourcePackCatalogPanel panel;

        /// Minimum pointer movement before a drag gesture starts.
        private static final int DRAG_THRESHOLD = 4;

        /// Armed row index, or -1 while inactive.
        private int armedIndex = -1;

        /// Pointer x-coordinate captured when armed.
        private int armedX;

        /// Pointer y-coordinate captured when armed.
        private int armedY;

        /// Whether the current armed gesture has started Swing drag-and-drop.
        private boolean dragging;

        /// Creates one controller for the owning panel.
        ///
        /// @param panel owning catalog panel
        private ResourcePackReorderDragController(ResourcePackCatalogPanel panel) {
            this.panel = panel;
        }

        /// Arms handle dragging only while this writable catalog accepts commands.
        @Override
        public void mousePressed(MouseEvent event) {
            if (!(event.getSource() instanceof JList<?> list)
                    || event.getButton() != MouseEvent.BUTTON1
                    || !panel.isReorderInteractionAllowed()
                    || !list.isEnabled()) {
                reset();
                return;
            }
            int index = list.locationToIndex(event.getPoint());
            if (!isHandleHit(list, index, event.getPoint())) {
                reset();
                return;
            }
            list.setSelectedIndex(index);
            armedIndex = index;
            armedX = event.getX();
            armedY = event.getY();
            dragging = false;
        }

        /// Starts Swing drag-and-drop after the pointer leaves the handle threshold.
        @Override
        public void mouseDragged(MouseEvent event) {
            if (armedIndex < 0 || dragging || !(event.getSource() instanceof JList<?> list)) {
                return;
            }
            if (Math.abs(event.getX() - armedX) + Math.abs(event.getY() - armedY)
                    < DRAG_THRESHOLD) {
                return;
            }
            dragging = true;
            @Nullable TransferHandler handler = list.getTransferHandler();
            if (handler != null) {
                handler.exportAsDrag(list, event, TransferHandler.MOVE);
            }
        }

        /// Clears one completed handle gesture.
        @Override
        public void mouseReleased(MouseEvent event) {
            reset();
        }

        /// Shows the hand cursor only while hovering a draggable enabled row handle.
        @Override
        public void mouseMoved(MouseEvent event) {
            if (event.getSource() instanceof JList<?> list) {
                int index = list.locationToIndex(event.getPoint());
                list.setCursor(isHandleHit(list, index, event.getPoint())
                        ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                        : Cursor.getDefaultCursor());
            }
        }

        /// Restores the default cursor when leaving the row surface.
        @Override
        public void mouseExited(MouseEvent event) {
            if (event.getSource() instanceof JList<?> list) {
                list.setCursor(Cursor.getDefaultCursor());
            }
            if (!dragging) {
                reset();
            }
        }

        /// Tests one point against the current enabled row handle.
        private boolean isHandleHit(JList<?> list, int index, Point point) {
            @Nullable ResourcePackCatalogSnapshot snapshot = panel.displayedSnapshotValue();
            if (!panel.isReorderInteractionAllowed() || snapshot == null
                    || index < 0 || index >= snapshot.enabledItemCount()
                    || index >= list.getModel().getSize()) {
                return false;
            }
            Object row = list.getModel().getElementAt(index);
            if (!(row instanceof ChoiceListEntry<?> entry)
                    || entry.status() != ChoiceLoadStatus.LOADED
                    || !(entry.value() instanceof ResourcePackCatalogItem item)
                    || !item.enabled()) {
                return false;
            }
            @Nullable Rectangle bounds = list.getCellBounds(index, index);
            return bounds != null && RichChoiceListCellRenderer.dragHandleBounds(bounds).contains(point);
        }

        /// Clears the current armed gesture state.
        private void reset() {
            armedIndex = -1;
            dragging = false;
        }
    }

    /// Reorders one enabled loaded row within the current enabled prefix.
    @NotNullByDefault
    private static final class ResourcePackReorderTransferHandler extends TransferHandler {
        /// Owning catalog panel.
        private final ResourcePackCatalogPanel panel;

        /// Source list of the active drag, or null outside a drag.
        private @Nullable Component dragSource;

        /// Stable path captured when the active drag starts, or null outside a drag.
        private @Nullable Path draggedPath;

        /// Creates one handler for the owning panel.
        ///
        /// @param panel owning catalog panel
        private ResourcePackReorderTransferHandler(ResourcePackCatalogPanel panel) {
            this.panel = panel;
        }

        /// Creates one path transfer for the loaded selected row.
        @Override
        protected Transferable createTransferable(JComponent component) {
            JList<ChoiceListEntry<ResourcePackCatalogItem>> list = panel.resourcePackList();
            if (!panel.isReorderInteractionAllowed() || component != list) {
                return null;
            }
            @Nullable ChoiceListEntry<ResourcePackCatalogItem> selectedEntry = list.getSelectedValue();
            @Nullable ResourcePackCatalogItem selected = selectedEntry == null ? null : selectedEntry.value();
            @Nullable ResourcePackCatalogSnapshot snapshot = panel.displayedSnapshotValue();
            if (selected == null || snapshot == null || !selected.enabled()) {
                return null;
            }
            int selectedIndex = list.getSelectedIndex();
            if (selectedIndex < 0 || selectedIndex >= snapshot.enabledItemCount()) {
                return null;
            }
            dragSource = component;
            draggedPath = selected.path();
            return new StringSelection(draggedPath.toString());
        }

        /// Enables move semantics for the resource-pack list.
        @Override
        public int getSourceActions(JComponent component) {
            return MOVE;
        }

        /// Accepts drops only for the same reorderable row and enabled prefix.
        @Override
        public boolean canImport(TransferSupport support) {
            JList<ChoiceListEntry<ResourcePackCatalogItem>> list = panel.resourcePackList();
            if (!panel.isReorderInteractionAllowed()
                    || !support.isDrop()
                    || !support.isDataFlavorSupported(DataFlavor.stringFlavor)
                    || support.getComponent() != dragSource
                    || dragSource != list
                    || draggedPath == null) {
                return false;
            }
            @Nullable ResourcePackCatalogSnapshot snapshot = panel.displayedSnapshotValue();
            if (snapshot == null || !transferredPathMatches(support)) {
                return false;
            }
            int sourceIndex = list.getSelectedIndex();
            JList.DropLocation location = (JList.DropLocation) support.getDropLocation();
            int targetIndex = dropTargetIndex(sourceIndex, location.getIndex(), snapshot.enabledItemCount());
            return targetIndex >= 0 && targetIndex != sourceIndex;
        }

        /// Applies one accepted drop through the panel write gate.
        @Override
        public boolean importData(TransferSupport support) {
            if (!canImport(support)) {
                return false;
            }
            JList<ChoiceListEntry<ResourcePackCatalogItem>> list = panel.resourcePackList();
            @Nullable ChoiceListEntry<ResourcePackCatalogItem> selectedEntry = list.getSelectedValue();
            @Nullable ResourcePackCatalogItem selected = selectedEntry == null ? null : selectedEntry.value();
            @Nullable ResourcePackCatalogSnapshot snapshot = panel.displayedSnapshotValue();
            if (selected == null || snapshot == null || draggedPath == null
                    || !selected.path().equals(draggedPath)) {
                return false;
            }
            JList.DropLocation location = (JList.DropLocation) support.getDropLocation();
            int targetIndex = dropTargetIndex(list.getSelectedIndex(), location.getIndex(), snapshot.enabledItemCount());
            return targetIndex >= 0 && panel.reorderResourcePackToIndex(selected, targetIndex);
        }

        /// Clears one completed or cancelled drag.
        @Override
        protected void exportDone(JComponent source, Transferable data, int action) {
            dragSource = null;
            draggedPath = null;
            super.exportDone(source, data, action);
        }

        /// Reads and compares the dragged stable path.
        private boolean transferredPathMatches(TransferSupport support) {
            try {
                Object value = support.getTransferable().getTransferData(DataFlavor.stringFlavor);
                return value instanceof String text && draggedPath.toString().equals(text);
            } catch (UnsupportedFlavorException | IOException failure) {
                return false;
            }
        }

        /// Converts one insertion gap into a final enabled-prefix index.
        private int dropTargetIndex(int sourceIndex, int dropIndex, int enabledItemCount) {
            if (sourceIndex < 0 || enabledItemCount <= 0 || dropIndex < 0) {
                return -1;
            }
            int targetIndex = dropIndex > sourceIndex ? dropIndex - 1 : dropIndex;
            return targetIndex >= 0 && targetIndex < enabledItemCount ? targetIndex : -1;
        }
    }
}
