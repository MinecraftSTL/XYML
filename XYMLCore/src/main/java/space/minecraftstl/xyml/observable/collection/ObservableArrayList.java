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
package space.minecraftstl.xyml.observable.collection;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.observable.Subscription;

import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

/// ArrayList-backed implementation of [ObservableList] with one event per public batch mutation.
///
/// Input collections are copied and null elements are rejected before state changes, so a failed validation cannot
/// leave a partial mutation. Listener failures are propagated after the completed mutation.
@NotNullByDefault
public final class ObservableArrayList<E> extends AbstractList<E> implements ObservableList<E> {
    /// Mutable list storage owned exclusively by this instance.
    private final ArrayList<E> elements;

    /// Synchronous listener registrations.
    private final CollectionChangeSupport<ListChange<E>> changeSupport = new CollectionChangeSupport<>();

    /// Creates an empty observable list.
    public ObservableArrayList() {
        this.elements = new ArrayList<>();
    }

    /// Creates an observable list by copying the supplied elements in encounter order.
    public ObservableArrayList(Collection<? extends E> elements) {
        this.elements = new ArrayList<>(validatedCopy(elements));
    }

    /// Registers a synchronous list-change listener.
    @Override
    public Subscription subscribe(CollectionChangeListener<ListChange<E>> listener) {
        return changeSupport.subscribe(listener);
    }

    /// Returns the element at the requested index.
    @Override
    public E get(int index) {
        return elements.get(index);
    }

    /// Returns the number of elements.
    @Override
    public int size() {
        return elements.size();
    }

    /// Replaces one element and publishes a contiguous replacement unless the values are equal.
    @Override
    public E set(int index, E element) {
        Objects.requireNonNull(element, "element");
        E previous = elements.get(index);
        if (Objects.equals(previous, element)) {
            return previous;
        }

        elements.set(index, element);
        changeSupport.fire(new ListChange<>(
                this,
                ListChange.Kind.REPLACE,
                index,
                index + 1,
                List.of(previous),
                List.of(element)));
        return previous;
    }

    /// Inserts one element at the requested position.
    @Override
    public void add(int index, E element) {
        Objects.requireNonNull(element, "element");
        elements.add(index, element);
        modCount++;
        changeSupport.fire(new ListChange<>(
                this,
                ListChange.Kind.ADD,
                index,
                index + 1,
                List.of(),
                List.of(element)));
    }

    /// Appends one element.
    @Override
    public boolean add(E element) {
        add(size(), element);
        return true;
    }

    /// Inserts a copied contiguous batch and publishes one addition event.
    @Override
    public boolean addAll(int index, Collection<? extends E> addedElements) {
        checkPositionIndex(index);
        @Unmodifiable List<E> added = validatedCopy(addedElements);
        if (added.isEmpty()) {
            return false;
        }

        elements.addAll(index, added);
        modCount++;
        changeSupport.fire(new ListChange<>(
                this,
                ListChange.Kind.ADD,
                index,
                index + added.size(),
                List.of(),
                added));
        return true;
    }

    /// Appends a copied batch and publishes one addition event.
    @Override
    public boolean addAll(Collection<? extends E> addedElements) {
        return addAll(size(), addedElements);
    }

    /// Removes and returns one indexed element.
    @Override
    public E remove(int index) {
        E previous = elements.remove(index);
        modCount++;
        changeSupport.fire(new ListChange<>(
                this,
                ListChange.Kind.REMOVE,
                index,
                index + 1,
                List.of(previous),
                List.of()));
        return previous;
    }

    /// Removes the first equal candidate when present.
    @Override
    public boolean remove(@Nullable Object candidate) {
        int index = elements.indexOf(candidate);
        if (index < 0) {
            return false;
        }
        remove(index);
        return true;
    }

    /// Removes a contiguous half-open range and publishes one removal event.
    @Override
    public void removeRange(int fromIndex, int toIndex) {
        Objects.checkFromToIndex(fromIndex, toIndex, size());
        if (fromIndex == toIndex) {
            return;
        }

        @Unmodifiable List<E> removed = List.copyOf(elements.subList(fromIndex, toIndex));
        elements.subList(fromIndex, toIndex).clear();
        modCount++;
        changeSupport.fire(new ListChange<>(
                this,
                ListChange.Kind.REMOVE,
                fromIndex,
                toIndex,
                removed,
                List.of()));
    }

    /// Clears all contents with one event, or does nothing when already empty.
    @Override
    public void clear() {
        if (elements.isEmpty()) {
            return;
        }

        @Unmodifiable List<E> previous = List.copyOf(elements);
        elements.clear();
        modCount++;
        changeSupport.fire(new ListChange<>(
                this,
                ListChange.Kind.CLEAR,
                0,
                previous.size(),
                previous,
                List.of()));
    }

    /// Replaces all contents from a defensive copy and emits one complete reset event.
    @Override
    public boolean setAll(Collection<? extends E> replacementElements) {
        return resetTo(validatedCopy(replacementElements));
    }

    /// Removes every element equal to a candidate with one complete reset event.
    @Override
    public boolean removeAll(Collection<? extends @Nullable Object> candidates) {
        Objects.requireNonNull(candidates, "candidates");
        ArrayList<E> retained = new ArrayList<>(size());
        for (E element : elements) {
            if (!candidates.contains(element)) {
                retained.add(element);
            }
        }
        return resetTo(retained);
    }

    /// Retains only elements equal to at least one candidate with one complete reset event.
    @Override
    public boolean retainAll(Collection<? extends @Nullable Object> candidates) {
        Objects.requireNonNull(candidates, "candidates");
        ArrayList<E> retained = new ArrayList<>(size());
        for (E element : elements) {
            if (candidates.contains(element)) {
                retained.add(element);
            }
        }
        return resetTo(retained);
    }

    /// Removes all predicate matches with one complete reset event.
    @Override
    public boolean removeIf(Predicate<? super E> predicate) {
        Objects.requireNonNull(predicate, "predicate");
        ArrayList<E> retained = new ArrayList<>(size());
        for (E element : elements) {
            if (!predicate.test(element)) {
                retained.add(element);
            }
        }
        return resetTo(retained);
    }

    /// Applies a non-null replacement to every item and emits at most one complete reset event.
    @Override
    public void replaceAll(UnaryOperator<E> operator) {
        Objects.requireNonNull(operator, "operator");
        ArrayList<E> replacements = new ArrayList<>(size());
        for (E element : elements) {
            replacements.add(Objects.requireNonNull(operator.apply(element), "operator result"));
        }
        resetTo(replacements);
    }

    /// Sorts a copy using the supplied comparator and emits at most one complete reset event.
    @Override
    public void sort(@Nullable Comparator<? super E> comparator) {
        ArrayList<E> sorted = new ArrayList<>(elements);
        sorted.sort(comparator);
        resetTo(sorted);
    }

    /// Replaces the backing contents and publishes a reset only when logical contents differ.
    private boolean resetTo(Collection<? extends E> replacements) {
        @Unmodifiable List<E> replacement = validatedCopy(replacements);
        @Unmodifiable List<E> previous = List.copyOf(elements);
        if (previous.equals(replacement)) {
            return false;
        }

        elements.clear();
        elements.addAll(replacement);
        modCount++;
        changeSupport.fire(new ListChange<>(
                this,
                ListChange.Kind.RESET,
                0,
                Math.max(previous.size(), replacement.size()),
                previous,
                replacement));
        return true;
    }

    /// Copies and validates input elements before a mutation begins.
    private static <E> @Unmodifiable List<E> validatedCopy(Collection<? extends E> source) {
        Objects.requireNonNull(source, "source");
        ArrayList<E> copy = new ArrayList<>(source.size());
        for (E element : source) {
            copy.add(Objects.requireNonNull(element, "source element"));
        }
        return List.copyOf(copy);
    }

    /// Validates an insertion position in the inclusive range from zero through the current size.
    private void checkPositionIndex(int index) {
        if (index < 0 || index > size()) {
            throw new IndexOutOfBoundsException("Index " + index + " outside [0, " + size() + "]");
        }
    }
}
