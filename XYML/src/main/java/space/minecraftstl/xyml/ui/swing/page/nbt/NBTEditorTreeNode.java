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
import space.minecraftstl.xyml.library.nbt.edit.NBTAddress;
import space.minecraftstl.xyml.library.nbt.edit.NBTNode;
import space.minecraftstl.xyml.nbt.NBTDocument;
import space.minecraftstl.xyml.nbt.NBTTreeNode;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReferenceArray;

/// Swing adapter over one immutable, revision-bound XoyzNBT node handle.
///
/// No mutable NBT element crosses this boundary. A successful edit replaces the complete tree
/// model, and any accidental use of an older adapter is rejected by the underlying editor.
@NotNullByDefault
public final class NBTEditorTreeNode {
    /// Document identity used to reject rows retained from an older open operation.
    private final NBTDocument document;

    /// Toolkit-neutral immutable presentation metadata.
    private final NBTTreeNode presentation;

    /// Index in the direct parent at the captured revision, or `-1` for the root.
    private final int parentIndex;

    /// Per-index cache populated only when Swing requests a child.
    private final AtomicReferenceArray<@Nullable NBTEditorTreeNode> children;

    /// Creates a root adapter for the document's current editor revision.
    ///
    /// @param document loaded document
    public NBTEditorTreeNode(NBTDocument document) {
        this(Objects.requireNonNull(document, "document"), document.rootNode(), -1);
    }

    /// Creates one lazy child adapter.
    ///
    /// @param document owning document
    /// @param presentation immutable backend presentation
    /// @param parentIndex direct index in the parent
    private NBTEditorTreeNode(NBTDocument document, NBTTreeNode presentation, int parentIndex) {
        this.document = Objects.requireNonNull(document, "document");
        this.presentation = Objects.requireNonNull(presentation, "presentation");
        this.parentIndex = parentIndex;
        children = new AtomicReferenceArray<>(presentation.childCount());
    }

    /// Returns immutable metadata for rendering.
    ///
    /// @return stable presentation node
    public NBTTreeNode presentation() {
        return presentation;
    }

    /// Returns the revision-bound read-only node handle.
    ///
    /// @return immutable editor node
    public NBTNode node() {
        return presentation.node();
    }

    /// Returns the immutable structural address.
    ///
    /// @return node address
    public NBTAddress address() {
        return presentation.address();
    }

    /// Returns the captured direct index in the parent.
    ///
    /// @return direct child index, or `-1` for the root
    int parentIndex() {
        return parentIndex;
    }

    /// Returns the exact direct-child count without materializing children.
    ///
    /// @return direct child count
    public int childCount() {
        return presentation.childCount();
    }

    /// Returns one child and materializes no sibling.
    ///
    /// @param index direct child index
    /// @return stable child adapter
    public NBTEditorTreeNode childAt(int index) {
        Objects.checkIndex(index, childCount());
        @Nullable NBTEditorTreeNode cached = children.get(index);
        if (cached != null) {
            // Swing may query already rendered rows while replacing a stale model. The cached
            // adapter is immutable; only materializing a new row must consult the current editor.
            return cached;
        }
        NBTEditorTreeNode created = new NBTEditorTreeNode(document, presentation.childAt(index), index);
        if (children.compareAndSet(index, null, created)) {
            return created;
        }
        @Nullable NBTEditorTreeNode concurrent = children.get(index);
        if (concurrent == null) {
            throw new AssertionError("Child cache lost a concurrent update");
        }
        return concurrent;
    }

    /// Counts child adapters already requested from this node.
    ///
    /// @return populated cache slots
    public int materializedChildCount() {
        int count = 0;
        for (int index = 0; index < children.length(); index++) {
            if (children.get(index) != null) {
                count++;
            }
        }
        return count;
    }

    /// Returns whether this row has a scalar value accepted by `NBTEditor.setScalar`.
    ///
    /// @return whether scalar editing is available
    public boolean editable() {
        return node().getType() != null && node().getValue() != null;
    }

    /// Returns the scalar text captured at this revision.
    ///
    /// @return scalar text, or `null` for containers
    public @Nullable String currentScalarValue() {
        return node().getValue();
    }

    /// Returns whether this row belongs to the exact current document identity.
    ///
    /// @param candidate document to compare
    /// @return whether both identities match
    boolean belongsTo(NBTDocument candidate) {
        return document == Objects.requireNonNull(candidate, "candidate");
    }
}
