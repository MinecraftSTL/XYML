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
package space.minecraftstl.xyml.observable.property;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import space.minecraftstl.xyml.observable.Subscription;
import space.minecraftstl.xyml.observable.ValueChangeListener;
import space.minecraftstl.xyml.observable.ValueChangeSupport;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;

/// Provides the shared, thread-safe value and binding behavior for toolkit-neutral properties.
///
/// Changes are delivered synchronously on the mutating thread after the state lock is released, so listeners may
/// safely read or modify properties without cross-property lock inversion. Concurrent writes are atomic, although
/// their synchronous listener calls may interleave; each event still describes the exact committed transition.
/// Bindings retain their source until they are explicitly removed.
@NotNullByDefault
abstract class AbstractProperty<T> implements Property<T> {
    /// Coordinates creation and removal of binding pairs across two property instances.
    private static final Object BIDIRECTIONAL_BINDING_LOCK = new Object();

    /// Serializes this property's value, one-way binding state, and ordered event delivery.
    private final Object stateLock = new Object();

    /// The owning model object, or null when this property has no owner.
    private final @Nullable Object bean;

    /// The stable property name.
    private final String name;

    /// Publishes value transitions to subscribers.
    private final ValueChangeSupport<T> changeSupport = new ValueChangeSupport<>(this);

    /// Tracks bidirectional bindings by property identity rather than potentially mutable equality.
    private final Map<Property<T>, BidirectionalBinding<T>> bidirectionalBindings = new IdentityHashMap<>();

    /// The current normalized property value.
    private @Nullable T value;

    /// The source of the current one-way binding, or null when unbound.
    private @Nullable ObservableValue<T> boundSource;

    /// The subscription to the current one-way source, or null while unbound or during installation.
    private @Nullable Subscription boundSubscription;

    /// Increments before each active-source callback so initial synchronization cannot overwrite a newer update.
    private long boundRevision;

    /// Creates a property with metadata and its initial value.
    protected AbstractProperty(@Nullable Object bean, String name, @Nullable T initialValue) {
        this.bean = bean;
        this.name = Objects.requireNonNull(name, "name");
        this.value = normalize(initialValue);
    }

    /// Returns the owning model object, or null when this property has no owner.
    @Override
    public final @Nullable Object getBean() {
        return bean;
    }

    /// Returns the stable property name.
    @Override
    public final String getName() {
        return name;
    }

    /// Returns the current value under the property's state lock.
    @Override
    public @Nullable T getValue() {
        synchronized (stateLock) {
            return value;
        }
    }

    /// Registers a synchronous listener for distinct value transitions.
    @Override
    public final Subscription subscribe(ValueChangeListener<T> listener) {
        return changeSupport.subscribe(listener);
    }

    /// Replaces the current value when this property is not one-way bound.
    @Override
    public final void setValue(@Nullable T value) {
        @Nullable PendingChange<T> change;
        synchronized (stateLock) {
            if (boundSource != null) {
                throw new IllegalStateException("A bound property cannot be set directly");
            }
            change = prepareChangeLocked(value);
        }
        if (change != null) {
            change.publish(this, changeSupport);
        }
    }

    /// Binds this property to the source and synchronizes its value immediately.
    @Override
    public final void bind(ObservableValue<T> source) {
        Objects.requireNonNull(source, "source");
        if (source == this) {
            throw new IllegalArgumentException("A property cannot bind to itself");
        }

        @Nullable Subscription previousSubscription;
        synchronized (BIDIRECTIONAL_BINDING_LOCK) {
            if (!bidirectionalBindings.isEmpty()) {
                throw new IllegalStateException("Remove bidirectional bindings before creating a one-way binding");
            }
            synchronized (stateLock) {
                if (boundSource == source) {
                    return;
                }
                previousSubscription = boundSubscription;
                boundSource = source;
                boundSubscription = null;
                boundRevision = 0L;
            }
        }
        if (previousSubscription != null) {
            previousSubscription.unsubscribe();
        }

        Subscription subscription = source.subscribe(change -> updateBoundValueAfterChange(source));
        boolean retainSubscription;
        synchronized (stateLock) {
            retainSubscription = boundSource == source;
            if (retainSubscription) {
                boundSubscription = subscription;
            }
        }
        if (!retainSubscription) {
            subscription.unsubscribe();
            return;
        }

        synchronizeBoundValue(source);
    }

    /// Removes the active one-way binding and retains the latest propagated value.
    @Override
    public final void unbind() {
        @Nullable Subscription subscription;
        synchronized (stateLock) {
            boundSource = null;
            subscription = boundSubscription;
            boundSubscription = null;
        }
        if (subscription != null) {
            subscription.unsubscribe();
        }
    }

    /// Returns whether this property currently follows a one-way source.
    @Override
    public final boolean isBound() {
        synchronized (stateLock) {
            return boundSource != null;
        }
    }

    /// Creates one recursion-safe bidirectional connection and adopts the other property's value.
    @Override
    public final void bindBidirectional(Property<T> other) {
        Objects.requireNonNull(other, "other");
        if (other == this) {
            return;
        }

        BidirectionalBinding<T> binding;
        synchronized (BIDIRECTIONAL_BINDING_LOCK) {
            if (isBound() || other.isBound()) {
                throw new IllegalStateException("One-way-bound properties cannot be bound bidirectionally");
            }
            if (bidirectionalBindings.containsKey(other)) {
                return;
            }

            binding = new BidirectionalBinding<>(this, other);
            bidirectionalBindings.put(other, binding);
            @Nullable AbstractProperty<T> otherProperty = asAbstractProperty(other);
            if (otherProperty != null) {
                otherProperty.bidirectionalBindings.put(this, binding);
            }
        }
        try {
            binding.synchronizeFromSecond();
        } catch (RuntimeException | Error exception) {
            synchronized (BIDIRECTIONAL_BINDING_LOCK) {
                bidirectionalBindings.remove(other);
                @Nullable AbstractProperty<T> otherProperty = asAbstractProperty(other);
                if (otherProperty != null) {
                    otherProperty.bidirectionalBindings.remove(this);
                }
            }
            binding.close();
            throw exception;
        }
    }

    /// Removes and closes the bidirectional connection to the other property when present.
    @Override
    public final void unbindBidirectional(Property<T> other) {
        Objects.requireNonNull(other, "other");
        @Nullable BidirectionalBinding<T> binding;
        synchronized (BIDIRECTIONAL_BINDING_LOCK) {
            binding = bidirectionalBindings.remove(other);
            @Nullable AbstractProperty<T> otherProperty = asAbstractProperty(other);
            if (binding != null && otherProperty != null) {
                otherProperty.bidirectionalBindings.remove(this);
            }
            if (binding != null) {
                binding.close();
            }
        }
    }

    /// Normalizes an incoming value before comparison and storage.
    protected @Nullable T normalize(@Nullable T candidate) {
        return candidate;
    }

    /// Runs after a distinct normalized value has been committed and before subscribers receive its change.
    ///
    /// The state lock is not held while this hook runs. Subclasses may use it to invalidate value-dependent cached
    /// state, but should not assume that later concurrent transitions are blocked until the hook returns.
    protected void valueChanged(@Nullable T previousValue, @Nullable T currentValue) {
    }

    /// Records an active-source callback before synchronizing so a racing initial read is retried.
    private void updateBoundValueAfterChange(ObservableValue<T> source) {
        synchronized (stateLock) {
            if (boundSource != source) {
                return;
            }
            boundRevision++;
        }
        synchronizeBoundValue(source);
    }

    /// Copies the active source value and retries when another source callback raced with the copy.
    private void synchronizeBoundValue(ObservableValue<T> source) {
        while (true) {
            long observedRevision;
            synchronized (stateLock) {
                if (boundSource != source) {
                    return;
                }
                observedRevision = boundRevision;
            }

            @Nullable T currentSourceValue = source.getValue();
            @Nullable PendingChange<T> change;
            synchronized (stateLock) {
                if (boundSource != source) {
                    return;
                }
                change = prepareChangeLocked(currentSourceValue);
            }
            if (change != null) {
                change.publish(this, changeSupport);
            }

            synchronized (stateLock) {
                if (boundSource != source || observedRevision == boundRevision) {
                    return;
                }
            }
        }
    }

    /// Commits a distinct normalized value while the caller owns [#stateLock].
    private @Nullable PendingChange<T> prepareChangeLocked(@Nullable T candidate) {
        @Nullable T normalizedValue = normalize(candidate);
        @Nullable T previousValue = value;
        if (Objects.equals(previousValue, normalizedValue)) {
            return null;
        }
        value = normalizedValue;
        return new PendingChange<>(previousValue, normalizedValue);
    }

    /// Returns the concrete base implementation when the supplied property belongs to this property family.
    @SuppressWarnings("unchecked")
    private static <T> @Nullable AbstractProperty<T> asAbstractProperty(Property<T> property) {
        if (property instanceof AbstractProperty<?> abstractProperty) {
            return (AbstractProperty<T>) abstractProperty;
        }
        return null;
    }

    /// Stores one committed transition until it can be published outside the property state lock.
    @NotNullByDefault
    private static final class PendingChange<T> {
        /// The value before the committed transition.
        private final @Nullable T previousValue;

        /// The value after the committed transition.
        private final @Nullable T currentValue;

        /// Creates a pending transition.
        private PendingChange(@Nullable T previousValue, @Nullable T currentValue) {
            this.previousValue = previousValue;
            this.currentValue = currentValue;
        }

        /// Invokes the subclass hook and publishes this transition to the supplied change support.
        private void publish(AbstractProperty<T> property, ValueChangeSupport<T> changeSupport) {
            property.valueChanged(previousValue, currentValue);
            changeSupport.fireChange(previousValue, currentValue);
        }
    }

    /// Owns the two subscriptions and serializes propagation for one bidirectional property pair.
    @NotNullByDefault
    private static final class BidirectionalBinding<T> implements AutoCloseable {
        /// Serializes propagation and closure for this binding pair.
        private final Object propagationLock = new Object();

        /// The first property in the pair.
        private final Property<T> first;

        /// The second property in the pair.
        private final Property<T> second;

        /// Propagates first-property changes to the second property.
        private final Subscription firstSubscription;

        /// Propagates second-property changes to the first property.
        private final Subscription secondSubscription;

        /// Marks a reentrant propagation so its reverse callback is suppressed.
        private boolean propagating;

        /// Marks a removed binding so an in-flight callback cannot start new propagation.
        private boolean closed;

        /// Creates and subscribes a binding pair without performing initial synchronization.
        private BidirectionalBinding(Property<T> first, Property<T> second) {
            this.first = first;
            this.second = second;
            firstSubscription = first.subscribe(change -> propagate(first, second));
            secondSubscription = second.subscribe(change -> propagate(second, first));
        }

        /// Copies the second property's current value to the first property under recursion protection.
        private void synchronizeFromSecond() {
            propagate(second, first);
        }

        /// Serializes a current-value transfer and suppresses the reverse callback produced by the target setter.
        private void propagate(Property<T> source, Property<T> target) {
            synchronized (propagationLock) {
                if (closed || propagating) {
                    return;
                }
                propagating = true;
                try {
                    target.setValue(source.getValue());
                } finally {
                    propagating = false;
                }
            }
        }

        /// Stops both directions of propagation exactly once.
        @Override
        public void close() {
            synchronized (propagationLock) {
                if (closed) {
                    return;
                }
                closed = true;
            }
            firstSubscription.unsubscribe();
            secondSubscription.unsubscribe();
        }
    }
}
