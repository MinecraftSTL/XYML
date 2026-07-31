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

import java.util.AbstractSet;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

/// LinkedHashSet-backed implementation of [ObservableSet] with stable iteration and batch events.
///
/// Input collections are validated before mutation, and event payloads contain only elements whose membership
/// actually changed.
@NotNullByDefault
public final class ObservableLinkedHashSet<E> extends AbstractSet<E> implements ObservableSet<E> {
    /// Mutable set storage owned exclusively by this instance.
    private final LinkedHashSet<E> elements;

    /// Synchronous listener registrations.
    private final CollectionChangeSupport<SetChange<E>> changeSupport = new CollectionChangeSupport<>();

    /// Creates an empty observable insertion-ordered set.
    public ObservableLinkedHashSet() {
        this.elements = new LinkedHashSet<>();
    }

    /// Creates an observable set by copying elements in encounter order.
    public ObservableLinkedHashSet(Collection<? extends E> elements) {
        this.elements = new LinkedHashSet<>(validatedCopy(elements));
    }

    /// Registers a synchronous set-change listener.
    @Override
    public Subscription subscribe(CollectionChangeListener<SetChange<E>> listener) {
        return changeSupport.subscribe(listener);
    }

    /// Returns an iterator whose removals emit one-element removal events.
    @Override
    public Iterator<E> iterator() {
        return new ObservableIterator(elements.iterator());
    }

    /// Returns the number of elements.
    @Override
    public int size() {
        return elements.size();
    }

    /// Adds an absent non-null element and emits one addition event.
    @Override
    public boolean add(E element) {
        Objects.requireNonNull(element, "element");
        if (!elements.add(element)) {
            return false;
        }
        fireAdded(Set.of(element));
        return true;
    }

    /// Adds all absent elements and emits at most one ordered batch event.
    @Override
    public boolean addAll(Collection<? extends E> addedElements) {
        @Unmodifiable Set<E> candidates = validatedCopy(addedElements);
        LinkedHashSet<E> added = new LinkedHashSet<>();
        for (E candidate : candidates) {
            if (!elements.contains(candidate)) {
                added.add(candidate);
            }
        }
        if (added.isEmpty()) {
            return false;
        }

        elements.addAll(added);
        fireAdded(added);
        return true;
    }

    /// Removes an equal element when present and emits its stored representation.
    @Override
    public boolean remove(@Nullable Object candidate) {
        @Nullable E existing = find(candidate);
        if (existing == null) {
            return false;
        }

        elements.remove(existing);
        fireRemoved(Set.of(existing), SetChange.Kind.REMOVE);
        return true;
    }

    /// Removes every element equal to a candidate with one ordered batch event.
    @Override
    public boolean removeAll(Collection<? extends @Nullable Object> candidates) {
        Objects.requireNonNull(candidates, "candidates");
        LinkedHashSet<E> removed = new LinkedHashSet<>();
        for (E element : elements) {
            if (candidates.contains(element)) {
                removed.add(element);
            }
        }
        return removeCollected(removed, SetChange.Kind.REMOVE);
    }

    /// Retains only elements equal to at least one candidate with one ordered batch event.
    @Override
    public boolean retainAll(Collection<? extends @Nullable Object> candidates) {
        Objects.requireNonNull(candidates, "candidates");
        LinkedHashSet<E> removed = new LinkedHashSet<>();
        for (E element : elements) {
            if (!candidates.contains(element)) {
                removed.add(element);
            }
        }
        return removeCollected(removed, SetChange.Kind.REMOVE);
    }

    /// Removes every predicate match with one ordered batch event.
    @Override
    public boolean removeIf(Predicate<? super E> predicate) {
        Objects.requireNonNull(predicate, "predicate");
        LinkedHashSet<E> removed = new LinkedHashSet<>();
        for (E element : elements) {
            if (predicate.test(element)) {
                removed.add(element);
            }
        }
        return removeCollected(removed, SetChange.Kind.REMOVE);
    }

    /// Clears all contents with one event, or does nothing when already empty.
    @Override
    public void clear() {
        if (elements.isEmpty()) {
            return;
        }

        LinkedHashSet<E> removed = new LinkedHashSet<>(elements);
        elements.clear();
        fireRemoved(removed, SetChange.Kind.CLEAR);
    }

    /// Finds the stored element equal to a candidate, or null when absent.
    private @Nullable E find(@Nullable Object candidate) {
        if (candidate == null) {
            return null;
        }
        for (E element : elements) {
            if (Objects.equals(element, candidate)) {
                return element;
            }
        }
        return null;
    }

    /// Removes a precomputed ordered set and emits at most one event.
    private boolean removeCollected(Set<E> removed, SetChange.Kind kind) {
        if (removed.isEmpty()) {
            return false;
        }
        elements.removeAll(removed);
        fireRemoved(removed, kind);
        return true;
    }

    /// Publishes one addition event.
    private void fireAdded(Set<? extends E> added) {
        changeSupport.fire(new SetChange<>(this, SetChange.Kind.ADD, Set.of(), new LinkedHashSet<>(added)));
    }

    /// Publishes one removal or clear event.
    private void fireRemoved(Set<? extends E> removed, SetChange.Kind kind) {
        changeSupport.fire(new SetChange<>(this, kind, new LinkedHashSet<>(removed), Set.of()));
    }

    /// Copies and validates elements while retaining encounter order.
    private static <E> @Unmodifiable Set<E> validatedCopy(Collection<? extends E> source) {
        Objects.requireNonNull(source, "source");
        LinkedHashSet<E> copy = new LinkedHashSet<>();
        for (E element : source) {
            copy.add(Objects.requireNonNull(element, "source element"));
        }
        return Collections.unmodifiableSet(copy);
    }

    /// Iterator adapter that reports successful removals through the owning set.
    @NotNullByDefault
    private final class ObservableIterator implements Iterator<E> {
        /// Iterator over the private backing set.
        private final Iterator<E> delegate;

        /// Most recently returned element, or null before iteration and after removal.
        private @Nullable E current;

        /// Whether the current element may be removed.
        private boolean canRemove;

        /// Creates a reporting iterator around the backing iterator.
        private ObservableIterator(Iterator<E> delegate) {
            this.delegate = Objects.requireNonNull(delegate, "delegate");
        }

        /// Returns whether another element is available.
        @Override
        public boolean hasNext() {
            return delegate.hasNext();
        }

        /// Returns the next element and makes it eligible for removal.
        @Override
        public E next() {
            E next = delegate.next();
            current = next;
            canRemove = true;
            return next;
        }

        /// Removes the current element and emits one removal event.
        @Override
        public void remove() {
            @Nullable E removed = current;
            if (!canRemove || removed == null) {
                throw new IllegalStateException("next() must be called once before remove()");
            }
            delegate.remove();
            current = null;
            canRemove = false;
            fireRemoved(Set.of(removed), SetChange.Kind.REMOVE);
        }
    }
}
