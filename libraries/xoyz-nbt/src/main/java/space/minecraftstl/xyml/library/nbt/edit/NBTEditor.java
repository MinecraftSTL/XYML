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
// Added by MinecraftSTL in 2026 for the generic XoyzNBT editing API.
package space.minecraftstl.xyml.library.nbt.edit;

import space.minecraftstl.xyml.library.nbt.NBTElement;
import space.minecraftstl.xyml.library.nbt.NBTParent;
import space.minecraftstl.xyml.library.nbt.chunk.Chunk;
import space.minecraftstl.xyml.library.nbt.chunk.ChunkRegion;
import space.minecraftstl.xyml.library.nbt.tag.ArrayTag;
import space.minecraftstl.xyml.library.nbt.tag.CompoundTag;
import space.minecraftstl.xyml.library.nbt.tag.ListTag;
import space.minecraftstl.xyml.library.nbt.tag.ParentTag;
import space.minecraftstl.xyml.library.nbt.tag.Tag;
import space.minecraftstl.xyml.library.nbt.tag.TagType;
import space.minecraftstl.xyml.library.nbt.tag.ValueTag;
import space.minecraftstl.xyml.library.nbt.validation.NBTStructureValidator;
import space.minecraftstl.xyml.library.nbt.validation.NBTValidationException;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;

/// A synchronized, revision-aware editing session for an NBT element.
///
/// The session owns a detached copy of the supplied root. All inserted and replacement elements
/// are copied before they become part of the session, and node handles become stale after every
/// successful mutation.
///
/// @param <E> root element type
@NotNullByDefault
public final class NBTEditor<E extends NBTElement> {
    private static final int DEFAULT_HISTORY_LIMIT = 100;

    private final Object identity = new Object();
    private final int historyLimit;
    private final Deque<HistoryEntry> undo = new ArrayDeque<>();
    private final Deque<HistoryEntry> redo = new ArrayDeque<>();
    private E root;
    private long revision;
    private long stateSequence;
    private long stateId;
    private long savedStateId;

    private NBTEditor(E root, int historyLimit) {
        E checkedRoot = Objects.requireNonNull(root, "root");
        try {
            NBTStructureValidator.validate(checkedRoot);
        } catch (NBTValidationException exception) {
            throw new IllegalArgumentException("The supplied root is not a valid NBT tree", exception);
        }
        this.root = cloneElement(checkedRoot);
        if (historyLimit < 1) {
            throw new IllegalArgumentException("historyLimit must be positive");
        }
        this.historyLimit = historyLimit;
    }

    /// Creates an editor with a detached deep copy and a 100-entry history.
    ///
    /// @param root input root
    /// @param <E> root type
    /// @return new editor
    public static <E extends NBTElement> NBTEditor<E> of(E root) {
        return new NBTEditor<>(root, DEFAULT_HISTORY_LIMIT);
    }

    /// Creates an editor with a detached deep copy and a bounded history.
    ///
    /// @param root input root
    /// @param historyLimit maximum number of undo entries
    /// @param <E> root type
    /// @return new editor
    public static <E extends NBTElement> NBTEditor<E> of(E root, int historyLimit) {
        return new NBTEditor<>(root, historyLimit);
    }

    /// Returns the current session revision.
    public synchronized long getRevision() {
        return revision;
    }

    /// Returns whether the session differs from its last save point.
    public synchronized boolean isDirty() {
        return stateId != savedStateId;
    }

    /// Returns whether an undo operation is available.
    public synchronized boolean canUndo() {
        return !undo.isEmpty();
    }

    /// Returns whether a redo operation is available.
    public synchronized boolean canRedo() {
        return !redo.isEmpty();
    }

    /// Returns a read-only root node handle.
    public synchronized NBTNode getRootNode() {
        return describe(root, NBTAddress.root());
    }

    /// Alias for [#getRootNode()].
    public synchronized NBTNode rootNode() {
        return getRootNode();
    }

    /// Resolves an address in the current revision.
    ///
    /// @param address exact address
    /// @return current node at the address
    /// @throws NBTEditException when no node exists at the address
    public synchronized NBTNode resolve(NBTAddress address) throws NBTEditException {
        Objects.requireNonNull(address, "address");
        NBTElement element = find(address);
        if (element == null) {
            throw error(NBTEditException.Reason.NOT_FOUND, "No element exists at " + address);
        }
        return describe(element, address);
    }

    /// Alias for [#resolve(NBTAddress)].
    public synchronized NBTNode getNode(NBTAddress address) throws NBTEditException {
        return resolve(address);
    }

    /// Resolves a node and returns its detached content.
    ///
    /// @param address exact address
    /// @return detached node content
    /// @throws NBTEditException when no node exists at the address
    public synchronized NBTElement snapshot(NBTAddress address) throws NBTEditException {
        return snapshot(resolve(address));
    }

    /// Returns a detached copy of the selected node's current content.
    ///
    /// This alias is useful to callers that want a stable value without exposing the working tree.
    ///
    /// @param node current node handle
    /// @return detached copy
    /// @throws NBTEditException if the node is stale
    public synchronized NBTElement detachedSnapshot(NBTNode node) throws NBTEditException {
        return snapshot(node);
    }

    /// Returns a child node using a current parent handle.
    ///
    /// @param parent current parent node
    /// @param index child index
    /// @return child node
    /// @throws NBTEditException if the handle or index is invalid
    public synchronized NBTNode getChild(NBTNode parent, int index) throws NBTEditException {
        NBTElement element = requireNode(parent);
        if (!(element instanceof NBTParent<?> container)) {
            throw error(NBTEditException.Reason.INVALID_TARGET, "The node is not a parent");
        }
        if (index < 0 || index >= container.size()) {
            throw error(NBTEditException.Reason.INVALID_INDEX, "Child index is outside the parent");
        }
        NBTElement child = childAt(container, index);
        return describe(child, childAddress(parent.address(), container, child, index));
    }

    /// Returns an immutable snapshot of all immediate children of a current parent node.
    ///
    /// @param parent current parent node
    /// @return immutable child metadata in display order
    /// @throws NBTEditException if the handle is stale or not a parent
    public synchronized List<NBTNode> getChildren(NBTNode parent) throws NBTEditException {
        NBTElement element = requireNode(parent);
        if (!(element instanceof NBTParent<?> container)) {
            throw error(NBTEditException.Reason.INVALID_TARGET, "The node is not a parent");
        }
        List<NBTNode> result = new ArrayList<>(container.size());
        for (int index = 0; index < container.size(); index++) {
            NBTElement child = childAt(container, index);
            result.add(describe(child, childAddress(parent.address(), container, child, index)));
        }
        return List.copyOf(result);
    }

    /// Alias for [#getChildren(NBTNode)].
    public synchronized List<NBTNode> children(NBTNode parent) throws NBTEditException {
        return getChildren(parent);
    }

    /// Returns a detached deep copy of the selected node.
    ///
    /// @param node current node handle
    /// @return detached snapshot
    /// @throws NBTEditException if the handle is stale
    public synchronized NBTElement snapshot(NBTNode node) throws NBTEditException {
        return cloneElement(requireNode(node));
    }

    /// Returns a detached deep copy of the current root.
    ///
    /// @return detached root snapshot
    @SuppressWarnings("unchecked")
    public synchronized E snapshot() {
        return (E) cloneElement(root);
    }

    /// Captures a detached, revision-labelled savepoint.
    ///
    /// The returned object can be passed to a background writer. It is only accepted by
    /// [#markSaved(NBTSavepoint)] if the editor is still at the same revision when the write ends.
    ///
    /// @return detached savepoint
    public synchronized NBTSavepoint<E> saveSnapshot() {
        return new NBTSavepoint<>(identity, revision, cloneElement(root));
    }

    /// Alias for [#saveSnapshot()].
    public synchronized NBTSavepoint<E> createSaveSnapshot() {
        return saveSnapshot();
    }

    /// Alias for [#snapshot()].
    public synchronized E getRootSnapshot() {
        return snapshot();
    }

    /// Renames a compound child.
    ///
    /// @param target current node
    /// @param name new non-empty name
    /// @return node at the new name
    /// @throws NBTEditException if the target or name is invalid
    public synchronized NBTNode rename(NBTNode target, String name) throws NBTEditException {
        NBTElement element = requireNode(target);
        if (!(element instanceof Tag tag) || tag.getParent() == null) {
            throw error(NBTEditException.Reason.ROOT_OPERATION, "Only a child tag can be renamed");
        }
        if (!(tag.getParent() instanceof CompoundTag parent)) {
            throw error(NBTEditException.Reason.INVALID_TARGET, "Only compound children can be renamed");
        }
        if (name == null || name.isEmpty()) {
            throw error(NBTEditException.Reason.INVALID_NAME, "A compound name must not be empty");
        }
        if (!tag.getName().equals(name) && parent.get(name) != null) {
            throw error(NBTEditException.Reason.DUPLICATE_NAME, "The compound name is already in use");
        }
        MutationState mutation = beginMutation(target.address().parent());
        try {
            tag.setName(name);
            finishMutation(mutation);
            return resolve(NBTAddress.from(tag));
        } catch (NBTEditException exception) {
            abortMutation(mutation);
            throw exception;
        } catch (RuntimeException exception) {
            abortMutation(mutation);
            throw translate(exception);
        }
    }

    /// Renames the node at an address.
    ///
    /// @param address address of a compound child
    /// @param name new non-empty name
    /// @return node at the new name
    /// @throws NBTEditException if the address or name is invalid
    public synchronized NBTNode rename(NBTAddress address, String name) throws NBTEditException {
        return rename(resolve(address), name);
    }

    /// Inserts a detached tag into a parent.
    ///
    /// @param parent current parent node
    /// @param index insertion index
    /// @param value value to copy and insert
    /// @return inserted node in the new revision
    /// @throws NBTEditException if the operation violates a container invariant
    public synchronized NBTNode insertTag(NBTNode parent, int index, Tag value) throws NBTEditException {
        NBTElement parentElement = requireNode(parent);
        Tag copy = Objects.requireNonNull(value, "value").clone();
        try {
            if (parentElement instanceof CompoundTag compound) {
                if (index < 0 || index > compound.size()) {
                    throw error(NBTEditException.Reason.INVALID_INDEX, "Insertion index is outside the compound");
                }
                if (copy.getName().isEmpty() || compound.get(copy.getName()) != null) {
                    throw error(NBTEditException.Reason.DUPLICATE_NAME, "Compound names must be unique and non-empty");
                }
                MutationState mutation = beginMutation(parent.address());
                try {
                    compound.insertTag(index, copy);
                    finishMutation(mutation);
                } catch (NBTEditException exception) {
                    abortMutation(mutation);
                    throw exception;
                } catch (RuntimeException exception) {
                    abortMutation(mutation);
                    throw translate(exception);
                }
            } else if (parentElement instanceof ListTag<?> list) {
                if (index < 0 || index > list.size()) {
                    throw error(NBTEditException.Reason.INVALID_INDEX, "Insertion index is outside the list");
                }
                MutationState mutation = beginMutation(parent.address());
                try {
                    insertIntoList(list, index, copy);
                    finishMutation(mutation);
                } catch (NBTEditException exception) {
                    abortMutation(mutation);
                    throw exception;
                } catch (RuntimeException exception) {
                    abortMutation(mutation);
                    throw translate(exception);
                }
            } else if (parentElement instanceof Chunk chunk) {
                if (index != 0) {
                    throw error(NBTEditException.Reason.INVALID_INDEX,
                            "A chunk root can only occupy index zero");
                }
                if (!(copy instanceof CompoundTag compoundRoot)) {
                    throw error(NBTEditException.Reason.TYPE_MISMATCH,
                            "A chunk root must be a compound tag");
                }
                if (chunk.getRootTag() != null) {
                    throw error(NBTEditException.Reason.INVALID_TARGET,
                            "The chunk already has a root tag");
                }
                MutationState mutation = beginMutation(parent.address());
                try {
                    chunk.setRootTag(compoundRoot);
                    finishMutation(mutation);
                } catch (NBTEditException exception) {
                    abortMutation(mutation);
                    throw exception;
                } catch (RuntimeException exception) {
                    abortMutation(mutation);
                    throw translate(exception);
                }
            } else if (parentElement instanceof ArrayTag<?, ?, ?, ?> array) {
                if (index < 0 || index > array.size()) {
                    throw error(NBTEditException.Reason.INVALID_INDEX, "Insertion index is outside the array");
                }
                MutationState mutation = beginMutation(parent.address());
                try {
                    insertIntoArray(array, index, copy);
                    finishMutation(mutation);
                } catch (NBTEditException exception) {
                    abortMutation(mutation);
                    throw exception;
                } catch (RuntimeException exception) {
                    abortMutation(mutation);
                    throw translate(exception);
                }
            } else {
                throw error(NBTEditException.Reason.INVALID_TARGET, "The node cannot contain tags");
            }
            return resolve(NBTAddress.from(copy));
        } catch (NBTEditException e) {
            throw e;
        } catch (RuntimeException e) {
            throw translate(e);
        }
    }

    /// Inserts a detached tag below the parent at an address.
    ///
    /// @param parentAddress destination parent address
    /// @param index insertion index
    /// @param value value to copy and insert
    /// @return inserted node in the new revision
    /// @throws NBTEditException if the parent or value is invalid
    public synchronized NBTNode insertTag(NBTAddress parentAddress, int index, Tag value)
            throws NBTEditException {
        return insertTag(resolve(parentAddress), index, value);
    }

    /// Replaces a node with a detached copy of another element.
    ///
    /// @param target current target node
    /// @param replacement detached replacement
    /// @return replacement node in the new revision
    /// @throws NBTEditException if the replacement is not compatible
    public synchronized NBTNode replace(NBTNode target, NBTElement replacement) throws NBTEditException {
        NBTElement old = requireNode(target);
        NBTElement copy = cloneElement(Objects.requireNonNull(replacement, "replacement"));
        if (old == root) {
            if (!root.getClass().isInstance(copy)) {
                throw error(NBTEditException.Reason.TYPE_MISMATCH, "The root type cannot change");
            }
            MutationState mutation = beginMutation(NBTAddress.root());
            try {
                root = castRoot(copy);
                finishMutation(mutation);
                return getRootNode();
            } catch (NBTEditException exception) {
                abortMutation(mutation);
                throw exception;
            }
        }
        if (!(old instanceof Tag oldTag) || !(copy instanceof Tag newTag)) {
            throw error(NBTEditException.Reason.INVALID_TARGET, "Only tags below a parent can be replaced");
        }
        NBTParent<?> parent = oldTag.getParent();
        if (parent == null) {
            throw error(NBTEditException.Reason.ROOT_OPERATION, "The target has no parent");
        }
        if (parent instanceof CompoundTag compound) {
            newTag.setName(oldTag.getName());
            if (compound.get(newTag.getName()) != oldTag && compound.get(newTag.getName()) != null) {
                throw error(NBTEditException.Reason.DUPLICATE_NAME, "The replacement name is already in use");
            }
        } else if (parent instanceof Chunk && !(newTag instanceof CompoundTag)) {
            throw error(NBTEditException.Reason.TYPE_MISMATCH, "A chunk root must be a compound tag");
        } else if (!newTag.getName().isEmpty()) {
            newTag.setName("");
        }
        if (parent instanceof Chunk chunk && chunk.getRootTag() != oldTag) {
            throw error(NBTEditException.Reason.NOT_FOUND, "The target is not the current chunk root");
        }
        MutationState mutation = beginMutation(target.address().parent());
        try {
            replaceInParent(parent, oldTag, newTag);
            finishMutation(mutation);
            return resolve(NBTAddress.from(newTag));
        } catch (NBTEditException exception) {
            abortMutation(mutation);
            throw exception;
        } catch (RuntimeException e) {
            abortMutation(mutation);
            throw translate(e);
        }
    }

    /// Replaces the node at an address.
    ///
    /// @param address target address
    /// @param replacement detached replacement
    /// @return replacement node in the new revision
    /// @throws NBTEditException if the address or replacement is invalid
    public synchronized NBTNode replace(NBTAddress address, NBTElement replacement)
            throws NBTEditException {
        return replace(resolve(address), replacement);
    }

    /// Replaces only the content while preserving the target name and position.
    ///
    /// @param target current target
    /// @param source same-type source
    /// @return refreshed node
    /// @throws NBTEditException if types differ or the handle is stale
    public synchronized NBTNode replaceContent(NBTNode target, Tag source) throws NBTEditException {
        NBTElement current = requireNode(target);
        Tag replacement = Objects.requireNonNull(source, "source");
        if (!(current instanceof Tag currentTag) || currentTag.getType() != replacement.getType()) {
            throw error(NBTEditException.Reason.TYPE_MISMATCH, "Content replacement requires the same tag type");
        }
        return replace(target, replacement);
    }

    /// Returns the tag types to which the selected node can currently be converted.
    ///
    /// The current type is always present. The result accounts for both the conversion matrix and
    /// the selected node's parent constraint, so every different type in the result can be passed
    /// directly to [#convertType(NBTNode, TagType)].
    ///
    /// @param target current tag node
    /// @return immutable list of currently valid target types
    /// @throws NBTEditException if the handle is stale or does not identify a tag
    public synchronized @Unmodifiable List<TagType<?>> getConvertibleTypes(NBTNode target)
            throws NBTEditException {
        NBTElement element = requireNode(target);
        if (!(element instanceof Tag tag)) {
            throw error(NBTEditException.Reason.INVALID_TARGET, "Only tags have a convertible type");
        }
        if (element == root || tag.getParent() == null) {
            return List.of(tag.getType());
        }
        return NBTTagConverter.getConvertibleTypes(tag).stream()
                .filter(type -> acceptsReplacementType(tag.getParent(), type))
                .toList();
    }

    /// Converts a tag to another compatible NBT type as one transactional edit.
    ///
    /// Conversion constructs and validates a detached replacement before mutating the working tree.
    /// The original name and position are retained. Selecting the current type is a no-op and does
    /// not advance the revision or create a history entry.
    ///
    /// @param target current tag node
    /// @param targetType requested target type
    /// @return converted node in the new revision, or the current node for a no-op
    /// @throws NBTEditException if the conversion or the parent container constraint rejects the type
    public synchronized NBTNode convertType(NBTNode target, TagType<?> targetType)
            throws NBTEditException {
        NBTElement element = requireNode(target);
        TagType<?> requestedType = Objects.requireNonNull(targetType, "targetType");
        if (!(element instanceof Tag source)) {
            throw error(NBTEditException.Reason.INVALID_TARGET, "Only tags can change type");
        }
        if (source.getType() == requestedType) {
            return describe(source, target.address());
        }
        if (element == root || source.getParent() == null) {
            throw error(NBTEditException.Reason.ROOT_OPERATION,
                    "The root type cannot change because it defines the editor's root contract");
        }
        if (!acceptsReplacementType(source.getParent(), requestedType)) {
            throw error(NBTEditException.Reason.TYPE_MISMATCH,
                    "The parent container does not accept the requested tag type");
        }
        Tag replacement = NBTTagConverter.convert(source, requestedType);
        return replace(target, replacement);
    }

    /// Converts the tag at an address to another compatible NBT type.
    ///
    /// @param address target address
    /// @param targetType requested target type
    /// @return converted node in the new revision, or the current node for a no-op
    /// @throws NBTEditException if the address or conversion is invalid
    public synchronized NBTNode convertType(NBTAddress address, TagType<?> targetType)
            throws NBTEditException {
        return convertType(resolve(address), targetType);
    }

    /// Replaces a scalar value using the strict parser for its existing tag type.
    ///
    /// The input is parsed before any history entry is created, so malformed input leaves the
    /// working tree and revision unchanged.
    ///
    /// @param target current scalar node
    /// @param text textual value
    /// @return refreshed node
    /// @throws NBTEditException if the node is not scalar or the text is invalid
    public synchronized NBTNode setScalar(NBTNode target, String text) throws NBTEditException {
        NBTElement element = requireNode(target);
        String input = Objects.requireNonNull(text, "text");
        if (!(element instanceof ValueTag<?> valueTag)) {
            throw error(NBTEditException.Reason.INVALID_TARGET, "Only scalar tags have editable values");
        }
        Tag replacement;
        try {
            replacement = parseScalar(valueTag, input);
        } catch (NumberFormatException exception) {
            throw errorWithCause(NBTEditException.Reason.TYPE_MISMATCH,
                    "The value is outside the selected tag type's range", exception);
        }
        return replace(target, replacement);
    }

    /// Removes a non-root tag.
    ///
    /// @param target current node
    /// @return the parent node in the new revision
    /// @throws NBTEditException if the target is the root or stale
    public synchronized NBTNode remove(NBTNode target) throws NBTEditException {
        NBTElement element = requireNode(target);
        if (!(element instanceof Tag tag) || tag.getParent() == null) {
            throw error(NBTEditException.Reason.ROOT_OPERATION, "The root cannot be removed");
        }
        NBTParent<?> parent = tag.getParent();
        NBTAddress parentAddress = target.address().parent();
        MutationState mutation = beginMutation(parentAddress);
        try {
            removeFromParent(parent, tag);
            finishMutation(mutation);
            return resolve(parentAddress);
        } catch (NBTEditException exception) {
            abortMutation(mutation);
            throw exception;
        } catch (RuntimeException exception) {
            abortMutation(mutation);
            throw translate(exception);
        }
    }

    /// Removes the node at an address.
    ///
    /// @param address target address
    /// @return parent node in the new revision
    /// @throws NBTEditException if the address identifies the root or is stale
    public synchronized NBTNode remove(NBTAddress address) throws NBTEditException {
        return remove(resolve(address));
    }

    /// Alias for [#remove(NBTNode)].
    public synchronized NBTNode delete(NBTNode target) throws NBTEditException {
        return remove(target);
    }

    /// Moves a tag to a compatible parent and index.
    ///
    /// @param target current tag
    /// @param newParent current destination parent
    /// @param newIndex destination index
    /// @return moved node in the new revision
    /// @throws NBTEditException if the move is invalid
    public synchronized NBTNode move(NBTNode target, NBTNode newParent, int newIndex) throws NBTEditException {
        NBTElement element = requireNode(target);
        NBTElement destination = requireNode(newParent);
        if (!(element instanceof Tag tag) || !(destination instanceof NBTParent<?> parent)) {
            throw error(NBTEditException.Reason.INVALID_TARGET, "Only tags can be moved into parents");
        }
        if (element == destination || destination.getRoot() != root || isDescendant(destination, element)) {
            throw error(NBTEditException.Reason.CYCLE, "The destination would create an invalid tree");
        }
        validateChunkBoundary(tag, destination);
        validateMoveDestination(tag, parent, newIndex);

        NBTParent<?> sourceParent = tag.getParent();
        if (sourceParent == null) {
            throw error(NBTEditException.Reason.ROOT_OPERATION, "The root cannot be moved");
        }
        if (sourceParent == parent) {
            if (newIndex < 0 || newIndex >= parent.size()) {
                throw error(NBTEditException.Reason.INVALID_INDEX, "The destination index is outside the parent");
            }
            MutationState mutation = beginMutation(target.address().parent());
            try {
                moveWithinParent(sourceParent, tag, newIndex);
                finishMutation(mutation);
                return resolve(NBTAddress.from(tag));
            } catch (NBTEditException exception) {
                abortMutation(mutation);
                throw exception;
            } catch (RuntimeException exception) {
                abortMutation(mutation);
                throw translate(exception);
            }
        }

        MutationState mutation = beginMutation(target.address().parent(), newParent.address());
        try {
            removeFromParent(sourceParent, tag);
            insertIntoParent(parent, newIndex, tag);
            finishMutation(mutation);
            return resolve(NBTAddress.from(tag));
        } catch (NBTEditException exception) {
            abortMutation(mutation);
            throw exception;
        } catch (RuntimeException exception) {
            abortMutation(mutation);
            throw translate(exception);
        }
    }

    /// Sets the type of an empty list.
    ///
    /// @param list current list node
    /// @param type element type, or `null` for TAG_End
    /// @return refreshed list node
    /// @throws NBTEditException if the list is non-empty or the handle is stale
    @SuppressWarnings({"rawtypes", "unchecked"})
    public synchronized NBTNode setListElementType(NBTNode list, @Nullable TagType<?> type) throws NBTEditException {
        NBTElement element = requireNode(list);
        if (!(element instanceof ListTag<?> listTag)) {
            throw error(NBTEditException.Reason.TYPE_MISMATCH, "The node is not a list");
        }
        if (!listTag.isEmpty()) {
            throw error(NBTEditException.Reason.TYPE_MISMATCH, "Only an empty list can change element type");
        }
        MutationState mutation = beginMutation(list.address());
        try {
            ((ListTag) listTag).setElementType((TagType) type);
            finishMutation(mutation);
            return resolve(list.address());
        } catch (NBTEditException exception) {
            abortMutation(mutation);
            throw exception;
        } catch (RuntimeException exception) {
            abortMutation(mutation);
            throw translate(exception);
        }
    }

    /// Sets a chunk timestamp.
    ///
    /// @param chunk current chunk node
    /// @param timestamp new timestamp
    /// @return refreshed chunk node
    /// @throws NBTEditException if the node is not a chunk
    public synchronized NBTNode setChunkTimestamp(NBTNode chunk, Instant timestamp) throws NBTEditException {
        NBTElement element = requireNode(chunk);
        if (!(element instanceof Chunk value)) {
            throw error(NBTEditException.Reason.INVALID_TARGET, "The node is not a chunk");
        }
        MutationState mutation = beginMutation(chunk.address());
        try {
            value.setTimestamp(Objects.requireNonNull(timestamp, "timestamp"));
            finishMutation(mutation);
            return resolve(chunk.address());
        } catch (NBTEditException exception) {
            abortMutation(mutation);
            throw exception;
        } catch (RuntimeException exception) {
            abortMutation(mutation);
            throw translate(exception);
        }
    }

    /// Sets an array element using the scalar parser for that element.
    ///
    /// @param array current array node
    /// @param index element index
    /// @param text strict scalar text
    /// @return refreshed element node
    /// @throws NBTEditException if the array, index, or value is invalid
    public synchronized NBTNode setArrayElement(NBTNode array, int index, String text)
            throws NBTEditException {
        return setScalar(getChild(array, index), text);
    }

    /// Undoes the latest operation.
    ///
    /// @return current root node
    /// @throws NBTEditException if there is no undo entry
    public synchronized NBTNode undo() throws NBTEditException {
        if (undo.isEmpty()) {
            throw error(NBTEditException.Reason.NO_UNDO, "There is no edit to undo");
        }
        HistoryEntry entry = undo.peek();
        applyHistory(entry.before());
        undo.pop();
        redo.push(entry);
        stateId = entry.beforeState();
        revision++;
        return getRootNode();
    }

    /// Redoes the latest undone operation.
    ///
    /// @return current root node
    /// @throws NBTEditException if there is no redo entry
    public synchronized NBTNode redo() throws NBTEditException {
        if (redo.isEmpty()) {
            throw error(NBTEditException.Reason.NO_REDO, "There is no edit to redo");
        }
        HistoryEntry entry = redo.peek();
        applyHistory(entry.after());
        redo.pop();
        undo.push(entry);
        stateId = entry.afterState();
        revision++;
        return getRootNode();
    }

    /// Alias for [#undo()].
    public synchronized NBTNode undoEdit() throws NBTEditException {
        return undo();
    }

    /// Alias for [#redo()].
    public synchronized NBTNode redoEdit() throws NBTEditException {
        return redo();
    }

    /// Marks the current revision as saved.
    public synchronized void markSaved() {
        savedStateId = stateId;
    }

    /// Marks a savepoint as saved only when it belongs to this session and is still current.
    ///
    /// @param savepoint completed savepoint
    /// @return `true` when the editor was marked clean, `false` when a newer edit exists
    public synchronized boolean markSaved(NBTSavepoint<?> savepoint) {
        Objects.requireNonNull(savepoint, "savepoint");
        if (savepoint.session() != identity || savepoint.revision() != revision) {
            return false;
        }
        markSaved();
        return true;
    }

    /// Marks the given revision as saved if it is still the current revision.
    ///
    /// @param expectedRevision revision returned by [#getRevision()]
    /// @return `true` when the editor was marked clean
    public synchronized boolean markSaved(long expectedRevision) {
        if (expectedRevision != revision) {
            return false;
        }
        markSaved();
        return true;
    }

    private MutationState beginMutation(NBTAddress... affectedAddresses) throws NBTEditException {
        List<NBTAddress> addresses = normalizeAddresses(List.of(affectedAddresses));
        return new MutationState(addresses, capturePatches(addresses), stateId);
    }

    private void finishMutation(MutationState mutation) throws NBTEditException {
        validateCurrentTree();
        long afterState = ++stateSequence;
        HistoryEntry entry = new HistoryEntry(
                mutation.before(), capturePatches(mutation.addresses()), mutation.beforeState(), afterState);
        undo.push(entry);
        while (undo.size() > historyLimit) {
            undo.removeLast();
        }
        redo.clear();
        stateId = afterState;
        revision++;
    }

    private void abortMutation(MutationState mutation) {
        try {
            applyPatches(mutation.before());
        } catch (NBTEditException exception) {
            throw new IllegalStateException("Could not restore the NBT tree after a rejected edit", exception);
        }
    }

    private void validateCurrentTree() throws NBTEditException {
        try {
            NBTStructureValidator.validate(root);
        } catch (NBTValidationException exception) {
            throw errorWithCause(NBTEditException.Reason.INVALID_FORMAT,
                    "The edit would create an invalid NBT tree", exception);
        }
    }

    private List<Patch> capturePatches(List<NBTAddress> addresses) throws NBTEditException {
        List<Patch> patches = new ArrayList<>(addresses.size());
        for (NBTAddress address : addresses) {
            NBTElement element = find(address);
            if (element == null) {
                throw error(NBTEditException.Reason.NOT_FOUND, "No element exists at " + address);
            }
            patches.add(new Patch(address, cloneElement(element)));
        }
        return List.copyOf(patches);
    }

    private void applyHistory(List<Patch> patches) throws NBTEditException {
        applyPatches(patches);
        validateCurrentTree();
    }

    private void applyPatches(List<Patch> patches) throws NBTEditException {
        for (Patch patch : patches) {
            replaceAtAddress(patch.address(), cloneElement(patch.element()));
        }
    }

    private void replaceAtAddress(NBTAddress address, NBTElement replacement) throws NBTEditException {
        if (address.isRoot()) {
            if (!root.getClass().isInstance(replacement)) {
                throw error(NBTEditException.Reason.TYPE_MISMATCH, "The root type cannot change");
            }
            root = castRoot(replacement);
            return;
        }
        NBTElement parentElement = find(address.parent());
        if (!(parentElement instanceof NBTParent<?> parent)) {
            throw error(NBTEditException.Reason.NOT_FOUND, "The patch parent no longer exists at " + address.parent());
        }
        NBTAddress.Segment segment = address.segments().get(address.segments().size() - 1);
        try {
            if (segment instanceof NBTAddress.NameSegment name && parent instanceof CompoundTag compound
                    && replacement instanceof Tag tag) {
                Tag current = compound.get(name.name());
                if (current == null) {
                    throw error(NBTEditException.Reason.NOT_FOUND, "The patch target no longer exists at " + address);
                }
                compound.replaceTagAt(current.getIndex(), tag);
            } else if (segment instanceof NBTAddress.IndexSegment index && replacement instanceof Tag tag) {
                replaceInParent(parent, childTagAt(parent, index.index()), tag);
            } else if (segment instanceof NBTAddress.RegionChunkSegment chunkIndex
                    && parent instanceof ChunkRegion region && replacement instanceof Chunk chunk) {
                region.setChunk(chunkIndex.localIndex(), chunk);
            } else if (segment instanceof NBTAddress.ChunkRootSegment && parent instanceof Chunk chunk
                    && replacement instanceof CompoundTag compound) {
                chunk.setRootTag(compound);
            } else {
                throw error(NBTEditException.Reason.INVALID_TARGET, "The patch target is incompatible at " + address);
            }
        } catch (NBTEditException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw translate(exception);
        }
    }

    private static Tag childTagAt(NBTParent<?> parent, int index) throws NBTEditException {
        if (index < 0 || index >= parent.size()) {
            throw error(NBTEditException.Reason.INVALID_INDEX, "The patch index is outside the parent");
        }
        NBTElement child = childAt(parent, index);
        if (!(child instanceof Tag tag)) {
            throw error(NBTEditException.Reason.INVALID_TARGET, "The patch child is not a tag");
        }
        return tag;
    }

    private static List<NBTAddress> normalizeAddresses(List<NBTAddress> addresses) {
        List<NBTAddress> ordered = new ArrayList<>(addresses);
        ordered.sort(java.util.Comparator.comparingInt(address -> address.segments().size()));
        List<NBTAddress> result = new ArrayList<>(ordered.size());
        for (NBTAddress address : ordered) {
            Objects.requireNonNull(address, "address");
            boolean covered = result.stream().anyMatch(existing -> isAddressPrefix(existing, address));
            if (!covered) {
                result.add(address);
            }
        }
        return List.copyOf(result);
    }

    private static boolean isAddressPrefix(NBTAddress prefix, NBTAddress address) {
        List<NBTAddress.Segment> prefixSegments = prefix.segments();
        List<NBTAddress.Segment> segments = address.segments();
        return prefixSegments.size() <= segments.size()
                && prefixSegments.equals(segments.subList(0, prefixSegments.size()));
    }

    private NBTElement requireNode(NBTNode node) throws NBTEditException {
        Objects.requireNonNull(node, "node");
        if (node.session() != identity) {
            throw error(NBTEditException.Reason.FOREIGN_NODE, "The node belongs to another editor");
        }
        if (node.revision() != revision) {
            throw error(NBTEditException.Reason.STALE_NODE, "The node belongs to an older revision");
        }
        NBTElement result = find(node.address());
        if (result == null) {
            throw error(NBTEditException.Reason.NOT_FOUND, "The node no longer exists");
        }
        return result;
    }

    private @Nullable NBTElement find(NBTAddress address) {
        NBTElement current = root;
        for (NBTAddress.Segment segment : address.segments()) {
            if (segment instanceof NBTAddress.NameSegment name) {
                if (!(current instanceof CompoundTag compound)) {
                    return null;
                }
                current = compound.get(name.name());
            } else if (segment instanceof NBTAddress.IndexSegment index) {
                if (!(current instanceof NBTParent<?> parent) || index.index() < 0 || index.index() >= parent.size()) {
                    return null;
                }
                current = childAt(parent, index.index());
            } else if (segment instanceof NBTAddress.RegionChunkSegment chunkSegment) {
                if (!(current instanceof ChunkRegion region)) {
                    return null;
                }
                current = region.getChunk(chunkSegment.localIndex());
            } else if (segment instanceof NBTAddress.ChunkRootSegment) {
                if (!(current instanceof Chunk chunk)) {
                    return null;
                }
                current = chunk.getRootTag();
            }
            if (current == null) {
                return null;
            }
        }
        return current;
    }

    private NBTNode describe(NBTElement element, NBTAddress address) {
        String name = element instanceof Tag tag ? tag.getName() : "";
        @Nullable TagType<?> type = element instanceof Tag tag ? tag.getType() : null;
        int childCount = element instanceof NBTParent<?> parent ? parent.size() : 0;
        @Nullable String value = scalarValue(element);
        return new NBTNode(identity, revision, address, name, type, childCount, value);
    }

    private static @Nullable String scalarValue(NBTElement element) {
        if (element instanceof ValueTag<?> valueTag) {
            return valueTag.getAsString();
        }
        if (element instanceof Chunk chunk) {
            return chunk.getTimestamp().toString();
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static <E extends NBTElement> E cloneElement(NBTElement element) {
        return (E) element.clone();
    }

    @SuppressWarnings("unchecked")
    private static <E extends NBTElement> E castRoot(NBTElement element) {
        return (E) element;
    }

    private static NBTElement childAt(NBTParent<?> parent, int index) {
        if (parent instanceof ChunkRegion region) {
            return region.getChunk(index);
        }
        if (parent instanceof Chunk chunk) {
            return chunk.getRootTag();
        }
        if (parent instanceof ParentTag<?> parentTag) {
            return parentTag.childAt(index);
        }
        throw new IllegalArgumentException("Unsupported NBT parent type: " + parent.getClass().getName());
    }

    private static NBTAddress childAddress(NBTAddress parentAddress, NBTParent<?> parent,
                                           NBTElement child, int index) {
        if (parent instanceof ChunkRegion) {
            return parentAddress.appendChunk(index);
        }
        if (parent instanceof Chunk) {
            return parentAddress.appendChunkRoot();
        }
        if (parent instanceof CompoundTag && child instanceof Tag tag) {
            return parentAddress.appendName(tag.getName());
        }
        return parentAddress.appendIndex(index);
    }

    private static boolean isDescendant(NBTElement candidate, NBTElement ancestor) {
        NBTElement cursor = candidate;
        while (cursor.getParent() != null) {
            NBTParent<?> parent = cursor.getParent();
            if (parent == ancestor) {
                return true;
            }
            cursor = parent;
        }
        return false;
    }

    private static void validateChunkBoundary(Tag source, NBTElement destination)
            throws NBTEditException {
        @Nullable Chunk sourceChunk = owningChunk(source);
        @Nullable Chunk destinationChunk = owningChunk(destination);
        if (sourceChunk != destinationChunk && (sourceChunk != null || destinationChunk != null)) {
            throw error(NBTEditException.Reason.INVALID_TARGET, "Cross-chunk moves are not allowed");
        }
    }

    private static @Nullable Chunk owningChunk(NBTElement element) {
        NBTElement cursor = element;
        while (cursor != null) {
            if (cursor instanceof Chunk chunk) {
                return chunk;
            }
            cursor = cursor.getParent();
        }
        return null;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void moveWithinParent(NBTParent<?> parent, Tag tag, int newIndex) {
        if (parent instanceof ParentTag parentTag) {
            parentTag.moveTag(tag.getIndex(), newIndex);
        } else if (parent instanceof Chunk) {
            if (newIndex != 0 || tag.getIndex() != 0) {
                throw new IndexOutOfBoundsException("A chunk root can only occupy index zero");
            }
        } else {
            throw new IllegalArgumentException("Unsupported NBT parent type");
        }
    }

    private static void insertIntoList(ListTag<?> list, int index, Tag tag) {
        insertListUnchecked(list, index, tag);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void insertListUnchecked(ListTag list, int index, Tag tag) {
        list.insertTag(index, tag);
    }

    private static void insertIntoArray(ArrayTag<?, ?, ?, ?> array, int index, Tag tag) {
        insertArrayUnchecked(array, index, tag);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void insertArrayUnchecked(ArrayTag array, int index, Tag tag) {
        array.insertTag(index, tag);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void replaceInParent(NBTParent<?> parent, Tag oldTag, Tag newTag) {
        if (parent instanceof CompoundTag compound) {
            compound.replaceTagAt(oldTag.getIndex(), newTag);
        } else if (parent instanceof ListTag list) {
            list.replaceTagAt(oldTag.getIndex(), newTag);
        } else if (parent instanceof ArrayTag array) {
            array.replaceTagAt(oldTag.getIndex(), newTag);
        } else if (parent instanceof Chunk chunk) {
            chunk.setRootTag((CompoundTag) newTag);
        } else {
            throw new IllegalArgumentException("Unsupported parent type");
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void removeFromParent(NBTParent<?> parent, Tag tag) {
        if (parent instanceof CompoundTag compound) {
            compound.removeTagAt(tag.getIndex());
        } else if (parent instanceof ListTag list) {
            list.removeTagAt(tag.getIndex());
        } else if (parent instanceof ArrayTag array) {
            array.removeTagAt(tag.getIndex());
        } else if (parent instanceof Chunk chunk) {
            chunk.setRootTag(null);
        } else {
            throw new IllegalArgumentException("Unsupported parent type");
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void insertIntoParent(NBTParent<?> parent, int index, Tag tag) {
        if (parent instanceof CompoundTag compound) {
            compound.insertTag(index, tag);
        } else if (parent instanceof ListTag list) {
            list.insertTag(index, tag);
        } else if (parent instanceof ArrayTag array) {
            array.insertTag(index, tag);
        } else if (parent instanceof Chunk chunk) {
            if (index != 0 || !(tag instanceof space.minecraftstl.xyml.library.nbt.tag.CompoundTag compoundTag)) {
                throw new IllegalArgumentException("A chunk accepts only one compound root");
            }
            chunk.setRootTag(compoundTag);
        } else {
            throw new IllegalArgumentException("Unsupported NBT parent type");
        }
    }

    private static void validateMoveDestination(Tag tag, NBTParent<?> parent, int index) throws NBTEditException {
        if (parent instanceof ChunkRegion) {
            throw error(NBTEditException.Reason.INVALID_TARGET, "A chunk region does not accept moved tags");
        }
        if (parent instanceof Chunk) {
            if (!(tag instanceof CompoundTag) || index != 0) {
                throw error(NBTEditException.Reason.TYPE_MISMATCH, "A chunk root must be a compound at index zero");
            }
            if (((Chunk) parent).getRootTag() != null && ((Chunk) parent).getRootTag() != tag) {
                throw error(NBTEditException.Reason.INVALID_TARGET, "The destination chunk already has a root tag");
            }
            return;
        }
        if (index < 0 || index > parent.size()) {
            throw error(NBTEditException.Reason.INVALID_INDEX, "The destination index is outside the parent");
        }
        if (parent instanceof CompoundTag compound) {
            if (tag.getName().isEmpty() || (compound.get(tag.getName()) != null && compound.get(tag.getName()) != tag)) {
                throw error(NBTEditException.Reason.DUPLICATE_NAME, "Compound names must be unique and non-empty");
            }
        } else if (parent instanceof ListTag<?> list) {
            if (list.getElementType() != null && list.getElementType() != tag.getType()) {
                throw error(NBTEditException.Reason.TYPE_MISMATCH, "The destination list requires another tag type");
            }
        } else if (parent instanceof ArrayTag<?, ?, ?, ?> array && array.getElementType() != tag.getType()) {
            throw error(NBTEditException.Reason.TYPE_MISMATCH, "The destination array requires another tag type");
        }
    }

    /// Returns whether an existing parent can accept a replacement with the requested type.
    ///
    /// @param parent existing parent
    /// @param type replacement type
    /// @return whether replacement preserves the parent invariant
    private static boolean acceptsReplacementType(NBTParent<?> parent, TagType<?> type) {
        if (parent instanceof CompoundTag) {
            return true;
        }
        if (parent instanceof ListTag<?> list) {
            return list.getElementType() == type;
        }
        if (parent instanceof ArrayTag<?, ?, ?, ?> array) {
            return array.getElementType() == type;
        }
        return parent instanceof Chunk && type == TagType.COMPOUND;
    }

    private static Tag parseScalar(ValueTag<?> source, String text) {
        String input = text.trim();
        if (source instanceof space.minecraftstl.xyml.library.nbt.tag.ByteTag) {
            return new space.minecraftstl.xyml.library.nbt.tag.ByteTag(Byte.parseByte(input)).setName(source.getName());
        }
        if (source instanceof space.minecraftstl.xyml.library.nbt.tag.ShortTag) {
            return new space.minecraftstl.xyml.library.nbt.tag.ShortTag(Short.parseShort(input)).setName(source.getName());
        }
        if (source instanceof space.minecraftstl.xyml.library.nbt.tag.IntTag) {
            return new space.minecraftstl.xyml.library.nbt.tag.IntTag(Integer.parseInt(input)).setName(source.getName());
        }
        if (source instanceof space.minecraftstl.xyml.library.nbt.tag.LongTag) {
            return new space.minecraftstl.xyml.library.nbt.tag.LongTag(Long.parseLong(input)).setName(source.getName());
        }
        if (source instanceof space.minecraftstl.xyml.library.nbt.tag.FloatTag) {
            float value = Float.parseFloat(input);
            if (!Float.isFinite(value)) {
                throw new NumberFormatException("A float value must be finite");
            }
            return new space.minecraftstl.xyml.library.nbt.tag.FloatTag(value).setName(source.getName());
        }
        if (source instanceof space.minecraftstl.xyml.library.nbt.tag.DoubleTag) {
            double value = Double.parseDouble(input);
            if (!Double.isFinite(value)) {
                throw new NumberFormatException("A double value must be finite");
            }
            return new space.minecraftstl.xyml.library.nbt.tag.DoubleTag(value).setName(source.getName());
        }
        if (source instanceof space.minecraftstl.xyml.library.nbt.tag.StringTag) {
            return new space.minecraftstl.xyml.library.nbt.tag.StringTag(text).setName(source.getName());
        }
        throw new NumberFormatException("The selected value type is not editable");
    }

    private static NBTEditException translate(RuntimeException exception) {
        String message = exception.getMessage() == null ? "The edit was rejected" : exception.getMessage();
        String lower = message.toLowerCase(java.util.Locale.ROOT);
        NBTEditException.Reason reason;
        if (exception instanceof IndexOutOfBoundsException) {
            reason = NBTEditException.Reason.INVALID_INDEX;
        } else if (lower.contains("type")) {
            reason = NBTEditException.Reason.TYPE_MISMATCH;
        } else if (lower.contains("name")) {
            reason = lower.contains("already")
                    ? NBTEditException.Reason.DUPLICATE_NAME
                    : NBTEditException.Reason.INVALID_NAME;
        } else if (lower.contains("cycle") || lower.contains("ancestor") || lower.contains("itself")) {
            reason = NBTEditException.Reason.CYCLE;
        } else {
            reason = NBTEditException.Reason.INVALID_TARGET;
        }
        return new NBTEditException(reason, message, exception);
    }

    private static NBTEditException error(NBTEditException.Reason reason, String message) {
        return new NBTEditException(reason, message);
    }

    private static NBTEditException errorWithCause(
            NBTEditException.Reason reason,
            String message,
            Throwable cause) {
        return new NBTEditException(reason, message, cause);
    }

    private record Patch(NBTAddress address, NBTElement element) {
        private Patch {
            Objects.requireNonNull(address, "address");
            Objects.requireNonNull(element, "element");
        }
    }

    private record MutationState(List<NBTAddress> addresses, List<Patch> before, long beforeState) {
        private MutationState {
            addresses = List.copyOf(addresses);
            before = List.copyOf(before);
        }
    }

    private record HistoryEntry(List<Patch> before, List<Patch> after, long beforeState, long afterState) {
        private HistoryEntry {
            before = List.copyOf(before);
            after = List.copyOf(after);
        }
    }
}
