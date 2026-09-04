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
// Modified by MinecraftSTL in 2026 for the XYML namespace and monorepo build.
package space.minecraftstl.xyml.library.nbt.tag;

import space.minecraftstl.xyml.library.nbt.NBTParent;
import space.minecraftstl.xyml.library.nbt.chunk.Chunk;
import space.minecraftstl.xyml.library.nbt.internal.ArrayAccessor;
import org.intellij.lang.annotations.Flow;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.UnknownNullability;

import java.util.*;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/// Base class for tags that can contain other tags as children.
///
/// These are the all possible types of parent tags:
///
/// - [CompoundTag]: A tag that holds a collection of named tags.
/// - [ListTag]: A tag that holds a collection of unnamed tags.
/// - [ArrayTag]: A tag that holds an array of primitive values.
///     - [ByteArrayTag]: A tag that holds an array of [byte tags][ByteTag].
///     - [IntArrayTag]: A tag that holds an array of [int tags][IntTag]. Sometimes used for UUIDs.
///     - [LongArrayTag]: A tag that holds an array of [long tags][LongTag].
///
/// @see Tag
/// @see CompoundTag
/// @see ListTag
/// @see ArrayTag
@NotNullByDefault
public sealed abstract class ParentTag<T extends Tag> extends Tag
        implements NBTParent<T>, Iterable<T>
        permits CompoundTag, ListTag, ArrayTag {

    private static final Tag[] EMPTY_TAGS = new Tag[0];

    // Store all sub-tags in an array.
    //
    // For ListTag and CompoundTag, the array length is large or equal to the size,
    // and all tags in [0, size) are not null.
    //
    // For ArrayTag, we may lazy allocate the array and the tags, so the array length
    // may be smaller than the size, and some tags in [0, size) may be null.
    @UnknownNullability
    Tag[] tags = EMPTY_TAGS;
    int size;

    ParentTag() {
    }

    /// Prepares to update the name of the given subtag.
    ///
    /// Used internally by [Tag#setName(String)].
    ///
    /// @see Tag#setName(String)
    abstract void preUpdateSubTagName(Tag tag, String oldName, String newName) throws IllegalArgumentException;

    final void ensureTagsCapacity(int minCapacity) {
        if (minCapacity > tags.length) {
            tags = Arrays.copyOf(tags, ArrayAccessor.nextCapacity(tags.length, minCapacity));
        }
    }

    final void ensureTagsCapacityForAdd() {
        if (size >= tags.length) {
            tags = Arrays.copyOf(tags, ArrayAccessor.nextCapacity(tags.length, size + 1));
        }
    }

    /// Removes the tag at the given index from the array, and decreases the size.
    ///
    /// @return The old tag at the given index.
    final @UnknownNullability T removeTagFromArray(int index) {
        assert index >= 0 && index < size;

        if (index >= tags.length) {
            return null;
        }

        @SuppressWarnings("unchecked")
        T oldTag = (T) tags[index];

        int arrayEnd = Math.min(size, tags.length);
        if (index < arrayEnd - 1) {
            System.arraycopy(tags, index + 1, tags, index, arrayEnd - index - 1);
            tags[arrayEnd - 1] = null;
        } else if (oldTag != null) {
            tags[index] = null;
        }

        return oldTag;
    }

    /// Updates the indexes of the subtags starting from the given index.
    final void updateIndexes(int startIndex) {
        for (int i = startIndex, end = Math.min(size, tags.length); i < end; i++) {
            Tag subTag = tags[i];
            if (subTag != null) {
                subTag.setIndex(i);
            }
        }
    }

    @Override
    public abstract TagType<? extends ParentTag<?>> getType();

    @Override
    @Contract(value = "_ -> this", mutates = "this")
    public ParentTag<T> setName(String name) throws IllegalArgumentException {
        setName0(name);
        return this;
    }

    /// Returns `true` if this tag has no subtags, `false` otherwise.
    @Override
    @Contract(pure = true)
    public final boolean isEmpty() {
        return size == 0;
    }

    /// Returns the number of subtags in this tag.
    @Override
    @Contract(pure = true)
    public final int size() {
        return size;
    }

    /// Returns the subtag at the given index.
    ///
    /// @throws IndexOutOfBoundsException if the index is out of bounds.
    @Contract(pure = true)
    @Flow(sourceIsContainer = true)
    public T getTag(int index) throws IndexOutOfBoundsException {
        Objects.checkIndex(index, size);
        @SuppressWarnings("unchecked")
        T tag = (T) tags[index];
        return tag;
    }

    final void moveTagToLast(T tag) {
        assert tag.getParent() == this;

        int index = tag.getIndex();

        if (tag.getIndex() == this.size() - 1) {
            // The tag is already the last child of this tag, so we don't need to do anything.

            assert tag == tags[index];
        } else {
            // Move the tag to the end of the subTags list.

            Tag oldTag = removeTagFromArray(index);
            if (oldTag != tag) {
                throw new AssertionError("Expected " + tag + ", but got " + oldTag);
            }

            tags[size - 1] = tag;

            updateIndexes(index);
        }
    }

    /// Validates that a tag can be attached below this parent without changing either tree.
    ///
    /// The check deliberately follows the complete parent chain by identity. This catches both
    /// direct self references and attempts to attach an ancestor below one of its descendants.
    ///
    /// @param tag candidate child
    /// @throws IllegalArgumentException if the candidate would create an invalid ownership link
    final void validateTagForAttach(T tag) throws IllegalArgumentException {
        Objects.requireNonNull(tag, "tag");
        if (tag == this) {
            throw new IllegalArgumentException("A tag cannot contain itself");
        }

        NBTParent<?> cursor = this;
        while (cursor != null) {
            if (cursor == tag) {
                throw new IllegalArgumentException("A tag cannot contain one of its ancestors");
            }
            cursor = cursor.getParent();
        }

        NBTParent<?> oldParent = tag.getParent();
        if (oldParent != null && oldParent != this) {
            if (tag.getIndex() < 0) {
                throw new IllegalArgumentException("The tag has an invalid parent index");
            }
            if (oldParent instanceof ParentTag<?> oldParentTag) {
                if (tag.getIndex() >= oldParentTag.size()
                        || tag.getIndex() >= oldParentTag.tags.length
                        || oldParentTag.tags[tag.getIndex()] != tag) {
                    throw new IllegalArgumentException("The tag has an invalid parent index");
                }
            }
        }
    }

    /// Validates that an index identifies the expected child before a structural operation.
    ///
    /// @param index child index
    /// @param expected expected child identity
    /// @throws IllegalArgumentException if the backing array and child metadata disagree
    final void validateChildIdentity(int index, Tag expected) throws IllegalArgumentException {
        if (index < 0 || index >= size || index >= tags.length || tags[index] != expected
                || expected.getParent() != this || expected.getIndex() != index) {
            throw new IllegalArgumentException("The parent-child ownership invariant is inconsistent");
        }
    }

    /// Detaches a child from its current parent after all destination checks have passed.
    ///
    /// @param tag child to detach
    @SuppressWarnings({"rawtypes", "unchecked"})
    final void detachFromCurrentParent(Tag tag) {
        NBTParent oldParent = tag.getParent();
        if (oldParent != null && oldParent != this) {
            oldParent.removeElement(tag);
        }
    }

    /// Inserts an already validated, detached child into the backing array.
    ///
    /// @param index insertion index
    /// @param tag detached child
    final void insertTagInternal(int index, T tag) {
        ensureTagsCapacityForAdd();
        if (index < size) {
            System.arraycopy(tags, index, tags, index + 1, size - index);
        }
        tags[index] = tag;
        size++;
        tag.setParent(this, index);
        updateIndexes(index + 1);
    }

    /// Validates a complete child collection before attaching any element.
    ///
    /// @param candidates candidate children in their intended order
    /// @throws IllegalArgumentException if an ownership, cycle, or index invariant would fail
    final void validateTagBatch(Iterable<? extends T> candidates) throws IllegalArgumentException {
        Objects.requireNonNull(candidates, "candidates");
        List<T> snapshot = new ArrayList<>();
        for (T candidate : candidates) {
            snapshot.add(Objects.requireNonNull(candidate, "candidate"));
        }
        validateTagBatchSnapshot(snapshot);
    }

    /// Validates a materialized batch against a non-mutating simulation of all affected containers.
    ///
    /// The simulation follows the compatibility semantics of [#addTag(Tag)] while keeping every
    /// real parent untouched. It is intentionally identity-based: two equal-but-distinct tags are
    /// valid candidates, whereas attaching the same object twice would create an ambiguous move.
    ///
    /// @param candidates materialized candidate children in submission order
    /// @throws IllegalArgumentException if any candidate or simulated intermediate state is invalid
    private void validateTagBatchSnapshot(List<? extends T> candidates) throws IllegalArgumentException {
        Set<Tag> identities = Collections.newSetFromMap(new IdentityHashMap<>());
        IdentityHashMap<Tag, @Nullable NBTParent<?>> simulatedParents = new IdentityHashMap<>();
        for (T candidate : candidates) {
            if (!identities.add(candidate)) {
                throw new IllegalArgumentException("A child cannot occur more than once in a batch");
            }
            validateTagForAttach(candidate);
            validateCurrentOwnership(candidate);
            simulatedParents.put(candidate, candidate.getParent());
        }

        IdentityHashMap<NBTParent<?>, List<Tag>> simulatedChildren = new IdentityHashMap<>();
        List<Tag> destination = snapshotChildren(this);
        simulatedChildren.put(this, destination);
        @Nullable TagType<?> listElementType = this instanceof ListTag<?> list
                ? list.getElementType()
                : null;

        for (T candidate : candidates) {
            @Nullable NBTParent<?> oldParent = simulatedParents.get(candidate);
            if (oldParent == this) {
                if (!removeIdentity(destination, candidate)) {
                    throw new IllegalArgumentException("The parent-child ownership invariant is inconsistent");
                }
                destination.add(candidate);
                continue;
            }

            if (oldParent != null) {
                List<Tag> source = simulatedChildren.computeIfAbsent(oldParent,
                        ParentTag::snapshotChildren);
                if (!removeIdentity(source, candidate)) {
                    // A Compound replacement may have detached a candidate that is also later
                    // present in this batch. The real addTag sequence permits that candidate to
                    // be attached again, so treat it as detached in the simulation as well.
                    if (simulatedParents.get(candidate) != null) {
                        throw new IllegalArgumentException(
                                "The parent-child ownership invariant is inconsistent");
                    }
                }
                simulatedParents.put(candidate, null);
            }

            if (this instanceof ListTag<?> list) {
                if (listElementType != null && candidate.getType() != listElementType) {
                    throw new IllegalArgumentException("Cannot add a tag of type " + candidate.getType()
                            + " to a list of type " + listElementType);
                }
                if (listElementType == null) {
                    listElementType = candidate.getType();
                }
            } else if (this instanceof ArrayTag<?, ?, ?, ?> array) {
                if (candidate.getType() != array.getElementType()) {
                    throw new IllegalArgumentException("Cannot add a tag of type " + candidate.getType()
                            + " to an array of type " + array.getElementType());
                }
            } else if (this instanceof CompoundTag) {
                Tag existing = findNamed(destination, candidate.getName());
                if (existing != null) {
                    destination.remove(existing);
                    simulatedParents.put(existing, null);
                }
            }
            destination.add(candidate);
            simulatedParents.put(candidate, this);
        }
    }

    /// Validates one candidate's current parent/index metadata without materializing lazy tags.
    ///
    /// @param tag candidate tag
    /// @throws IllegalArgumentException if its ownership metadata is inconsistent
    private static void validateCurrentOwnership(Tag tag) throws IllegalArgumentException {
        @Nullable NBTParent<?> parent = tag.getParent();
        int index = tag.getIndex();
        if (parent == null) {
            if (index != -1) {
                throw new IllegalArgumentException("A detached tag must have index -1");
            }
            return;
        }
        if (index < 0) {
            throw new IllegalArgumentException("An attached tag must have a non-negative index");
        }
        if (parent instanceof ParentTag<?> parentTag) {
            if (index >= parentTag.size() || index >= parentTag.tags.length || parentTag.tags[index] != tag) {
                throw new IllegalArgumentException("The parent-child ownership invariant is inconsistent");
            }
        } else if (parent instanceof Chunk chunk) {
            if (index != 0 || chunk.getRootTag() != tag) {
                throw new IllegalArgumentException("The chunk-child ownership invariant is inconsistent");
            }
        } else {
            throw new IllegalArgumentException("Unsupported NBT parent implementation");
        }
    }

    /// Captures materialized children of a parent without triggering lazy array-tag creation.
    ///
    /// @param parent source or destination parent
    /// @return identity-preserving child snapshot
    private static List<Tag> snapshotChildren(NBTParent<?> parent) throws IllegalArgumentException {
        List<Tag> children = new ArrayList<>();
        if (parent instanceof ParentTag<?> parentTag) {
            Set<Tag> identities = Collections.newSetFromMap(new IdentityHashMap<>());
            for (int index = 0; index < parentTag.size(); index++) {
                @Nullable Tag child = index < parentTag.tags.length ? parentTag.tags[index] : null;
                if (child != null) {
                    validateCurrentOwnership(child);
                    if (!identities.add(child)) {
                        throw new IllegalArgumentException("A parent contains the same child more than once");
                    }
                    children.add(child);
                } else if (!(parentTag instanceof ArrayTag<?, ?, ?, ?>)) {
                    throw new IllegalArgumentException("A non-array parent contains a null child");
                }
            }
            return children;
        }
        if (parent instanceof Chunk chunk) {
            @Nullable CompoundTag root = chunk.getRootTag();
            if (root != null) {
                validateCurrentOwnership(root);
                children.add(root);
            }
            return children;
        }
        throw new IllegalArgumentException("Unsupported NBT parent implementation");
    }

    /// Removes one identity from a simulated child list.
    ///
    /// @param children simulated children
    /// @param candidate identity to remove
    /// @return whether the identity was found
    private static boolean removeIdentity(List<Tag> children, Tag candidate) {
        for (int index = 0; index < children.size(); index++) {
            if (children.get(index) == candidate) {
                children.remove(index);
                return true;
            }
        }
        return false;
    }

    /// Finds a simulated Compound child by its current name.
    ///
    /// @param children simulated children
    /// @param name candidate name
    /// @return matching child, or `null`
    private static @Nullable Tag findNamed(List<Tag> children, String name) {
        for (Tag child : children) {
            if (child.getName().equals(name)) {
                return child;
            }
        }
        return null;
    }

    /// Adds the `tag` to this tag.
    ///
    /// If the `tag` is already a child of this tag, move it to the end of the list.
    ///
    /// If the `tag` is already a child of another tag, removes it from old parent and adds it to this tag.
    @Contract(value = "_ -> this", mutates = "this,param1")
    public abstract ParentTag<T> addTag(@Flow(targetIsContainer = true)
                                        T tag) throws IllegalArgumentException;

    /// Adds all `tags` to this tag.
    ///
    /// @see #addTag(Tag)
    public final void addTags(@Flow(sourceIsContainer = true, targetIsContainer = true)
                              Iterable<? extends T> tags) throws IllegalArgumentException {
        if (this == tags) {
            return;
        }

        // Snapshot first: adding a parent's own children otherwise mutates the iterator and skips
        // every successor that shifts into the current cursor.
        List<T> snapshot = new ArrayList<>();
        for (T tag : tags) {
            snapshot.add(Objects.requireNonNull(tag, "tag"));
        }
        validateTagBatchSnapshot(snapshot);
        for (T tag : snapshot) {
            this.addTag(tag);
        }
    }

    /// Adds all `tags` to this tag.
    ///
    /// @see #addTag(Tag)
    @SafeVarargs
    public final void addTags(@Flow(sourceIsContainer = true, targetIsContainer = true)
                              T... tags) throws IllegalArgumentException {
        Objects.requireNonNull(tags, "tags");
        List<T> snapshot = new ArrayList<>(tags.length);
        for (T tag : tags) {
            snapshot.add(Objects.requireNonNull(tag, "tag"));
        }
        validateTagBatchSnapshot(snapshot);
        for (T tag : snapshot) {
            this.addTag(tag);
        }
    }

    /// Removes the tag at the given index from this tag.
    ///
    /// @param index The index of the tag to remove.
    /// @throws IndexOutOfBoundsException if the index is out of bounds.
    @Contract(mutates = "this")
    public void removeAt(int index) throws IndexOutOfBoundsException {
        removeTagAt(index);
    }

    /// Removes the tag at the given index from this tag.
    ///
    /// If the return value is not needed, use [ParentTag#removeAt(int)] instead.
    ///
    /// @param index The index of the tag to remove.
    /// @return The removed tag.
    /// @throws IndexOutOfBoundsException if the index is out of bounds.
    @Contract(mutates = "this")
    @SuppressWarnings("UnusedReturnValue")
    public abstract T removeTagAt(int index) throws IndexOutOfBoundsException;

    /// Removes the `tag` from this tag.
    ///
    /// @throws IllegalArgumentException if the `tag` is not a child of this tag.
    @Contract(mutates = "this,param1")
    public void removeTag(Tag tag) throws IllegalArgumentException {
        Objects.requireNonNull(tag, "tag");
        if (tag.getParent() != this) {
            throw new IllegalArgumentException("The tag is not a child of this tag");
        }

        if (tag.getIndex() < 0 || tag.getIndex() >= size || tags[tag.getIndex()] != tag) {
            throw new IllegalArgumentException("The tag has an invalid index in this tag");
        }
        removeAt(tag.getIndex());
    }

    /// Inserts a tag at an exact position while preserving the order of existing children.
    ///
    /// @param index insertion index, including `size()` to append
    /// @param tag tag to attach
    /// @return this parent
    /// @throws IndexOutOfBoundsException if the index is outside `0..size()`
    /// @throws IllegalArgumentException if the tag cannot be attached
    @Contract(value = "_, _ -> this", mutates = "this,param2")
    public ParentTag<T> insertTag(int index, T tag) throws IllegalArgumentException {
        if (index < 0 || index > size) {
            throw new IndexOutOfBoundsException("index: " + index + ", size: " + size);
        }
        validateTagForAttach(tag);
        if (tag.getParent() == this) {
            throw new IllegalArgumentException("The tag is already a child of this tag");
        }
        detachFromCurrentParent(tag);
        insertTagInternal(index, tag);
        return this;
    }

    /// Replaces one child without exposing an intermediate invalid destination.
    ///
    /// @param index child index
    /// @param replacement replacement tag
    /// @return the detached former child
    /// @throws IndexOutOfBoundsException if the index is outside the current children
    /// @throws IllegalArgumentException if the replacement cannot be attached
    @Contract(value = "_, _ -> new", mutates = "this,param2")
    public T replaceTagAt(int index, T replacement) throws IllegalArgumentException {
        Objects.checkIndex(index, size);
        Objects.requireNonNull(replacement, "replacement");
        validateTagForAttach(replacement);
        if (replacement.getParent() == this) {
            throw new IllegalArgumentException("The replacement is already a child of this tag");
        }
        detachFromCurrentParent(replacement);
        T previous = removeTagAt(index);
        insertTagInternal(index, replacement);
        return previous;
    }

    /// Moves one child to another position in this parent.
    ///
    /// @param fromIndex current index
    /// @param toIndex destination index
    /// @return this parent
    /// @throws IndexOutOfBoundsException if either index is outside the current children
    @Contract(value = "_, _ -> this", mutates = "this")
    public ParentTag<T> moveTag(int fromIndex, int toIndex) {
        Objects.checkIndex(fromIndex, size);
        Objects.checkIndex(toIndex, size);
        if (fromIndex == toIndex) {
            return this;
        }
        @SuppressWarnings("unchecked")
        T moved = (T) tags[fromIndex];
        if (fromIndex < toIndex) {
            System.arraycopy(tags, fromIndex + 1, tags, fromIndex, toIndex - fromIndex);
        } else {
            System.arraycopy(tags, toIndex, tags, toIndex + 1, fromIndex - toIndex);
        }
        tags[toIndex] = moved;
        updateIndexes(Math.min(fromIndex, toIndex));
        return this;
    }

    /// Returns the child at an exact index without exposing internal storage.
    ///
    /// @param index child index
    /// @return child at the index
    /// @throws IndexOutOfBoundsException if the index is outside this parent
    @Contract(pure = true)
    public final T childAt(int index) throws IndexOutOfBoundsException {
        return getTag(index);
    }

    /// @see #removeTag(Tag)
    @ApiStatus.Obsolete
    @Override
    public final void removeElement(Tag tag) throws IllegalArgumentException {
        removeTag(tag);
    }

    /// Removes all subtags from this tag.
    @Contract(mutates = "this")
    public void clear() {
        for (int i = 0, end = Math.min(size, tags.length); i < end; i++) {
            Tag subTag = tags[i];
            if (subTag != null) {
                subTag.setParent(null, -1);
            }
        }

        tags = EMPTY_TAGS;
        size = 0;
    }

    @Override
    public Iterator<T> iterator() {
        if (size == 0) {
            return Collections.emptyIterator();
        }

        return new Iterator<>() {
            private int cursor = 0;

            @Override
            public boolean hasNext() {
                return cursor < size;
            }

            @Override
            public T next() {
                if (cursor >= size) {
                    throw new NoSuchElementException();
                }
                return getTag(cursor++);
            }
        };
    }

    /// Returns a stream of subtags.
    @Override
    public final Stream<T> stream() {
        return StreamSupport.stream(Spliterators.spliterator(iterator(), size(), 0), false);
    }

    @Override
    @Contract(value = "-> new", pure = true)
    public abstract ParentTag<T> clone();
}
