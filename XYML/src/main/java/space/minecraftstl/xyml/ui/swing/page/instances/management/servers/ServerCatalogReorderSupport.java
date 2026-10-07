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
package space.minecraftstl.xyml.ui.swing.page.instances.management.servers;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;
import space.minecraftstl.xyml.ui.swing.choice.RichChoiceListCellRenderer;

import javax.swing.DropMode;
import javax.swing.JComponent;
import javax.swing.JList;
import javax.swing.TransferHandler;
import java.awt.Cursor;
import java.awt.Point;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.util.List;
import java.util.Objects;

/// Uses the account page's right-side six-dot drag gesture and accepts only same-list insertion drops.
///
/// Each drag captures the exact displayed row order. Stale, foreign, no-op, and busy drops never write storage.
@NotNullByDefault
final class ServerCatalogReorderSupport extends TransferHandler implements AutoCloseable {
    /// JVM-local payload flavor; no file, text, clipboard, or cross-list import is accepted.
    private static final DataFlavor ROW_FLAVOR = new DataFlavor(
            DataFlavor.javaJVMLocalObjectMimeType + ";class=" + RowTransfer.class.getName(), "Server catalog row");

    /// Owning catalog whose serialized model performs persistence.
    private final ServerCatalogPanel panel;

    /// List owned by the catalog.
    private final JList<ServerCatalogItem> list;

    /// Handle-only gesture listener, removed at closure.
    private final HandleGesture gesture = new HandleGesture();

    /// Blocks exports and imports after listener cleanup begins.
    private boolean closed;

    /// Attaches the account-style drag gesture without enabling drag from arbitrary row text.
    ///
    /// @param panel owning server catalog
    ServerCatalogReorderSupport(ServerCatalogPanel panel) {
        EdtDispatcher.requireEventDispatchThread();
        this.panel = Objects.requireNonNull(panel, "panel");
        list = panel.serverList();
        list.setDropMode(DropMode.INSERT);
        list.setTransferHandler(this);
        list.addMouseListener(gesture);
        list.addMouseMotionListener(gesture);
    }

    /// Captures immutable rows and the selected source index on the EDT.
    ///
    /// @param component exporting list
    /// @return local row transfer, or null when exports are not available
    @Override
    protected @Nullable Transferable createTransferable(JComponent component) {
        EdtDispatcher.requireEventDispatchThread();
        int index = list.getSelectedIndex();
        @Unmodifiable List<ServerCatalogItem> rows = panel.displayedServers();
        return !closed && component == list && panel.canReorder() && index >= 0 && index < rows.size()
                ? new RowTransfer(this, rows, index) : null;
    }

    /// Supports move semantics only for this catalog's list.
    ///
    /// @param component requested export source
    /// @return MOVE, or NONE for an unavailable source
    @Override
    public int getSourceActions(JComponent component) {
        return !closed && component == list && panel.canReorder() ? MOVE : NONE;
    }

    /// Rejects clipboard, external, stale, and busy imports before deriving an insertion index.
    ///
    /// @param support current drop context
    /// @return whether this same-list drop would change the current order
    @Override
    public boolean canImport(TransferSupport support) {
        if (closed || !support.isDrop() || support.getComponent() != list
                || !support.isDataFlavorSupported(ROW_FLAVOR)
                || !(support.getDropLocation() instanceof JList.DropLocation location)) return false;
        @Nullable RowTransfer transfer = readTransfer(support.getTransferable());
        return transfer != null && targetIndex(transfer, location.getIndex()) >= 0;
    }

    /// Submits one validated same-list drop to the serialized storage channel.
    ///
    /// @param support current drop context
    /// @return whether a reorder was accepted for asynchronous persistence
    @Override
    public boolean importData(TransferSupport support) {
        return canImport(support) && drop(support.getTransferable(),
                ((JList.DropLocation) support.getDropLocation()).getIndex());
    }

    /// Validates a local payload and submits its insertion gap; also used by headless integration tests.
    ///
    /// @param data local transfer payload
    /// @param insertionIndex insertion gap in [0, item count]
    /// @return whether a non-no-op reorder was submitted
    boolean drop(Transferable data, int insertionIndex) {
        EdtDispatcher.requireEventDispatchThread();
        @Nullable RowTransfer transfer = readTransfer(data);
        if (transfer == null) return false;
        int target = targetIndex(transfer, insertionIndex);
        return target >= 0 && panel.reorderServers(transfer.rows(), transfer.sourceIndex(), target);
    }

    /// Converts a current same-list insertion gap into a final index, rejecting no-op and stale transfers.
    ///
    /// @param transfer captured row transfer
    /// @param insertionIndex insertion gap, including the final gap after the last row
    /// @return final index, or -1 when the transfer is stale, invalid, or unchanged
    private int targetIndex(RowTransfer transfer, int insertionIndex) {
        int count = transfer.rows().size();
        if (closed || transfer.owner() != this || !panel.canReorder()
                || !transfer.rows().equals(panel.displayedServers())
                || transfer.sourceIndex() < 0 || transfer.sourceIndex() >= count
                || insertionIndex < 0 || insertionIndex > count) return -1;
        int target = insertionIndex > transfer.sourceIndex() ? insertionIndex - 1 : insertionIndex;
        return target == transfer.sourceIndex() ? -1 : target;
    }

    /// Reads only the process-local row payload without relying on externally supplied text.
    ///
    /// @param data candidate transfer
    /// @return local payload, or null for an unsupported or failed transfer
    private static @Nullable RowTransfer readTransfer(Transferable data) {
        if (!data.isDataFlavorSupported(ROW_FLAVOR)) return null;
        try {
            Object value = data.getTransferData(ROW_FLAVOR);
            return value instanceof RowTransfer transfer ? transfer : null;
        } catch (UnsupportedFlavorException | IOException ignored) {
            return null;
        }
    }

    /// Detaches listeners and transfer ownership on the EDT, without cancelling model-owned storage work.
    @Override
    public void close() {
        EdtDispatcher.requireEventDispatchThread();
        if (closed) return;
        closed = true;
        gesture.reset();
        list.removeMouseListener(gesture);
        list.removeMouseMotionListener(gesture);
        if (list.getTransferHandler() == this) list.setTransferHandler(null);
        list.setCursor(Cursor.getDefaultCursor());
    }

    /// Captured immutable same-list transfer; row objects are immutable presentation values.
    ///
    /// @param owner originating list's transfer handler
    /// @param rows captured row order
    /// @param sourceIndex captured source row index
    @NotNullByDefault
    private record RowTransfer(ServerCatalogReorderSupport owner,
                               @Unmodifiable List<ServerCatalogItem> rows, int sourceIndex) implements Transferable {
        /// Copies the list before it crosses the drag gesture boundary.
        private RowTransfer {
            rows = List.copyOf(rows);
        }

        /// Returns a detached flavor array.
        @Override
        public DataFlavor @Unmodifiable [] getTransferDataFlavors() {
            return new DataFlavor[] {ROW_FLAVOR};
        }

        /// Supports only the catalog's local row flavor.
        @Override
        public boolean isDataFlavorSupported(DataFlavor flavor) {
            return ROW_FLAVOR.equals(flavor);
        }

        /// Returns this immutable captured request only for the matching flavor.
        @Override
        public Object getTransferData(DataFlavor flavor) throws UnsupportedFlavorException {
            if (!isDataFlavorSupported(flavor)) throw new UnsupportedFlavorException(flavor);
            return this;
        }
    }

    /// Starts dragging only from the same right-aligned handle geometry used by account rows.
    @NotNullByDefault
    private final class HandleGesture extends MouseAdapter {
        /// Pointer position captured on handle press, or null outside a gesture.
        private @Nullable Point pressed;

        /// Source row captured by a handle press, independent of Swing drag-selection updates.
        private int armedIndex = -1;

        /// Clears a completed or cancelled gesture.
        private void reset() {
            armedIndex = -1;
            pressed = null;
        }

        /// Arms only a primary-button press inside the actual row's six-dot handle.
        @Override
        public void mousePressed(MouseEvent event) {
            reset();
            int index = list.locationToIndex(event.getPoint());
            if (event.getButton() == MouseEvent.BUTTON1 && isHandleHit(index, event.getPoint())) {
                list.setSelectedIndex(index);
                armedIndex = index;
                pressed = event.getPoint();
            }
        }

        /// Exports a move after the account page's four-pixel gesture threshold is crossed.
        @Override
        public void mouseDragged(MouseEvent event) {
            @Nullable Point origin = pressed;
            if (origin == null || !panel.canReorder()) return;
            if (Math.abs(event.getX() - origin.x) + Math.abs(event.getY() - origin.y) < 4) return;
            // The list UI may select the row under the moving pointer before this listener runs.
            list.setSelectedIndex(armedIndex);
            reset();
            @Nullable TransferHandler handler = list.getTransferHandler();
            if (handler != null) handler.exportAsDrag(list, event, MOVE);
        }

        /// Clears an uncompleted gesture after release.
        @Override
        public void mouseReleased(MouseEvent event) {
            reset();
        }

        /// Uses the hand cursor over reorder handles only.
        @Override
        public void mouseMoved(MouseEvent event) {
            int index = list.locationToIndex(event.getPoint());
            list.setCursor(isHandleHit(index, event.getPoint())
                    ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) : Cursor.getDefaultCursor());
        }

        /// Restores the default cursor when the pointer leaves the list.
        @Override
        public void mouseExited(MouseEvent event) {
            list.setCursor(Cursor.getDefaultCursor());
        }

        /// Tests a bounded row handle rather than JList's nearest row for blank-space clicks.
        ///
        /// @param index nearest row index
        /// @param point pointer in list coordinates
        /// @return whether a writable row's handle contains the pointer
        private boolean isHandleHit(int index, Point point) {
            if (closed || !panel.canReorder() || index < 0 || index >= list.getModel().getSize()) return false;
            return RichChoiceListCellRenderer.dragHandleBounds(list, index).contains(point);
        }
    }
}
