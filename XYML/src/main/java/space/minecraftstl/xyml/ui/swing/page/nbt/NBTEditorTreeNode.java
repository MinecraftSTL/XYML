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

import space.minecraftstl.xyml.library.nbt.NBTElement;
import space.minecraftstl.xyml.library.nbt.chunk.Chunk;
import space.minecraftstl.xyml.library.nbt.chunk.ChunkRegion;
import space.minecraftstl.xyml.library.nbt.tag.CompoundTag;
import space.minecraftstl.xyml.library.nbt.tag.ParentTag;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.nbt.NBTDocument;
import space.minecraftstl.xyml.nbt.NBTTreeNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReferenceArray;

/// Swing tree node that pairs lazy presentation metadata with the exact mutable XoyzNBT element.
@NotNullByDefault
public final class NBTEditorTreeNode {
    /// Document whose mutable root owns this element.
    private final NBTDocument document;

    /// Toolkit-neutral presentation node supplied by the NBT backend.
    private final NBTTreeNode presentation;

    /// Exact XoyzNBT element edited when this row is selected.
    private final NBTElement element;

    /// Immutable child-index address from the document root.
    private final @Unmodifiable List<Integer> address;

    /// Per-index cache populated only when Swing requests a child.
    private final AtomicReferenceArray<@Nullable NBTEditorTreeNode> children;

    /// Creates a root adapter for a loaded document.
    ///
    /// @param document loaded document
    public NBTEditorTreeNode(NBTDocument document) {
        this(
                Objects.requireNonNull(document, "document"),
                document.rootNode(),
                document.rootElement(),
                List.of());
    }

    /// Creates one lazy child adapter.
    ///
    /// @param document owning document
    /// @param presentation backend presentation node
    /// @param element exact mutable element
    /// @param address immutable child-index address
    private NBTEditorTreeNode(
            NBTDocument document,
            NBTTreeNode presentation,
            NBTElement element,
            @Unmodifiable List<Integer> address) {
        this.document = Objects.requireNonNull(document, "document");
        this.presentation = Objects.requireNonNull(presentation, "presentation");
        this.element = Objects.requireNonNull(element, "element");
        this.address = List.copyOf(Objects.requireNonNull(address, "address"));
        children = new AtomicReferenceArray<>(presentation.childCount());
    }

    /// Returns the toolkit-neutral metadata for rendering.
    ///
    /// @return stable backend presentation node
    public NBTTreeNode presentation() {
        return presentation;
    }

    /// Returns the immutable child-index address from the root.
    ///
    /// @return immutable address
    public @Unmodifiable List<Integer> address() {
        return address;
    }

    /// Returns the exact number of direct children without materializing them.
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
            return cached;
        }
        NBTEditorTreeNode created = new NBTEditorTreeNode(
                document,
                presentation.childAt(index),
                resolveElement(index),
                childAddress(index));
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
    /// @return populated child-cache slots
    public int materializedChildCount() {
        int count = 0;
        for (int index = 0; index < children.length(); index++) {
            if (children.get(index) != null) {
                count++;
            }
        }
        return count;
    }

    /// Returns whether this row has an exact supported scalar setter.
    ///
    /// @return whether value editing is supported
    public boolean editable() {
        return NBTValueEditor.isEditable(element);
    }

    /// Returns the exact current scalar text when available.
    ///
    /// @return current scalar text, or `null` for containers
    public @Nullable String currentScalarValue() {
        return NBTEditorTreeValues.scalarText(element);
    }

    /// Returns whether this node belongs to the exact document identity.
    ///
    /// @param candidate document to compare
    /// @return whether both identities match
    boolean belongsTo(NBTDocument candidate) {
        return document == Objects.requireNonNull(candidate, "candidate");
    }

    /// Returns the exact mutable element for controller-owned edits.
    ///
    /// @return mutable XoyzNBT element
    NBTElement element() {
        return element;
    }

    /// Resolves one child element by index without enumerating its siblings.
    ///
    /// @param index validated child index
    /// @return exact child element
    private NBTElement resolveElement(int index) {
        if (element instanceof ChunkRegion region) {
            return region.getChunk(index);
        }
        if (element instanceof Chunk chunk) {
            @Nullable CompoundTag rootTag = chunk.getRootTag();
            if (rootTag == null) {
                throw new AssertionError("Chunk child count changed after tree construction");
            }
            return rootTag.getTag(index);
        }
        if (element instanceof ParentTag<?> parentTag) {
            return parentTag.getTag(index);
        }
        throw new AssertionError("Leaf node unexpectedly resolved a child");
    }

    /// Appends one index to this immutable address.
    ///
    /// @param index direct child index
    /// @return immutable child address
    private @Unmodifiable List<Integer> childAddress(int index) {
        List<Integer> values = new ArrayList<>(address.size() + 1);
        values.addAll(address);
        values.add(index);
        return List.copyOf(values);
    }
}
