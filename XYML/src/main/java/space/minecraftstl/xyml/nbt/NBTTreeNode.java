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
package space.minecraftstl.xyml.nbt;

import space.minecraftstl.xyml.library.nbt.NBTElement;
import space.minecraftstl.xyml.library.nbt.edit.NBTAddress;
import space.minecraftstl.xyml.library.nbt.edit.NBTEditException;
import space.minecraftstl.xyml.library.nbt.edit.NBTEditor;
import space.minecraftstl.xyml.library.nbt.edit.NBTNode;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReferenceArray;

/// A lazy toolkit-neutral tree view backed only by immutable XoyzNBT node handles.
///
/// The view captures one editor revision. It never retains or exposes an element from the mutable
/// working tree, and each requested child is described independently. After a successful edit,
/// callers must request a new root from [NBTDocument#rootNode()]; attempts to expand an old view
/// fail as stale instead of resolving a structurally different node.
@NotNullByDefault
public final class NBTTreeNode {
    /// Editor used only to resolve immutable direct-child metadata.
    private final NBTEditor<? extends NBTElement> editor;

    /// Immutable revision-bound handle represented by this row.
    private final NBTNode node;

    /// Stable display name captured at construction.
    private final String displayName;

    /// Stable node category captured at construction.
    private final NBTNodeType type;

    /// Scalar value text, or `null` for containers without a scalar representation.
    private final @Nullable String scalarValue;

    /// Per-index cache whose initially null slots are populated on demand.
    private final AtomicReferenceArray<@Nullable NBTTreeNode> childNodes;

    /// Creates a detached standalone view for package tests and compatibility callers.
    ///
    /// The supplied element is deep-copied by [NBTEditor#of(NBTElement)], so later mutations of the
    /// input cannot affect this view.
    ///
    /// @param element source root copied into a private editor
    /// @param displayName contextual root display name
    NBTTreeNode(NBTElement element, String displayName) {
        editor = NBTEditor.of(Objects.requireNonNull(element, "element"));
        node = editor.getRootNode();
        this.displayName = Objects.requireNonNull(displayName, "displayName");
        type = NBTNodeType.fromRootElement(element);
        scalarValue = node.value();
        childNodes = new AtomicReferenceArray<>(node.childCount());
    }

    /// Creates one view from already captured immutable metadata.
    ///
    /// @param editor owning editor
    /// @param node current immutable node handle
    /// @param displayName stable row name
    /// @param type stable presentation category
    private NBTTreeNode(
            NBTEditor<? extends NBTElement> editor,
            NBTNode node,
            String displayName,
            NBTNodeType type) {
        this.editor = Objects.requireNonNull(editor, "editor");
        this.node = Objects.requireNonNull(node, "node");
        this.displayName = Objects.requireNonNull(displayName, "displayName");
        this.type = Objects.requireNonNull(type, "type");
        scalarValue = node.value();
        childNodes = new AtomicReferenceArray<>(node.childCount());
    }

    /// Creates a document root view without inspecting or copying the editor's mutable root.
    ///
    /// @param editor document editor
    /// @param displayName filename-derived root name
    /// @param fileType filename-derived document family
    /// @return fresh root view at the current editor revision
    static NBTTreeNode forDocument(
            NBTEditor<? extends NBTElement> editor,
            String displayName,
            NBTFileType fileType) {
        NBTEditor<? extends NBTElement> selectedEditor = Objects.requireNonNull(editor, "editor");
        NBTNode root = selectedEditor.getRootNode();
        NBTNodeType rootType = fileType == NBTFileType.TAG
                ? NBTNodeType.fromTagType(root.type())
                : NBTNodeType.CHUNK_REGION;
        return new NBTTreeNode(selectedEditor, root, displayName, rootType);
    }

    /// Returns the immutable XoyzNBT node handle represented by this row.
    ///
    /// @return revision-bound read-only node metadata
    public NBTNode node() {
        return node;
    }

    /// Returns the immutable structural address represented by this row.
    ///
    /// @return node address
    public NBTAddress address() {
        return node.getAddress();
    }

    /// Returns the stable name to show beside this node.
    ///
    /// @return stable non-null presentation name
    public String displayName() {
        return displayName;
    }

    /// Returns the stable element category for icon and renderer selection.
    ///
    /// @return toolkit-neutral node category
    public NBTNodeType type() {
        return type;
    }

    /// Returns the scalar value captured in this node handle.
    ///
    /// @return scalar text, or `null` for containers without a scalar representation
    public @Nullable String scalarValue() {
        return scalarValue;
    }

    /// Returns the exact direct-child count without materializing any child node.
    ///
    /// @return non-negative direct-child count
    public int childCount() {
        return node.childCount();
    }

    /// Reports whether this node has no direct children.
    ///
    /// @return whether `childCount` is zero
    public boolean isLeaf() {
        return childCount() == 0;
    }

    /// Returns one direct child, creating only that child view on first access.
    ///
    /// @param index zero-based direct-child index
    /// @return lazily materialized child metadata
    /// @throws IndexOutOfBoundsException when the index is outside `childCount`
    /// @throws IllegalStateException when this tree view belongs to an older editor revision
    public NBTTreeNode childAt(int index) {
        Objects.checkIndex(index, childCount());
        if (editor.getRevision() != node.getRevision()) {
            throw new IllegalStateException("NBT tree view belongs to an older editor revision");
        }
        @Nullable NBTTreeNode cached = childNodes.get(index);
        if (cached != null) {
            return cached;
        }
        NBTTreeNode created;
        try {
            NBTNode child = editor.getChild(node, index);
            created = new NBTTreeNode(
                    editor,
                    child,
                    deriveDisplayName(child),
                    NBTNodeType.fromNode(child));
        } catch (NBTEditException failure) {
            throw new IllegalStateException("NBT tree view is stale or inconsistent", failure);
        }
        if (childNodes.compareAndSet(index, null, created)) {
            return created;
        }
        @Nullable NBTTreeNode concurrent = childNodes.get(index);
        if (concurrent == null) {
            throw new AssertionError("Child cache lost a successful concurrent update");
        }
        return concurrent;
    }

    /// Counts child nodes that have actually been requested so far.
    ///
    /// @return number of populated child-cache slots
    public int materializedChildCount() {
        int count = 0;
        for (int index = 0; index < childNodes.length(); index++) {
            if (childNodes.get(index) != null) {
                count++;
            }
        }
        return count;
    }

    /// Derives one non-root row name from its immutable address and tag metadata.
    ///
    /// @param child immutable child metadata
    /// @return stable display name
    private static String deriveDisplayName(NBTNode child) {
        List<NBTAddress.Segment> segments = child.getAddress().segments();
        if (segments.isEmpty()) {
            return child.getName();
        }
        NBTAddress.Segment finalSegment = segments.get(segments.size() - 1);
        if (finalSegment instanceof NBTAddress.IndexSegment index) {
            return Integer.toString(index.index());
        }
        if (finalSegment instanceof NBTAddress.RegionChunkSegment chunk) {
            int localIndex = chunk.localIndex();
            return "Chunk (" + (localIndex & 31) + ", " + (localIndex >>> 5) + ')';
        }
        return child.getName();
    }
}
