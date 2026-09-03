/*
 * Copyright 2026 Glavo
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
// Added by MinecraftSTL in 2026 for iterative XoyzNBT tree validation.
package space.minecraftstl.xyml.library.nbt.validation;

import space.minecraftstl.xyml.library.nbt.NBTElement;
import space.minecraftstl.xyml.library.nbt.NBTParent;
import space.minecraftstl.xyml.library.nbt.chunk.Chunk;
import space.minecraftstl.xyml.library.nbt.chunk.ChunkRegion;
import space.minecraftstl.xyml.library.nbt.internal.TextUtils;
import space.minecraftstl.xyml.library.nbt.io.MinecraftEdition;
import space.minecraftstl.xyml.library.nbt.tag.ArrayTag;
import space.minecraftstl.xyml.library.nbt.tag.CompoundTag;
import space.minecraftstl.xyml.library.nbt.tag.ListTag;
import space.minecraftstl.xyml.library.nbt.tag.ParentTag;
import space.minecraftstl.xyml.library.nbt.tag.StringTag;
import space.minecraftstl.xyml.library.nbt.tag.Tag;
import space.minecraftstl.xyml.library.nbt.tag.TagType;
import space.minecraftstl.xyml.library.nbt.tag.ValueTag;
import org.jetbrains.annotations.NotNullByDefault;

import java.lang.reflect.Array;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/// Performs a complete, non-recursive validation of an NBT tree.
///
/// The validator checks ownership metadata as well as the logical payload. It rejects cycles and
/// shared identities, stale parent/index links, duplicate compound names, heterogeneous lists,
/// inconsistent primitive-array state, and strings which cannot be represented by the selected
/// edition's two-byte string length field. The traversal uses an explicit stack so deeply nested
/// data does not consume the Java call stack.
@NotNullByDefault
public final class NBTStructureValidator {
    private static final int ACTIVE = 1;
    private static final int COMPLETE = 2;

    private final MinecraftEdition edition;

    /// Creates a validator for Java Edition's big-endian and modified UTF-8 encoding.
    public NBTStructureValidator() {
        this(MinecraftEdition.JAVA_EDITION);
    }

    /// Creates a validator for the supplied Minecraft edition.
    ///
    /// @param edition encoding rules to apply to strings
    public NBTStructureValidator(MinecraftEdition edition) {
        this.edition = Objects.requireNonNull(edition, "edition");
    }

    /// Returns a validator for Java Edition.
    public static NBTStructureValidator javaEdition() {
        return new NBTStructureValidator(MinecraftEdition.JAVA_EDITION);
    }

    /// Returns a validator for Bedrock Edition.
    public static NBTStructureValidator bedrockEdition() {
        return new NBTStructureValidator(MinecraftEdition.BEDROCK_EDITION);
    }

    /// Validates a tree using Java Edition encoding rules.
    ///
    /// @param element root element to validate
    /// @throws NBTValidationException if any structural or encoding invariant is violated
    public static void validate(NBTElement element) throws NBTValidationException {
        validate(element, MinecraftEdition.JAVA_EDITION);
    }

    /// Validates a tree using the selected edition's encoding rules.
    ///
    /// @param element root element to validate
    /// @param edition encoding rules to apply to strings
    /// @throws NBTValidationException if any structural or encoding invariant is violated
    public static void validate(NBTElement element, MinecraftEdition edition) throws NBTValidationException {
        new NBTStructureValidator(edition).validateElement(element);
    }

    /// Validates an element and all descendants while allowing the supplied element to remain
    /// attached to a parent outside the selected subtree.
    ///
    /// This form is used by serializers, which commonly write a child tag directly. The complete
    /// tree validator above continues to require a parent-free root.
    ///
    /// @param element subtree root to validate
    /// @throws NBTValidationException if a descendant or payload is invalid
    public static void validateSubtree(NBTElement element) throws NBTValidationException {
        validateSubtree(element, MinecraftEdition.JAVA_EDITION);
    }

    /// Validates an attached subtree using the selected edition's encoding rules.
    ///
    /// @param element subtree root to validate
    /// @param edition encoding edition
    /// @throws NBTValidationException if a descendant or payload is invalid
    public static void validateSubtree(NBTElement element, MinecraftEdition edition)
            throws NBTValidationException {
        new NBTStructureValidator(edition).validateElement(element, true);
    }

    /// Validates a tree using this validator's edition.
    ///
    /// @param element root element to validate
    /// @throws NBTValidationException if any structural or encoding invariant is violated
    public void validateElement(NBTElement element) throws NBTValidationException {
        validateElement(element, false);
    }

    private void validateElement(NBTElement element, boolean allowAttachedRoot)
            throws NBTValidationException {
        Objects.requireNonNull(element, "element");

        Map<NBTElement, Integer> states = new IdentityHashMap<>();
        Deque<Frame> pending = new ArrayDeque<>();
        pending.push(new Frame(element, null, -1, "$", false));

        while (!pending.isEmpty()) {
            Frame frame = pending.pop();
            if (frame.exit) {
                states.put(frame.element, COMPLETE);
                continue;
            }

            Integer previousState = states.get(frame.element);
            if (previousState != null) {
                if (previousState == ACTIVE) {
                    fail("cycle detected", frame.path);
                }
                fail("element identity is shared by more than one parent", frame.path);
            }
            states.put(frame.element, ACTIVE);

            validateRelation(frame, allowAttachedRoot);
            if (frame.element instanceof Tag tag) {
                validateTagMetadata(tag, frame.path);
            }

            pending.push(new Frame(frame.element, frame.parent, frame.index, frame.path, true));
            pushChildren(frame, pending);
        }
    }

    /// Tests a tree without exposing validation details through a checked exception.
    public boolean test(NBTElement element) {
        try {
            validateElement(element);
            return true;
        } catch (NBTValidationException | NullPointerException exception) {
            return false;
        }
    }

    /// Returns the encoding edition used by this validator.
    public MinecraftEdition edition() {
        return edition;
    }

    private void validateRelation(Frame frame, boolean allowAttachedRoot) throws NBTValidationException {
        NBTElement element = frame.element;
        NBTElement parent = frame.parent;
        if (parent == null) {
            if (!allowAttachedRoot && element.getParent() != null) {
                fail("root element has a parent", frame.path);
            }
            if (allowAttachedRoot && element.getParent() != null) {
                validateAttachedRootRelation(element, frame.path);
            }
            return;
        }

        if (parent instanceof ParentTag<?> parentTag && element instanceof Tag tag) {
            if (tag.getParent() != parentTag || tag.getIndex() != frame.index) {
                fail("tag parent/index metadata does not match its container", frame.path);
            }
            return;
        }

        if (parent instanceof Chunk chunk && element instanceof CompoundTag tag) {
            if (tag.getParent() != chunk || tag.getIndex() != 0) {
                fail("chunk root parent/index metadata does not match", frame.path);
            }
            return;
        }

        if (parent instanceof ChunkRegion region && element instanceof Chunk chunk) {
            if (chunk.getParent() != region || chunk.getLocalIndex() != frame.index) {
                fail("chunk parent/index metadata does not match its region slot", frame.path);
            }
            return;
        }

        fail("element has an invalid parent type", frame.path);
    }

    private static void validateAttachedRootRelation(NBTElement element, String path)
            throws NBTValidationException {
        NBTParent<?> parent = element.getParent();
        if (parent instanceof ParentTag<?> parentTag && element instanceof Tag tag) {
            if (tag.getIndex() < 0 || tag.getIndex() >= parentTag.size()
                    || parentTag.getTag(tag.getIndex()) != tag) {
                fail("attached subtree root has invalid parent/index metadata", path);
            }
            return;
        }
        if (parent instanceof Chunk chunk && element instanceof CompoundTag tag) {
            if (tag.getIndex() != 0 || chunk.getRootTag() != tag) {
                fail("attached subtree root has invalid chunk metadata", path);
            }
            return;
        }
        fail("attached subtree root has an invalid parent relation", path);
    }

    private void validateTagMetadata(Tag tag, String path) throws NBTValidationException {
        long nameLength = encodedLength(tag.getName());
        if (nameLength > 0xFFFFL) {
            fail("tag name exceeds the two-byte encoded length limit", path);
        }
        if (tag instanceof StringTag stringTag && encodedLength(stringTag.getValue()) > 0xFFFFL) {
            fail("string value exceeds the two-byte encoded length limit", path);
        }
    }

    private long encodedLength(String value) {
        return edition == MinecraftEdition.JAVA_EDITION
                ? TextUtils.mutf8Length(value)
                : TextUtils.utf8Length(value);
    }

    private void pushChildren(Frame frame, Deque<Frame> pending) throws NBTValidationException {
        NBTElement element = frame.element;
        if (element instanceof ChunkRegion region) {
            for (int i = region.size() - 1; i >= 0; i--) {
                pending.push(new Frame(region.getChunk(i), region, i, frame.path + ".chunk[" + i + "]", false));
            }
            return;
        }

        if (element instanceof Chunk chunk) {
            Tag root = chunk.getRootTag();
            if (root == null) {
                return;
            }
            if (!(root instanceof CompoundTag)) {
                fail("chunk root must be a compound tag", frame.path);
            }
            pending.push(new Frame(root, chunk, 0, frame.path + ".root", false));
            return;
        }

        if (!(element instanceof NBTParent<?> parent)) {
            return;
        }

        if (element instanceof CompoundTag compound) {
            Set<String> names = new HashSet<>();
            for (int i = parent.size() - 1; i >= 0; i--) {
                Tag child = ((CompoundTag) parent).getTag(i);
                if (!names.add(child.getName())) {
                    fail("compound contains duplicate child names", frame.path);
                }
                if (compound.get(child.getName()) != child) {
                    fail("compound name index does not point to the child", frame.path);
                }
                pending.push(new Frame(child, parent, i, frame.path + "." + child.getName(), false));
            }
            return;
        }

        if (element instanceof ListTag<?> list) {
            TagType<?> elementType = list.getElementType();
            if (elementType == null && !list.isEmpty()) {
                fail("non-empty list has TAG_End as its element type", frame.path);
            }
            for (int i = parent.size() - 1; i >= 0; i--) {
                Tag child = parentChild(parent, i);
                if (elementType == null || child.getType() != elementType) {
                    fail("list contains an element with a different type", frame.path + "[" + i + "]");
                }
                if (!child.getName().isEmpty()) {
                    fail("list elements must have empty names", frame.path + "[" + i + "]");
                }
                pending.push(new Frame(child, parent, i, frame.path + "[" + i + "]", false));
            }
            return;
        }

        if (element instanceof ArrayTag<?, ?, ?, ?> array) {
            Object values = array.getArray();
            if (Array.getLength(values) != array.size() || array.getBuffer().remaining() != array.size()) {
                fail("primitive array length and backing storage disagree", frame.path);
            }
            for (int i = parent.size() - 1; i >= 0; i--) {
                Tag child = parentChild(parent, i);
                if (child.getType() != array.getElementType()) {
                    fail("primitive array contains an element with a different type", frame.path + "[" + i + "]");
                }
                if (!(child instanceof ValueTag<?> valueTag)
                        || !Objects.equals(valueTag.getValue(), array.getValue(i))) {
                    fail("primitive array child does not match its backing value", frame.path + "[" + i + "]");
                }
                if (!child.getName().isEmpty()) {
                    fail("primitive array elements must have empty names", frame.path + "[" + i + "]");
                }
                pending.push(new Frame(child, parent, i, frame.path + "[" + i + "]", false));
            }
        }
    }

    private static Tag parentChild(NBTParent<?> parent, int index) throws NBTValidationException {
        if (!(parent instanceof ParentTag<?> parentTag)) {
            throw new NBTValidationException("Unexpected non-tag parent child");
        }
        try {
            return parentTag.getTag(index);
        } catch (ClassCastException | IndexOutOfBoundsException exception) {
            throw new NBTValidationException("Parent contains an invalid child at index " + index, exception);
        }
    }

    private static void fail(String reason, String path) throws NBTValidationException {
        throw new NBTValidationException(reason + " at " + path);
    }

    private static final class Frame {
        private final NBTElement element;
        private final NBTElement parent;
        private final int index;
        private final String path;
        private final boolean exit;

        private Frame(NBTElement element, NBTElement parent, int index, String path, boolean exit) {
            this.element = element;
            this.parent = parent;
            this.index = index;
            this.path = path;
            this.exit = exit;
        }
    }
}
