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
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.library.nbt.edit.NBTAddress;
import space.minecraftstl.xyml.nbt.NBTDocument;

import javax.swing.event.TreeModelListener;
import javax.swing.tree.TreeModel;
import javax.swing.tree.TreePath;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/// Lazy Swing tree model for one immutable XoyzNBT editor revision.
@NotNullByDefault
public final class NBTLazyTreeModel implements TreeModel {
    /// Root adapter for the loaded document.
    private final NBTEditorTreeNode root;

    /// Listener registrations retained for Swing contract completeness.
    private final CopyOnWriteArrayList<TreeModelListener> listeners = new CopyOnWriteArrayList<>();

    /// Whether Swing may enumerate the root's direct children.
    private boolean rootChildrenVisible;

    /// Creates a fresh structural model with immediately visible root children.
    ///
    /// @param document loaded document
    public NBTLazyTreeModel(NBTDocument document) {
        this(document, true);
    }

    /// Creates a model whose root can remain dormant until a real expansion request.
    ///
    /// @param document loaded document
    /// @param rootChildrenVisible whether root children are immediately visible
    NBTLazyTreeModel(NBTDocument document, boolean rootChildrenVisible) {
        root = new NBTEditorTreeNode(Objects.requireNonNull(document, "document"));
        this.rootChildrenVisible = rootChildrenVisible;
    }

    /// Returns the root adapter.
    ///
    /// @return root node
    @Override
    public NBTEditorTreeNode getRoot() {
        return root;
    }

    /// Returns one requested child without materializing siblings.
    ///
    /// @param parent parent adapter
    /// @param index child index
    /// @return stable child adapter
    @Override
    public NBTEditorTreeNode getChild(Object parent, int index) {
        return requireNode(parent).childAt(index);
    }

    /// Returns the direct-child count without expanding the parent.
    ///
    /// @param parent parent adapter
    /// @return direct child count
    @Override
    public int getChildCount(Object parent) {
        NBTEditorTreeNode node = requireNode(parent);
        return node == root && !rootChildrenVisible ? 0 : node.childCount();
    }

    /// Returns whether the adapter has no direct children.
    ///
    /// @param node candidate adapter
    /// @return whether the row is a structural leaf
    @Override
    public boolean isLeaf(Object node) {
        return requireNode(node).childCount() == 0;
    }

    /// Ignores Swing's generic inline-edit callback because edits use explicit controller commands.
    ///
    /// @param path edited path
    /// @param newValue generic replacement value
    @Override
    public void valueForPathChanged(TreePath path, Object newValue) {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(newValue, "newValue");
    }

    /// Returns the child's captured direct index without scanning siblings.
    ///
    /// @param parent expected parent adapter
    /// @param child expected direct child adapter
    /// @return child index, or `-1` when the relationship does not match
    @Override
    public int getIndexOfChild(Object parent, Object child) {
        NBTEditorTreeNode parentNode = requireNode(parent);
        NBTEditorTreeNode childNode = requireNode(child);
        return childNode.address().parent().equals(parentNode.address()) ? childNode.parentIndex() : -1;
    }

    /// Adds one Swing model listener.
    ///
    /// @param listener listener to retain
    @Override
    public void addTreeModelListener(TreeModelListener listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
    }

    /// Removes one Swing model listener.
    ///
    /// @param listener listener to remove
    @Override
    public void removeTreeModelListener(TreeModelListener listener) {
        listeners.remove(Objects.requireNonNull(listener, "listener"));
    }

    /// Resolves an immutable structural address into a lazily materialized Swing path.
    ///
    /// @param address exact current address
    /// @return resolved path
    /// @throws IndexOutOfBoundsException when the current tree no longer contains the address
    public TreePath pathForAddress(NBTAddress address) {
        NBTAddress target = Objects.requireNonNull(address, "address");
        if (!target.isRoot()) {
            revealRootChildren();
        }
        List<Object> components = new ArrayList<>(target.segments().size() + 1);
        NBTEditorTreeNode current = root;
        components.add(current);
        for (NBTAddress.Segment segment : target.segments()) {
            current = directChild(current, segment);
            components.add(current);
        }
        return new TreePath(components.toArray());
    }

    /// Resolves a legacy child-index address for compatibility callers.
    ///
    /// @param address child indexes from the root
    /// @return resolved path
    /// @throws IndexOutOfBoundsException when an index is absent
    public TreePath pathForAddress(@Unmodifiable List<Integer> address) {
        @Unmodifiable List<Integer> indexes = List.copyOf(Objects.requireNonNull(address, "address"));
        if (!indexes.isEmpty()) {
            revealRootChildren();
        }
        Object[] components = new Object[indexes.size() + 1];
        NBTEditorTreeNode current = root;
        components[0] = current;
        for (int depth = 0; depth < indexes.size(); depth++) {
            current = current.childAt(indexes.get(depth));
            components[depth + 1] = current;
        }
        return new TreePath(components);
    }

    /// Makes root children visible to subsequent Swing layout queries.
    void revealRootChildren() {
        rootChildrenVisible = true;
    }

    /// Resolves the direct child represented by one structural segment.
    ///
    /// @param parent current parent row
    /// @param segment next address segment
    /// @return matching direct child
    /// @throws IndexOutOfBoundsException when no child has the requested segment
    private static NBTEditorTreeNode directChild(NBTEditorTreeNode parent, NBTAddress.Segment segment) {
        int fixedIndex = -1;
        if (segment instanceof NBTAddress.IndexSegment index) {
            fixedIndex = index.index();
        } else if (segment instanceof NBTAddress.RegionChunkSegment chunk) {
            fixedIndex = chunk.localIndex();
        } else if (segment instanceof NBTAddress.ChunkRootSegment) {
            fixedIndex = 0;
        }
        if (fixedIndex >= 0) {
            NBTEditorTreeNode child = parent.childAt(fixedIndex);
            if (finalSegment(child.address()).equals(segment)) {
                return child;
            }
            throw new IndexOutOfBoundsException("NBT address segment no longer exists: " + segment);
        }
        for (int index = 0; index < parent.childCount(); index++) {
            NBTEditorTreeNode child = parent.childAt(index);
            if (finalSegment(child.address()).equals(segment)) {
                return child;
            }
        }
        throw new IndexOutOfBoundsException("NBT address segment no longer exists: " + segment);
    }

    /// Returns the final segment of a non-root child address.
    ///
    /// @param address non-root child address
    /// @return final segment
    private static NBTAddress.Segment finalSegment(NBTAddress address) {
        @Unmodifiable List<NBTAddress.Segment> segments = address.segments();
        return segments.get(segments.size() - 1);
    }

    /// Validates one generic Swing node value.
    ///
    /// @param value generic model value
    /// @return typed adapter
    private static NBTEditorTreeNode requireNode(Object value) {
        if (Objects.requireNonNull(value, "value") instanceof NBTEditorTreeNode node) {
            return node;
        }
        throw new IllegalArgumentException("NBT tree model accepts only NBTEditorTreeNode values");
    }
}
