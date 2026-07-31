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
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import space.minecraftstl.xyml.observable.Subscription;
import space.minecraftstl.xyml.observable.ValueChange;
import space.minecraftstl.xyml.observable.ValueChangeListener;
import space.minecraftstl.xyml.observable.ValueChangeSupport;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests the toolkit-neutral property values, metadata, bindings, and thread semantics.
@NotNullByDefault
public final class SimplePropertyTest {
    /// Verifies nullable object values, metadata, and suppression of equal transitions.
    @Test
    public void publishesOnlyDistinctNullableObjectValues() {
        Object bean = new Object();
        SimpleObjectProperty<String> property = new SimpleObjectProperty<>(bean, "selection", null);
        List<ValueChange<String>> changes = new ArrayList<>();
        property.subscribe(changes::add);

        property.set(null);
        property.set("ready");
        property.setValue("ready");
        property.set(null);

        assertSame(bean, property.getBean());
        assertEquals("selection", property.getName());
        assertNull(property.get());
        assertEquals(2, changes.size());
        assertNull(changes.get(0).previousValue());
        assertEquals("ready", changes.get(0).currentValue());
        assertEquals("ready", changes.get(1).previousValue());
        assertNull(changes.get(1).currentValue());
    }

    /// Verifies the subclass hook runs before listeners and only for committed distinct transitions.
    @Test
    public void invokesSubclassHookOnlyForRealChanges() {
        HookedObjectProperty property = new HookedObjectProperty("initial");
        SimpleStringProperty source = new SimpleStringProperty("bound");
        List<String> valuesSeenByListeners = new ArrayList<>();
        property.subscribe(change -> valuesSeenByListeners.add(property.lastHookValue));

        property.set("initial");
        property.set("direct");
        property.bind(source);
        source.set("bound");
        source.set("updated");

        assertEquals(List.of("direct", "bound", "updated"), property.hookValues);
        assertEquals(property.hookValues, valuesSeenByListeners);
    }

    /// Verifies immediate one-way synchronization, duplicate binding suppression, rebinding, and unbinding.
    @Test
    public void followsOneWayBindingUntilUnbound() {
        SimpleStringProperty firstSource = new SimpleStringProperty("first");
        SimpleStringProperty secondSource = new SimpleStringProperty("second");
        SimpleStringProperty target = new SimpleStringProperty("old");
        List<ValueChange<String>> changes = new ArrayList<>();
        target.subscribe(changes::add);

        target.bind(firstSource);
        target.bind(firstSource);
        firstSource.set("updated");

        assertTrue(target.isBound());
        assertEquals("updated", target.get());
        assertEquals(2, changes.size());
        assertThrows(IllegalStateException.class, () -> target.set("forbidden"));

        target.bind(secondSource);
        firstSource.set("stale");
        assertEquals("second", target.get());

        target.unbind();
        secondSource.set("ignored");
        target.set("manual");

        assertFalse(target.isBound());
        assertEquals("manual", target.get());
    }

    /// Verifies that a source update racing with the initial source read cannot be overwritten by the stale read.
    @Test
    public void retainsUpdateDuringBindingInstallation() throws Exception {
        PausingObservableValue source = new PausingObservableValue("old");
        SimpleStringProperty target = new SimpleStringProperty();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Integer> binding = executor.submit(() -> {
                target.bind(source);
                return 1;
            });

            source.awaitFirstRead();
            source.set("new");
            source.releaseFirstRead();
            binding.get(10, TimeUnit.SECONDS);

            assertEquals("new", target.get());
        } finally {
            source.releaseFirstRead();
            executor.shutdownNow();
        }
    }

    /// Verifies recursion-safe bidirectional propagation, duplicate suppression, and symmetric unbinding.
    @Test
    public void propagatesBidirectionalChangesWithoutRecursion() {
        SimpleStringProperty left = new SimpleStringProperty("left");
        SimpleStringProperty right = new SimpleStringProperty("right");
        List<ValueChange<String>> leftChanges = new ArrayList<>();
        List<ValueChange<String>> rightChanges = new ArrayList<>();
        left.subscribe(leftChanges::add);
        right.subscribe(rightChanges::add);

        left.bindBidirectional(right);
        right.bindBidirectional(left);
        assertEquals("right", left.get());

        left.set("alpha");
        assertEquals("alpha", right.get());

        right.set("beta");
        assertEquals("beta", left.get());

        right.unbindBidirectional(left);
        left.set("isolated");

        assertEquals("beta", right.get());
        assertEquals(4, leftChanges.size());
        assertEquals(2, rightChanges.size());
    }

    /// Verifies that primitive properties expose non-null boxed values and normalize nullable writes to zero values.
    @Test
    public void normalizesNullablePrimitiveWrites() {
        BooleanProperty booleanProperty = new SimpleBooleanProperty(true);
        IntegerProperty integerProperty = new SimpleIntegerProperty(7);
        LongProperty longProperty = new SimpleLongProperty(8L);
        DoubleProperty doubleProperty = new SimpleDoubleProperty(9.5);

        booleanProperty.setValue(null);
        integerProperty.setValue(null);
        longProperty.setValue(null);
        doubleProperty.setValue(null);

        assertFalse(booleanProperty.get());
        assertEquals(Boolean.FALSE, booleanProperty.getValue());
        assertEquals(0, integerProperty.get());
        assertEquals(0L, longProperty.get());
        assertEquals(0.0, doubleProperty.get());
    }

    /// Verifies that a listener runs synchronously on the thread that commits its value transition.
    @Test
    public void notifiesOnTheMutatingThread() throws Exception {
        SimpleIntegerProperty property = new SimpleIntegerProperty();
        AtomicReference<@Nullable Thread> listenerThread = new AtomicReference<>();
        property.subscribe(change -> listenerThread.set(Thread.currentThread()));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Thread> writer = executor.submit(() -> {
                Thread currentThread = Thread.currentThread();
                property.set(42);
                return currentThread;
            });

            assertSame(writer.get(10, TimeUnit.SECONDS), listenerThread.get());
        } finally {
            executor.shutdownNow();
        }
    }

    /// Verifies that simultaneous writes from both sides finish without recursion or divergent final values.
    @RepeatedTest(10)
    public void convergesAfterConcurrentBidirectionalWrites() throws Exception {
        SimpleIntegerProperty left = new SimpleIntegerProperty();
        SimpleIntegerProperty right = new SimpleIntegerProperty();
        left.bindBidirectional(right);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> leftWriter = executor.submit(() -> {
                start.await();
                for (int value = 1; value <= 200; value++) {
                    left.set(value);
                }
                return 1;
            });
            Future<Integer> rightWriter = executor.submit(() -> {
                start.await();
                for (int value = 1; value <= 200; value++) {
                    right.set(-value);
                }
                return 1;
            });

            start.countDown();
            leftWriter.get(10, TimeUnit.SECONDS);
            rightWriter.get(10, TimeUnit.SECONDS);

            assertEquals(left.get(), right.get());
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    /// Observable test source that pauses its first read after capturing the old value.
    @NotNullByDefault
    private static final class PausingObservableValue implements ObservableValue<String> {
        /// Publishes value changes to the bound test property.
        private final ValueChangeSupport<String> changeSupport = new ValueChangeSupport<>(this);

        /// Signals that the binding's initial read has captured its value.
        private final CountDownLatch firstReadStarted = new CountDownLatch(1);

        /// Releases the binding's paused initial read.
        private final CountDownLatch firstReadRelease = new CountDownLatch(1);

        /// Counts reads so only the binding's first read is paused.
        private final AtomicInteger readCount = new AtomicInteger();

        /// The source value visible to later binding callbacks.
        private volatile @Nullable String value;

        /// Creates a pausing source with an initial value.
        private PausingObservableValue(@Nullable String initialValue) {
            value = initialValue;
        }

        /// Returns the captured first value after the test releases it, or the current value on later reads.
        @Override
        public @Nullable String getValue() {
            @Nullable String capturedValue = value;
            if (readCount.getAndIncrement() == 0) {
                firstReadStarted.countDown();
                await(firstReadRelease);
            }
            return capturedValue;
        }

        /// Registers a synchronous value-change listener.
        @Override
        public Subscription subscribe(ValueChangeListener<String> listener) {
            return changeSupport.subscribe(listener);
        }

        /// Replaces the source value and publishes its transition.
        private void set(@Nullable String newValue) {
            @Nullable String previousValue = value;
            value = newValue;
            changeSupport.fireChange(previousValue, newValue);
        }

        /// Waits until the initial binding read has captured the old value.
        private void awaitFirstRead() {
            await(firstReadStarted);
        }

        /// Allows the paused initial binding read to return its captured value.
        private void releaseFirstRead() {
            firstReadRelease.countDown();
        }

        /// Waits for a test latch and converts timeout or interruption into a deterministic test failure.
        private static void await(CountDownLatch latch) {
            try {
                if (!latch.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Timed out waiting for binding test coordination");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Binding test coordination was interrupted", exception);
            }
        }
    }

    /// Object property that records each invocation of the protected distinct-change hook.
    @NotNullByDefault
    private static final class HookedObjectProperty extends SimpleObjectProperty<String> {
        /// Values recorded by the hook in publication order.
        private final List<String> hookValues = new ArrayList<>();

        /// Most recent value recorded by the hook before listener publication.
        private String lastHookValue;

        /// Creates a hooked property with its initial value, which is not a transition.
        private HookedObjectProperty(String initialValue) {
            super(initialValue);
            lastHookValue = initialValue;
        }

        /// Records each distinct committed value before subscribers are notified.
        @Override
        protected void valueChanged(@Nullable String previousValue, @Nullable String currentValue) {
            String nonNullValue = Objects.requireNonNull(currentValue, "currentValue");
            lastHookValue = nonNullValue;
            hookValues.add(nonNullValue);
        }
    }
}
