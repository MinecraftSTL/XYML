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
import space.minecraftstl.xyml.observable.Subscription;

import java.util.AbstractList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Objects;
import java.util.RandomAccess;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

/// Read-only live view of an observable list that preserves notifications from its mutable delegate.
///
/// Change snapshots are re-sourced to this view so consumers never need access to the mutable delegate. Every
/// mutation entry point throws [UnsupportedOperationException], including operations that would otherwise be empty.
@NotNullByDefault
final class UnmodifiableObservableList<E> extends AbstractList<E>
        implements ObservableList<E>, RandomAccess {
    /// Mutable observable list observed through this view.
    private final ObservableList<E> delegate;

    /// Creates a live read-only view of the supplied observable list.
    UnmodifiableObservableList(ObservableList<E> delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    /// Returns the delegate element at the requested index.
    @Override
    public E get(int index) {
        return delegate.get(index);
    }

    /// Returns the current delegate size.
    @Override
    public int size() {
        return delegate.size();
    }

    /// Registers a listener that receives delegate changes re-sourced to this view.
    @Override
    public Subscription subscribe(CollectionChangeListener<ListChange<E>> listener) {
        Objects.requireNonNull(listener, "listener");
        return delegate.subscribe(change -> listener.onChange(new ListChange<>(
                this,
                change.kind(),
                change.fromIndex(),
                change.toIndex(),
                change.previousItems(),
                change.currentItems())));
    }

    /// Rejects replacement through the read-only view.
    @Override
    public E set(int index, E element) {
        throw readOnly();
    }

    /// Rejects indexed insertion through the read-only view.
    @Override
    public void add(int index, E element) {
        throw readOnly();
    }

    /// Rejects append through the read-only view.
    @Override
    public boolean add(E element) {
        throw readOnly();
    }

    /// Rejects indexed batch insertion through the read-only view.
    @Override
    public boolean addAll(int index, Collection<? extends E> elements) {
        throw readOnly();
    }

    /// Rejects batch append through the read-only view.
    @Override
    public boolean addAll(Collection<? extends E> elements) {
        throw readOnly();
    }

    /// Rejects indexed removal through the read-only view.
    @Override
    public E remove(int index) {
        throw readOnly();
    }

    /// Rejects value removal through the read-only view.
    @Override
    public boolean remove(@Nullable Object candidate) {
        throw readOnly();
    }

    /// Rejects clearing through the read-only view.
    @Override
    public void clear() {
        throw readOnly();
    }

    /// Rejects complete replacement through the read-only view.
    @Override
    public boolean setAll(Collection<? extends E> elements) {
        throw readOnly();
    }

    /// Rejects range removal through the read-only view.
    @Override
    public void removeRange(int fromIndex, int toIndex) {
        throw readOnly();
    }

    /// Rejects candidate batch removal through the read-only view.
    @Override
    public boolean removeAll(Collection<? extends @Nullable Object> candidates) {
        throw readOnly();
    }

    /// Rejects candidate retention through the read-only view.
    @Override
    public boolean retainAll(Collection<? extends @Nullable Object> candidates) {
        throw readOnly();
    }

    /// Rejects predicate removal through the read-only view.
    @Override
    public boolean removeIf(Predicate<? super E> predicate) {
        throw readOnly();
    }

    /// Rejects element transformation through the read-only view.
    @Override
    public void replaceAll(UnaryOperator<E> operator) {
        throw readOnly();
    }

    /// Rejects sorting through the read-only view.
    @Override
    public void sort(@Nullable Comparator<? super E> comparator) {
        throw readOnly();
    }

    /// Creates the standard exception for every prohibited mutation.
    private UnsupportedOperationException readOnly() {
        return new UnsupportedOperationException("Observable list view is read-only");
    }
}
