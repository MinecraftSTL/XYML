// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.observable.property;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import space.minecraftstl.xyml.observable.Subscription;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/// Verifies lazy mapped reads, distinct mapped transitions, and unsubscription.
@NotNullByDefault
final class MappedObservableValueTest {
    /// Mapping follows the source and stops publishing after its subscription is removed.
    @Test
    void mapsDistinctChangesUntilUnsubscribed() {
        SimpleObjectProperty<String> source = new SimpleObjectProperty<>("a");
        ObservableValue<Integer> mapped = MappedObservableValue.map(
                source,
                value -> value == null ? -1 : value.length());
        List<Integer> observed = new ArrayList<>();

        assertEquals(1, mapped.getValue());
        Subscription subscription = mapped.subscribe(change -> observed.add(change.currentValue()));
        source.setValue("bb");
        source.setValue("cc");
        source.setValue(null);
        subscription.unsubscribe();
        source.setValue("three");

        assertEquals(List.of(2, -1), observed);
        assertEquals(5, mapped.getValue());
    }
}
