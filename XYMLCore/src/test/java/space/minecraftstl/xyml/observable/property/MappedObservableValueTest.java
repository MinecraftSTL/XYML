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
