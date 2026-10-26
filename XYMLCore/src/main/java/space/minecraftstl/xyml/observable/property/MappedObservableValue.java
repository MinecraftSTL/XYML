// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.observable.property;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import space.minecraftstl.xyml.observable.Subscription;
import space.minecraftstl.xyml.observable.ValueChange;
import space.minecraftstl.xyml.observable.ValueChangeListener;
import space.minecraftstl.xyml.observable.ValueChangeSupport;

import java.util.Objects;
import java.util.function.Function;

/// Lazily maps one toolkit-neutral observable value to another value type.
///
/// The source subscription exists only while this mapped value has subscribers, preventing a short-lived mapped
/// value from being retained by a process-wide source. Direct reads map the source's current value without caching.
///
/// @param <S> source value type
/// @param <T> mapped value type
@NotNullByDefault
public final class MappedObservableValue<S, T> implements ObservableValue<T> {
    /// Source value observed while at least one mapped subscriber exists.
    private final ObservableValue<S> source;

    /// Pure source-to-target mapping function.
    private final Function<@Nullable S, @Nullable T> mapper;

    /// Publishes mapped transitions to independently removable subscribers.
    private final ValueChangeSupport<T> changeSupport = new ValueChangeSupport<>(this);

    /// Serializes subscriber counting and source-subscription ownership.
    private final Object subscriptionLock = new Object();

    /// Number of active mapped subscriptions.
    private int subscriberCount;

    /// Active source subscription, or null before the first subscriber and after the last one leaves.
    private @Nullable Subscription sourceSubscription;

    /// Creates a lazily subscribed mapped value.
    ///
    /// @param source source observable
    /// @param mapper source-to-target mapper accepting an absent source value
    public MappedObservableValue(
            ObservableValue<S> source,
            Function<@Nullable S, @Nullable T> mapper) {
        this.source = Objects.requireNonNull(source, "source");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    /// Creates a lazily subscribed mapped value with inferred generic arguments.
    ///
    /// @param source source observable
    /// @param mapper source-to-target mapper
    /// @param <S> source value type
    /// @param <T> mapped value type
    /// @return mapped observable value
    public static <S, T> ObservableValue<T> map(
            ObservableValue<S> source,
            Function<@Nullable S, @Nullable T> mapper) {
        return new MappedObservableValue<>(source, mapper);
    }

    /// Maps the source's current value.
    ///
    /// @return current mapped value, or null when produced by the mapper
    @Override
    public @Nullable T getValue() {
        return mapper.apply(source.getValue());
    }

    /// Registers a mapped listener and attaches to the source for the shared active-subscriber interval.
    ///
    /// @param listener mapped transition listener
    /// @return independently cancellable subscription
    @Override
    public Subscription subscribe(ValueChangeListener<T> listener) {
        Subscription mappedSubscription = changeSupport.subscribe(listener);
        boolean attachSource;
        synchronized (subscriptionLock) {
            subscriberCount++;
            attachSource = subscriberCount == 1;
        }
        if (attachSource) {
            attachSourceSubscription();
        }
        return Subscription.create(() -> removeSubscription(mappedSubscription));
    }

    /// Installs a source listener unless all mapped subscribers leave during installation.
    private void attachSourceSubscription() {
        Subscription candidate = source.subscribe(this::sourceChanged);
        boolean retain;
        synchronized (subscriptionLock) {
            retain = subscriberCount > 0 && sourceSubscription == null;
            if (retain) {
                sourceSubscription = candidate;
            }
        }
        if (!retain) {
            candidate.unsubscribe();
        }
    }

    /// Maps and publishes one source transition.
    ///
    /// @param change source transition
    private void sourceChanged(ValueChange<S> change) {
        changeSupport.fireChange(
                mapper.apply(change.previousValue()),
                mapper.apply(change.currentValue()));
    }

    /// Removes one mapped subscription and detaches from the source after the last removal.
    ///
    /// @param mappedSubscription mapped registration to remove
    private void removeSubscription(Subscription mappedSubscription) {
        mappedSubscription.unsubscribe();
        @Nullable Subscription detach = null;
        synchronized (subscriptionLock) {
            if (subscriberCount <= 0) {
                return;
            }
            subscriberCount--;
            if (subscriberCount == 0) {
                detach = sourceSubscription;
                sourceSubscription = null;
            }
        }
        if (detach != null) {
            detach.unsubscribe();
        }
    }
}
