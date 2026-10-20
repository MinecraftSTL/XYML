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
package space.minecraftstl.xyml.ui.swing.page.nbt;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.library.nbt.edit.NBTAddress;
import space.minecraftstl.xyml.library.nbt.tag.TagType;
import space.minecraftstl.xyml.nbt.NBTNodeType;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;

import javax.swing.Icon;
import javax.swing.JTree;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreeModel;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;

/// Stateless helpers shared by the Swing NBT page and its lifecycle boundary.
@NotNullByDefault
final class NBTEditorPanelSupport {
    /// Prevents utility-class construction.
    private NBTEditorPanelSupport() {
    }

    /// Returns whether an address identifies a fixed Region chunk slot.
    static boolean isRegionSlot(NBTAddress address) {
        @Unmodifiable List<NBTAddress.Segment> segments = Objects.requireNonNull(address, "address").segments();
        return !segments.isEmpty()
                && segments.get(segments.size() - 1) instanceof NBTAddress.RegionChunkSegment;
    }

    /// Returns whether an address identifies a fixed chunk-root slot.
    static boolean isChunkRoot(NBTAddress address) {
        @Unmodifiable List<NBTAddress.Segment> segments = Objects.requireNonNull(address, "address").segments();
        return !segments.isEmpty()
                && segments.get(segments.size() - 1) instanceof NBTAddress.ChunkRootSegment;
    }

    /// Finds the owning Region local index for a chunk-root address.
    static int chunkLocalIndex(NBTAddress address) {
        for (NBTAddress.Segment segment : Objects.requireNonNull(address, "address").segments()) {
            if (segment instanceof NBTAddress.RegionChunkSegment chunk) {
                return chunk.localIndex();
            }
        }
        return -1;
    }

    /// Returns a safe initial structured value for one selectable type.
    static String defaultValue(TagType<?> type) {
        TagType<?> selected = Objects.requireNonNull(type, "type");
        return selected == TagType.BYTE
                || selected == TagType.SHORT
                || selected == TagType.INT
                || selected == TagType.LONG
                || selected == TagType.FLOAT
                || selected == TagType.DOUBLE ? "0" : "";
    }

    /// Returns localized lifecycle status text for one immutable editor snapshot.
    ///
    /// @param strings localized editor strings
    /// @param current current editor state
    /// @return visible status text
    static String statusText(NBTEditorStrings strings, NBTEditorSnapshot current) {
        NBTEditorStrings localized = Objects.requireNonNull(strings, "strings");
        NBTEditorSnapshot state = Objects.requireNonNull(current, "current");
        return switch (state.status()) {
            case EMPTY, CLOSED -> localized.emptyText();
            case OPENING -> localized.openingText();
            case READY -> state.dirty() ? localized.modifiedText() : localized.readyText();
            case EDITING -> localized.editingText();
            case EDIT_UNCERTAIN -> localized.editUncertainText();
            case SAVING -> localized.savingText();
            case PARTIAL_SAVE -> localized.partialSaveText();
            case COMMIT_UNCERTAIN -> localized.commitUncertainText();
            case ERROR -> localized.errorText();
        };
    }

    /// Returns whether the selected row is an empty List.
    ///
    /// @param selected selected row
    /// @return whether its declared element type can change
    static boolean emptyListSelected(NBTEditorTreeNode selected) {
        return Objects.requireNonNull(selected, "selected").node().getType() == TagType.LIST
                && selected.childCount() == 0;
    }

    /// Returns whether the selected address is a Compound name segment.
    ///
    /// @param selected selected row
    /// @return whether rename is structurally valid
    static boolean nameEditable(NBTEditorTreeNode selected) {
        @Unmodifiable List<NBTAddress.Segment> segments = Objects.requireNonNull(selected, "selected")
                .address()
                .segments();
        return !segments.isEmpty() && segments.get(segments.size() - 1) instanceof NBTAddress.NameSegment;
    }

    /// Generates a readable unused default Compound child name.
    static String uniqueChildName(NBTEditorStrings strings, NBTEditorTreeNode parent) {
        String base = Objects.requireNonNull(strings, "strings").defaultTagName();
        NBTEditorTreeNode container = Objects.requireNonNull(parent, "parent");
        for (int suffix = 1; ; suffix++) {
            String candidate = suffix == 1 ? base : base + '_' + suffix;
            boolean used = false;
            for (int index = 0; index < container.childCount(); index++) {
                if (candidate.equals(container.childAt(index).node().getName())) {
                    used = true;
                    break;
                }
            }
            if (!used) {
                return candidate;
            }
        }
    }

    /// Determines the constrained destination for Add or Paste from one selected row.
    static @Nullable InsertionTarget insertionTarget(
            NBTEditorTreeNode selected,
            @Nullable NBTEditorTreeNode selectedParent,
            NBTEditorController controller) {
        NBTEditorTreeNode current = Objects.requireNonNull(selected, "selected");
        NBTEditorController owner = Objects.requireNonNull(controller, "controller");
        @Nullable InsertionTarget direct = targetForParent(owner, current, current.childCount());
        if (direct != null) {
            return direct;
        }
        return selectedParent == null
                ? null
                : targetForParent(owner, selectedParent, current.parentIndex() + 1);
    }

    /// Builds insertion constraints for one candidate parent.
    private static @Nullable InsertionTarget targetForParent(
            NBTEditorController controller,
            NBTEditorTreeNode parent,
            int index) {
        NBTEditorTreeNode current = Objects.requireNonNull(parent, "parent");
        @Nullable TagType<?> type = current.node().getType();
        if (type == TagType.COMPOUND) {
            return new InsertionTarget(current, index, NBTTagInput.types(), true);
        }
        if (type == TagType.LIST) {
            @Nullable TagType<?> elementType = current.childCount() > 0
                    ? current.childAt(0).node().getType()
                    : Objects.requireNonNull(controller, "controller").listElementType(current);
            return new InsertionTarget(
                    current,
                    index,
                    elementType == null ? NBTTagInput.types() : List.of(elementType),
                    false);
        }
        if (type == TagType.BYTE_ARRAY) {
            return new InsertionTarget(current, index, List.of(TagType.BYTE), false);
        }
        if (type == TagType.INT_ARRAY) {
            return new InsertionTarget(current, index, List.of(TagType.INT), false);
        }
        if (type == TagType.LONG_ARRAY) {
            return new InsertionTarget(current, index, List.of(TagType.LONG), false);
        }
        if (type == null && isRegionSlot(current.address()) && current.childCount() == 0) {
            return new InsertionTarget(current, 0, List.of(TagType.COMPOUND), false);
        }
        return null;
    }

    /// Creates an empty tree model without a synthetic placeholder node.
    static TreeModel emptyTreeModel() {
        return new DefaultTreeModel(null);
    }

    /// Starts a background icon load and installs successful results on the EDT.
    static @Nullable CompletableFuture<@Unmodifiable Map<NBTNodeType, Icon>> preloadTreeIcons(
            Executor executor,
            NBTTreeCellRenderer renderer,
            JTree tree,
            BooleanSupplier active) {
        try {
            CompletableFuture<@Unmodifiable Map<NBTNodeType, Icon>> future = CompletableFuture.supplyAsync(
                    NBTTreeCellRenderer::loadIcons,
                    Objects.requireNonNull(executor, "executor"));
            future.whenComplete((
                    @Nullable @Unmodifiable Map<NBTNodeType, Icon> loaded,
                    @Nullable Throwable failure) -> EdtDispatcher.execute(() -> {
                        if (active.getAsBoolean() && failure == null && loaded != null) {
                            renderer.installIcons(loaded);
                            tree.repaint();
                        }
                    }));
            return future;
        } catch (RuntimeException failure) {
            return null;
        }
    }
}
