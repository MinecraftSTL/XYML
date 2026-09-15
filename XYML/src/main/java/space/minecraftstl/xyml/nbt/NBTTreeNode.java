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
import space.minecraftstl.xyml.library.nbt.chunk.Chunk;
import space.minecraftstl.xyml.library.nbt.chunk.ChunkRegion;
import space.minecraftstl.xyml.library.nbt.tag.ArrayTag;
import space.minecraftstl.xyml.library.nbt.tag.CompoundTag;
import space.minecraftstl.xyml.library.nbt.tag.ListTag;
import space.minecraftstl.xyml.library.nbt.tag.ParentTag;
import space.minecraftstl.xyml.library.nbt.tag.Tag;
import space.minecraftstl.xyml.library.nbt.tag.ValueTag;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReferenceArray;

/// A toolkit-neutral NBT tree node that materializes each requested child independently.
///
/// Constructing a node reads only stable presentation metadata and the direct child count. In
/// particular, a region root does not call `ChunkRegion.stream`, so asking for its size does not
/// allocate all 1024 chunk objects. `childAt` creates and caches only the requested child node.
/// The node is a structural view of the XoyzNBT state at construction time; callers should obtain
/// a fresh root node from `NBTDocument` after changing the underlying document.
@NotNullByDefault
public final class NBTTreeNode {
    /// XoyzNBT element retained solely to resolve requested direct children.
    private final NBTElement element;

    /// Stable display name captured when this node was constructed.
    private final String displayName;

    /// Stable element category captured when this node was constructed.
    private final NBTNodeType type;

    /// Scalar value text, or `null` for parent and container elements.
    private final @Nullable String scalarValue;

    /// Exact number of direct children without eagerly constructing child nodes.
    private final int childCount;

    /// Per-index cache whose initially null slots are populated on demand.
    private final AtomicReferenceArray<@Nullable NBTTreeNode> childNodes;

    /// Creates a root node with a caller-supplied filename or contextual name.
    ///
    /// @param element root XoyzNBT element
    /// @param displayName non-null root display name
    NBTTreeNode(NBTElement element, String displayName) {
        this(element, Objects.requireNonNull(displayName, "displayName"), true);
    }

    /// Creates either a named root or a regular child node and captures its metadata.
    ///
    /// @param element source element
    /// @param displayName explicit root name, or an ignored value for a regular child
    /// @param overrideName whether to use the explicit name
    private NBTTreeNode(NBTElement element, String displayName, boolean overrideName) {
        this.element = Objects.requireNonNull(element, "element");
        this.displayName = overrideName ? displayName : deriveDisplayName(element);
        type = NBTNodeType.fromElement(element);
        scalarValue = element instanceof ValueTag<?> valueTag ? valueTag.getAsString() : null;
        childCount = deriveChildCount(element);
        childNodes = new AtomicReferenceArray<>(childCount);
    }

    /// Returns the stable name to show beside this node.
    ///
    /// List and primitive-array entries use their numeric index; chunks use local coordinates; all
    /// other tags use their NBT name.
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

    /// Returns the scalar value text reported by XoyzNBT.
    ///
    /// @return scalar text, or `null` for parent and container nodes
    public @Nullable String scalarValue() {
        return scalarValue;
    }

    /// Returns the exact direct-child count without materializing any child node.
    ///
    /// @return non-negative direct-child count
    public int childCount() {
        return childCount;
    }

    /// Reports whether this node has no direct children.
    ///
    /// @return whether `childCount` is zero
    public boolean isLeaf() {
        return childCount == 0;
    }

    /// Returns one direct child, creating only that child node on first access.
    ///
    /// The method is thread-safe and returns the same node identity for repeated requests at the
    /// same index.
    ///
    /// @param index zero-based direct-child index
    /// @return lazily materialized child node
    /// @throws IndexOutOfBoundsException when the index is outside `childCount`
    public NBTTreeNode childAt(int index) {
        Objects.checkIndex(index, childCount);
        @Nullable NBTTreeNode cached = childNodes.get(index);
        if (cached != null) {
            return cached;
        }
        NBTTreeNode created = new NBTTreeNode(resolveChildElement(index), "", false);
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
    /// This diagnostic does not create children and is useful to adapters and tests that verify
    /// viewport-driven expansion behavior.
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

    /// Computes a regular child name from XoyzNBT parent and coordinate metadata.
    ///
    /// @param element child source element
    /// @return non-null display name
    private static String deriveDisplayName(NBTElement element) {
        if (element instanceof Tag tag) {
            return tag.getParent() instanceof ListTag<?> || tag.getParent() instanceof ArrayTag<?, ?, ?, ?>
                    ? Integer.toString(tag.getIndex())
                    : tag.getName();
        }
        if (element instanceof Chunk chunk) {
            return "Chunk (" + chunk.getLocalX() + ", " + chunk.getLocalZ() + ")";
        }
        return "";
    }

    /// Derives a direct-child count without enumerating a parent stream.
    ///
    /// @param element source element
    /// @return exact non-negative direct-child count
    private static int deriveChildCount(NBTElement element) {
        if (element instanceof Chunk chunk) {
            @Nullable CompoundTag rootTag = chunk.getRootTag();
            return rootTag == null ? 0 : rootTag.size();
        }
        if (element instanceof space.minecraftstl.xyml.library.nbt.NBTParent<?> parent) {
            return parent.size();
        }
        return 0;
    }

    /// Resolves one direct XoyzNBT child without enumerating its siblings.
    ///
    /// @param index validated direct-child index
    /// @return requested child element
    private NBTElement resolveChildElement(int index) {
        if (element instanceof ChunkRegion region) {
            return region.getChunk(index);
        }
        if (element instanceof Chunk chunk) {
            @Nullable CompoundTag rootTag = chunk.getRootTag();
            if (rootTag == null) {
                throw new AssertionError("Chunk child count changed after node construction");
            }
            return rootTag.getTag(index);
        }
        if (element instanceof ParentTag<?> parentTag) {
            return parentTag.getTag(index);
        }
        throw new AssertionError("Leaf node unexpectedly resolved a child");
    }
}
