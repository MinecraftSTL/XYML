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
import space.minecraftstl.xyml.observable.property.ObservableValue;

import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

/// ArrayList-backed implementation of [ObservableList] with one event per public batch mutation.
///
/// Input collections are copied and null elements are rejected before state changes, so failed validation cannot
/// leave a partial mutation. An optional extractor monitors dependencies of each stored element occurrence and
/// emits [ListChange.Kind#UPDATE] without structurally changing the list. Listener failures are propagated after the
/// completed mutation. The list is not thread-safe and must be confined or externally synchronized.
@NotNullByDefault
public final class ObservableArrayList<E> extends AbstractList<E> implements ObservableList<E> {
    /// Mutable list storage owned exclusively by this instance.
    private final ArrayList<E> elements;

    /// Element observers kept index-aligned with [#elements].
    private final ArrayList<ElementObserver> elementObservers;

    /// Selects the observable dependencies for each element occurrence.
    private final ObservableElementExtractor<? super E> extractor;

    /// Synchronous listener registrations.
    private final CollectionChangeSupport<ListChange<E>> changeSupport = new CollectionChangeSupport<>();

    /// Creates an empty observable list without element dependency tracking.
    public ObservableArrayList() {
        this(List.of(), noExtractor());
    }

    /// Creates an observable list by copying elements without element dependency tracking.
    public ObservableArrayList(Collection<? extends E> elements) {
        this(elements, noExtractor());
    }

    /// Creates an empty observable list that tracks dependencies selected by the extractor.
    public ObservableArrayList(ObservableElementExtractor<? super E> extractor) {
        this(List.of(), extractor);
    }

    /// Creates an observable list by copying elements and tracking dependencies selected by the extractor.
    public ObservableArrayList(
            Collection<? extends E> elements,
            ObservableElementExtractor<? super E> extractor) {
        @Unmodifiable List<E> initialElements = validatedCopy(elements);
        this.extractor = Objects.requireNonNull(extractor, "extractor");
        this.elements = new ArrayList<>(initialElements);
        this.elementObservers = createObservers(initialElements);
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

        ElementObserver replacementObserver = new ElementObserver(element);
        ElementObserver previousObserver = elementObservers.set(index, replacementObserver);
        elements.set(index, element);
        previousObserver.unsubscribe();
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
        checkPositionIndex(index);
        Objects.requireNonNull(element, "element");
        ElementObserver observer = new ElementObserver(element);
        elements.add(index, element);
        elementObservers.add(index, observer);
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

        ArrayList<ElementObserver> addedObservers = createObservers(added);
        elements.addAll(index, added);
        elementObservers.addAll(index, addedObservers);
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
        ElementObserver removedObserver = elementObservers.remove(index);
        modCount++;
        removedObserver.unsubscribe();
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
        ArrayList<ElementObserver> removedObservers = new ArrayList<>(
                elementObservers.subList(fromIndex, toIndex));
        elements.subList(fromIndex, toIndex).clear();
        elementObservers.subList(fromIndex, toIndex).clear();
        modCount++;
        unsubscribeAll(removedObservers);
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
        ArrayList<ElementObserver> previousObservers = new ArrayList<>(elementObservers);
        elements.clear();
        elementObservers.clear();
        modCount++;
        unsubscribeAll(previousObservers);
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

    /// Replaces the backing contents and dependency subscriptions when logical contents differ.
    private boolean resetTo(Collection<? extends E> replacements) {
        @Unmodifiable List<E> replacement = validatedCopy(replacements);
        @Unmodifiable List<E> previous = List.copyOf(elements);
        if (previous.equals(replacement)) {
            return false;
        }

        ArrayList<ElementObserver> replacementObservers = createObservers(replacement);
        ArrayList<ElementObserver> previousObservers = new ArrayList<>(elementObservers);
        elements.clear();
        elements.addAll(replacement);
        elementObservers.clear();
        elementObservers.addAll(replacementObservers);
        modCount++;
        unsubscribeAll(previousObservers);
        changeSupport.fire(new ListChange<>(
                this,
                ListChange.Kind.RESET,
                0,
                Math.max(previous.size(), replacement.size()),
                previous,
                replacement));
        return true;
    }

    /// Creates dependency observers for a validated element sequence, cleaning up after partial failure.
    private ArrayList<ElementObserver> createObservers(Collection<? extends E> observedElements) {
        ArrayList<ElementObserver> observers = new ArrayList<>(observedElements.size());
        try {
            for (E element : observedElements) {
                observers.add(new ElementObserver(element));
            }
            return observers;
        } catch (RuntimeException | Error exception) {
            try {
                unsubscribeAll(observers);
            } catch (RuntimeException | Error cleanupException) {
                exception.addSuppressed(cleanupException);
            }
            throw exception;
        }
    }

    /// Publishes an update for the occurrence still owned by the supplied observer.
    private void elementUpdated(ElementObserver observer) {
        int index = elementObservers.indexOf(observer);
        if (index < 0) {
            return;
        }

        E element = observer.element;
        changeSupport.fire(new ListChange<>(
                this,
                ListChange.Kind.UPDATE,
                index,
                index + 1,
                List.of(element),
                List.of(element)));
    }

    /// Cancels all supplied element observers, attempting every cancellation before propagating a failure.
    private void unsubscribeAll(Collection<? extends ElementObserver> observers) {
        @Nullable Throwable failure = null;
        for (ElementObserver observer : observers) {
            try {
                observer.unsubscribe();
            } catch (RuntimeException | Error exception) {
                if (failure == null) {
                    failure = exception;
                } else {
                    failure.addSuppressed(exception);
                }
            }
        }
        if (failure instanceof RuntimeException runtimeException) {
            throw runtimeException;
        }
        if (failure instanceof Error error) {
            throw error;
        }
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

    /// Returns an extractor that selects no element dependencies.
    private static <E> ObservableElementExtractor<E> noExtractor() {
        return element -> List.of();
    }

    /// Validates an insertion position in the inclusive range from zero through the current size.
    private void checkPositionIndex(int index) {
        if (index < 0 || index > size()) {
            throw new IndexOutOfBoundsException("Index " + index + " outside [0, " + size() + "]");
        }
    }

    /// Owns the dependency subscriptions associated with one stored element occurrence.
    @NotNullByDefault
    private final class ElementObserver {
        /// Element occurrence monitored by this observer.
        private final E element;

        /// Independently cancellable dependency subscriptions.
        private final @Unmodifiable List<Subscription> subscriptions;

        /// Extracts, validates, de-duplicates, and subscribes to this occurrence's dependencies.
        private ElementObserver(E element) {
            this.element = element;
            List<? extends ObservableValue<?>> extracted = Objects.requireNonNull(
                    extractor.extract(element),
                    "extractor result");
            IdentityHashMap<ObservableValue<?>, Boolean> uniqueSources = new IdentityHashMap<>();
            ArrayList<Subscription> createdSubscriptions = new ArrayList<>(extracted.size());
            try {
                for (ObservableValue<?> source : extracted) {
                    Objects.requireNonNull(source, "extracted observable");
                    if (uniqueSources.put(source, Boolean.TRUE) == null) {
                        createdSubscriptions.add(source.subscribe(change -> elementUpdated(this)));
                    }
                }
            } catch (RuntimeException | Error exception) {
                for (Subscription subscription : createdSubscriptions) {
                    try {
                        subscription.unsubscribe();
                    } catch (RuntimeException | Error cleanupException) {
                        exception.addSuppressed(cleanupException);
                    }
                }
                throw exception;
            }
            subscriptions = List.copyOf(createdSubscriptions);
        }

        /// Cancels every dependency subscription, attempting all cancellations before propagating a failure.
        private void unsubscribe() {
            @Nullable Throwable failure = null;
            for (Subscription subscription : subscriptions) {
                try {
                    subscription.unsubscribe();
                } catch (RuntimeException | Error exception) {
                    if (failure == null) {
                        failure = exception;
                    } else {
                        failure.addSuppressed(exception);
                    }
                }
            }
            if (failure instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (failure instanceof Error error) {
                throw error;
            }
        }
    }
}
