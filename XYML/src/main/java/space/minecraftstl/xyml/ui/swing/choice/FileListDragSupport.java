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
package space.minecraftstl.xyml.ui.swing.choice;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;
import space.minecraftstl.xyml.ui.swing.shell.ShellFileDropHandler;

import javax.swing.JComponent;
import javax.swing.JList;
import javax.swing.SwingUtilities;
import javax.swing.TransferHandler;
import javax.swing.event.ListDataEvent;
import javax.swing.event.ListDataListener;
import java.awt.GraphicsEnvironment;
import java.awt.Point;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.dnd.DragSource;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

/// Opt-in native, copy-only file dragging for loaded rows, composed with existing drop handlers.
///
/// Path and context providers must be nonblocking. Only file references are exported; recipients perform copying.
///
/// @param <T> loaded row value type
@NotNullByDefault
public final class FileListDragSupport<T> extends MouseAdapter implements AutoCloseable, ListDataListener {
    /// Owning list, never a toolbar selector unless explicitly installed there.
    private final JList<?> list;

    /// Typed row access preserving sparse load status or ordinary model values.
    private final RowAccess<T> rows;

    /// Pure path lookup for an already loaded row.
    private final Function<? super T, @Nullable Path> pathProvider;

    /// Stable model, filter, and repository identity for the current gesture.
    private final Supplier<Object> contextProvider;

    /// Page lifecycle and operation gate.
    private final BooleanSupplier available;

    /// Areas reserved for existing interactions such as priority handles.
    private final Predicate<Point> excluded;

    /// Previous handler whose import and reserved move gestures remain available.
    private final @Nullable TransferHandler previousHandler;

    /// Installed handler, retained for conditional restoration.
    private final FileTransferHandler handler = new FileTransferHandler();

    /// Original native selection/drag configuration.
    private final boolean previousDragEnabled;

    /// Press-time immutable path selection.
    private @Unmodifiable List<Path> armedPaths = List.of();

    /// Press-time context, or null outside an armed gesture.
    private @Nullable Object armedContext;

    /// Exact pressed row index, or -1 outside an armed gesture.
    private int armedIndex = -1;

    /// Pressed loaded value, or null outside an armed gesture.
    private @Nullable T armedValue;

    /// Initial pointer x-coordinate.
    private int pressedX;

    /// Initial pointer y-coordinate.
    private int pressedY;

    /// Whether the current press belongs to a reserved sorting handle.
    private boolean reservedGesture;

    /// Whether an exported file gesture is still owned by this source list.
    private boolean exporting;

    /// Whether listeners have been removed.
    private boolean closed;

    /// Installs file dragging without reserving a sorting handle.
    ///
    /// @param list page-owned loaded-row list
    /// @param pathProvider nonblocking actual local-path lookup
    /// @param contextProvider nonblocking stable context lookup
    /// @param available page lifecycle and operation gate
    public FileListDragSupport(
            JList<ChoiceListEntry<T>> list,
            Function<? super T, @Nullable Path> pathProvider,
            Supplier<Object> contextProvider,
            BooleanSupplier available) {
        this(list, pathProvider, contextProvider, available, point -> false);
    }

    /// Installs file dragging while preserving a previous handler's reserved move gestures.
    ///
    /// @param list page-owned loaded-row list
    /// @param pathProvider nonblocking actual local-path lookup
    /// @param contextProvider nonblocking stable context lookup
    /// @param available page lifecycle and operation gate
    /// @param excluded predicate for points reserved for internal sorting
    public FileListDragSupport(
            JList<ChoiceListEntry<T>> list,
            Function<? super T, @Nullable Path> pathProvider,
            Supplier<Object> contextProvider,
            BooleanSupplier available,
            Predicate<Point> excluded) {
        this(new LoadedRows<>(Objects.requireNonNull(list, "list")),
                pathProvider, contextProvider, available, excluded);
    }

    /// Installs copy-only file dragging on an ordinary value list without replacing its model or row type.
    ///
    /// @param list page-owned value list
    /// @param pathProvider nonblocking local-path lookup, returning null for non-file rows
    /// @param contextProvider stable page, filter, and model context
    /// @param available page lifecycle and operation gate
    /// @param <T> ordinary row value type
    /// @return owned file-drag lifecycle
    public static <T> FileListDragSupport<T> forValues(
            JList<T> list,
            Function<? super T, @Nullable Path> pathProvider,
            Supplier<Object> contextProvider,
            BooleanSupplier available) {
        return new FileListDragSupport<>(new ValueRows<>(Objects.requireNonNull(list, "list")),
                pathProvider, contextProvider, available, point -> false);
    }

    /// Installs one shared gesture controller over typed row access.
    ///
    /// @param rows typed, nonblocking row access
    /// @param pathProvider actual local-path lookup
    /// @param contextProvider stable page and model context
    /// @param available lifecycle and operation gate
    /// @param excluded points reserved for existing priority handles
    private FileListDragSupport(
            RowAccess<T> rows,
            Function<? super T, @Nullable Path> pathProvider,
            Supplier<Object> contextProvider,
            BooleanSupplier available,
            Predicate<Point> excluded) {
        EdtDispatcher.requireEventDispatchThread();
        this.rows = rows;
        list = rows.list();
        this.pathProvider = Objects.requireNonNull(pathProvider, "pathProvider");
        this.contextProvider = Objects.requireNonNull(contextProvider, "contextProvider");
        this.available = Objects.requireNonNull(available, "available");
        this.excluded = Objects.requireNonNull(excluded, "excluded");
        previousHandler = list.getTransferHandler();
        previousDragEnabled = list.getDragEnabled();
        list.setTransferHandler(handler);
        if (!GraphicsEnvironment.isHeadless()) {
            // Let BasicListUI defer collapsing a selected multi-row group until a click completes.
            list.setDragEnabled(true);
        }
        list.addMouseListener(this);
        list.addMouseMotionListener(this);
        list.getModel().addListDataListener(this);
    }

    /// Arms only exact loaded rows after ordinary list selection processing.
    @Override
    public void mousePressed(MouseEvent event) {
        reset();
        reservedGesture = false;
        if (closed || !available.getAsBoolean() || !list.isEnabled()
                || !SwingUtilities.isLeftMouseButton(event)) return;
        reservedGesture = excluded.test(event.getPoint());
        if (reservedGesture) return;
        int index = list.locationToIndex(event.getPoint());
        if (index < 0 || !Objects.requireNonNull(list.getCellBounds(index, index)).contains(event.getPoint())) return;
        @Nullable T value = rows.valueAt(index);
        if (value == null) return;
        if (!list.isSelectedIndex(index)) list.setSelectedIndex(index);
        armedPaths = selectedPaths();
        if (armedPaths.isEmpty()) return;
        armedValue = value;
        armedIndex = index;
        armedContext = Objects.requireNonNull(contextProvider.get(), "drag context");
        pressedX = event.getX();
        pressedY = event.getY();
    }

    /// Starts a native copy after the platform threshold without changing selection.
    @Override
    public void mouseDragged(MouseEvent event) {
        if (armedIndex >= 0 && !exporting) handler.exportAsDrag(list, event, TransferHandler.COPY);
    }

    /// Releases only pending gesture state; native completion owns active-export cleanup.
    @Override
    public void mouseReleased(MouseEvent event) {
        reset();
        reservedGesture = false;
    }

    /// Cancels gestures when the sparse row generation or filtering changes.
    @Override
    public void contentsChanged(ListDataEvent event) {
        reset();
        reservedGesture = false;
    }

    /// Cancels gestures when rows are inserted.
    @Override
    public void intervalAdded(ListDataEvent event) {
        contentsChanged(event);
    }

    /// Cancels gestures when rows are removed.
    @Override
    public void intervalRemoved(ListDataEvent event) {
        contentsChanged(event);
    }

    /// Removes all owned listeners and restores only the handler still owned by this support.
    @Override
    public void close() {
        EdtDispatcher.requireEventDispatchThread();
        if (closed) return;
        closed = true;
        reset();
        reservedGesture = false;
        exporting = false;
        list.removeMouseListener(this);
        list.removeMouseMotionListener(this);
        list.getModel().removeListDataListener(this);
        if (list.getTransferHandler() == handler) {
            list.setTransferHandler(previousHandler);
            list.setDragEnabled(previousDragEnabled);
        }
    }

    /// Captures loaded selected paths in displayed order without inspecting the filesystem.
    ///
    /// @return immutable actual paths, or an empty list if any selected row cannot be exported
    private @Unmodifiable List<Path> selectedPaths() {
        List<Path> paths = new ArrayList<>();
        for (int index : list.getSelectedIndices()) {
            @Nullable T value = rows.valueAt(index);
            if (value == null) return List.of();
            @Nullable Path path = pathProvider.apply(value);
            if (path == null || !path.getFileSystem().equals(java.nio.file.FileSystems.getDefault())) return List.of();
            paths.add(path.toAbsolutePath().normalize());
        }
        return List.copyOf(paths);
    }

    /// Revalidates press identity, selected paths, page state, and repository context.
    ///
    /// @return copy payload, or null if the gesture no longer represents the pressed rows
    @Nullable Transferable pendingTransfer() {
        if (closed || !available.getAsBoolean() || !list.isEnabled() || armedIndex < 0
                || armedIndex >= list.getModel().getSize()
                || !Objects.equals(armedContext, contextProvider.get())) return null;
        @Nullable T value = rows.valueAt(armedIndex);
        if (value == null || !Objects.equals(armedValue, value)
                || !list.isSelectedIndex(armedIndex) || !armedPaths.equals(selectedPaths())) return null;
        return new FileTransferable(list, armedPaths.stream().map(Path::toFile).toList());
    }

    /// Discards pending press data without modifying files or an active native payload.
    private void reset() {
        armedPaths = List.of();
        armedContext = null;
        armedValue = null;
        armedIndex = -1;
    }

    /// Composes copy-only export, reserved internal moves, and existing/ancestor imports.
    @NotNullByDefault
    private final class FileTransferHandler extends TransferHandler {
        /// Creates an immutable native file payload only for a still-valid press.
        @Override
        protected @Nullable Transferable createTransferable(JComponent component) {
            return component == list ? pendingTransfer() : null;
        }

        /// Advertises no move or text/link actions for file exports.
        @Override
        public int getSourceActions(JComponent component) {
            return component == list && !closed ? COPY : NONE;
        }

        /// Routes reserved handle moves separately and forces all file exports to COPY.
        @Override
        public void exportAsDrag(JComponent component, java.awt.event.InputEvent event, int action) {
            if (closed || exporting || component != list || !(event instanceof MouseEvent mouse)) return;
            if (reservedGesture && action == MOVE && previousHandler != null) {
                previousHandler.exportAsDrag(component, event, MOVE);
                return;
            }
            int threshold = DragSource.getDragThreshold();
            if (reservedGesture || Math.max(Math.abs(mouse.getX() - pressedX), Math.abs(mouse.getY() - pressedY))
                    < threshold || pendingTransfer() == null) return;
            exporting = true;
            super.exportAsDrag(component, event, COPY);
        }

        /// Never deletes or renames source files, including when recipients return unexpected actions.
        @Override
        protected void exportDone(JComponent source, @Nullable Transferable data, int action) {
            exporting = false;
            reset();
        }

        /// Rejects self-import and preserves existing or page-scoped external drop routes.
        @Override
        public boolean canImport(TransferSupport support) {
            if (closed || exporting || isOwnTransfer(support)) return false;
            return previousHandler != null && previousHandler.canImport(support)
                    || ShellFileDropHandler.canImportAncestor(support);
        }

        /// Routes external drops without treating a body drag as an internal priority change.
        @Override
        public boolean importData(TransferSupport support) {
            if (!canImport(support)) return false;
            if (previousHandler != null && previousHandler.canImport(support)) return previousHandler.importData(support);
            return ShellFileDropHandler.importAncestor(support);
        }

        /// Recognizes direct programmatic self-transfers without adding any public data flavor.
        private boolean isOwnTransfer(TransferSupport support) {
            return !support.isDrop() && support.getTransferable() instanceof FileTransferable files
                    && files.source == list;
        }
    }

    /// Supplies typed row values without exposing sparse placeholders as real files.
    ///
    /// @param <T> loaded row value type
    @NotNullByDefault
    private interface RowAccess<T> {
        /// Returns the owned source list without changing its row type or model.
        ///
        /// @return page-owned list
        JList<?> list();

        /// Returns an exportable row value, or null for a missing or unloaded value.
        ///
        /// @param index existing row index
        /// @return loaded value, or null when unavailable
        @Nullable T valueAt(int index);
    }

    /// Preserves the original sparse-list load-state contract.
    ///
    /// @param list existing sparse list
    /// @param <T> loaded row value type
    @NotNullByDefault
    private record LoadedRows<T>(JList<ChoiceListEntry<T>> list) implements RowAccess<T> {
        /// Returns only successfully loaded values, never loading or error placeholders.
        @Override
        public @Nullable T valueAt(int index) {
            @Nullable ChoiceListEntry<T> entry = list.getModel().getElementAt(index);
            return entry != null && entry.status() == ChoiceLoadStatus.LOADED ? entry.value() : null;
        }
    }

    /// Provides ordinary list values directly without casts, wrappers, or model replacement.
    ///
    /// @param list existing value list
    /// @param <T> ordinary row value type
    @NotNullByDefault
    private record ValueRows<T>(JList<T> list) implements RowAccess<T> {
        /// Returns the model value, allowing null rows to be rejected by the common gesture controller.
        @Override
        public @Nullable T valueAt(int index) {
            return list.getModel().getElementAt(index);
        }
    }

    /// Immutable file-only payload with private source ownership for self-import rejection.
    @NotNullByDefault
    private static final class FileTransferable implements Transferable {
        /// Source component, never exposed as transferable data.
        private final JComponent source;

        /// Immutable file references in visible selection order.
        private final @Unmodifiable List<File> files;

        /// Creates a file-only immutable selection.
        ///
        /// @param source originating list
        /// @param files actual selected files
        private FileTransferable(JComponent source, @Unmodifiable List<File> files) {
            this.source = source;
            this.files = List.copyOf(files);
        }

        /// Returns a fresh flavor array containing only standard native file-list data.
        @Override
        public DataFlavor @Unmodifiable [] getTransferDataFlavors() {
            return new DataFlavor[] {DataFlavor.javaFileListFlavor};
        }

        /// Recognizes only native file-list requests.
        @Override
        public boolean isDataFlavorSupported(DataFlavor flavor) {
            return DataFlavor.javaFileListFlavor.equals(flavor);
        }

        /// Returns immutable file references without reading file contents.
        @Override
        public @Unmodifiable List<File> getTransferData(DataFlavor flavor) throws UnsupportedFlavorException {
            if (!isDataFlavorSupported(flavor)) throw new UnsupportedFlavorException(flavor);
            return files;
        }
    }
}
