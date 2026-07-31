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
package space.minecraftstl.xyml.util.gson;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.annotations.JsonAdapter;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import space.minecraftstl.xyml.observable.collection.ObservableCollections;
import space.minecraftstl.xyml.observable.collection.ObservableList;
import space.minecraftstl.xyml.observable.collection.ObservableMap;
import space.minecraftstl.xyml.observable.collection.ObservableSet;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests toolkit-neutral observable setting persistence and aggregate change tracking.
@NotNullByDefault
public final class ObservableSettingTest {
    /// Verifies every supported observable family contributes to dirty state and aggregate notifications.
    @Test
    public void aggregatesNeutralChanges() {
        Gson gson = new GsonBuilder().create();
        DualSetting setting = new DualSetting();
        List<Long> revisions = new ArrayList<>();
        setting.changes().subscribe(change -> revisions.add(change.currentValue()));

        setting.primary.set("primary-changed");
        setting.neutral.set("neutral-changed");
        setting.items.add("item");
        setting.tags.add("tag");
        setting.weights.put("weight", 3);
        TrackedElement trackedElement = new TrackedElement("tracked");
        setting.trackedItems.add(trackedElement);
        trackedElement.signal.set("element-updated");

        assertEquals(List.of(1L, 2L, 3L, 4L, 5L, 6L, 7L), revisions);

        JsonObject serialized = gson.toJsonTree(setting).getAsJsonObject();
        assertEquals("primary-changed", serialized.get("primary").getAsString());
        assertEquals("neutral-changed", serialized.get("neutral").getAsString());
        assertEquals("item", serialized.getAsJsonArray("items").get(0).getAsString());
        assertEquals("tag", serialized.getAsJsonArray("tags").get(0).getAsString());
        assertEquals(3, serialized.getAsJsonObject("weights").get("weight").getAsInt());
        assertEquals("tracked", serialized.getAsJsonArray("trackedItems")
                .get(0).getAsJsonObject().get("name").getAsString());
        assertFalse(serialized.has("rawText"));
    }

    /// Keeps the aggregate revision accurate even when an exact-field listener rejects a change.
    @Test
    public void advancesRevisionBeforePublishingChangedField() {
        DualSetting setting = new DualSetting();
        setting.changedFields().subscribe(change -> {
            throw new IllegalStateException("listener failure");
        });

        assertThrows(IllegalStateException.class, () -> setting.primary.set("changed"));
        assertEquals(1L, setting.changes().getValue());
    }

    /// Verifies neutral fields deserialize, alternate data survives, and unknown members round-trip unchanged.
    @Test
    public void deserializesNeutralFieldsAndPreservesUnknownFields() {
        Gson gson = new GsonBuilder().create();
        String source = """
                {
                  "primary": "primary-loaded",
                  "neutral": "neutral-loaded",
                  "items": ["one", "two"],
                  "tags": ["alpha", "beta"],
                  "weights": {"first": 1, "second": 2},
                  "future": {"enabled": true}
                }
                """;

        DualSetting setting = gson.fromJson(source, DualSetting.class);

        assertEquals("primary-loaded", setting.primary.get());
        assertEquals("neutral-loaded", setting.neutral.get());
        assertEquals(List.of("one", "two"), setting.items);
        assertEquals(List.of("alpha", "beta"), new ArrayList<>(setting.tags));
        assertEquals(Map.of("first", 1, "second", 2), setting.weights);

        JsonObject serialized = gson.toJsonTree(setting).getAsJsonObject();
        assertTrue(serialized.getAsJsonObject("future").get("enabled").getAsBoolean());
        assertEquals("neutral-loaded", serialized.get("neutral").getAsString());
    }

    /// Verifies unsupported JSON remains verbatim across equal writes and clears only after a logical value change.
    @Test
    public void retainsRawJsonUntilLogicalValueActuallyChanges() {
        Gson gson = new GsonBuilder().create();
        DualSetting setting = gson.fromJson(
                "{\"rawText\":{\"future\":1}}",
                DualSetting.class);

        JsonObject originalRoundTrip = gson.toJsonTree(setting).getAsJsonObject();
        assertEquals(1, originalRoundTrip.getAsJsonObject("rawText").get("future").getAsInt());

        setting.rawText.set(new String("stable"));
        JsonObject equalRoundTrip = gson.toJsonTree(setting).getAsJsonObject();
        assertEquals(1, equalRoundTrip.getAsJsonObject("rawText").get("future").getAsInt());

        setting.rawText.set("changed");
        JsonObject changedRoundTrip = gson.toJsonTree(setting).getAsJsonObject();
        assertEquals("changed", changedRoundTrip.get("rawText").getAsString());
    }

    /// Settings fixture containing all toolkit-neutral observable field kinds.
    @JsonAdapter(DualSettingAdapter.class)
    @NotNullByDefault
    private static final class DualSetting extends ObservableSetting {
        /// Primary toolkit-neutral scalar property.
        private final space.minecraftstl.xyml.observable.property.SimpleStringProperty primary =
                new space.minecraftstl.xyml.observable.property.SimpleStringProperty("primary-default");

        /// Toolkit-neutral scalar property.
        private final space.minecraftstl.xyml.observable.property.SimpleStringProperty neutral =
                new space.minecraftstl.xyml.observable.property.SimpleStringProperty("neutral-default");

        /// Toolkit-neutral observable list.
        private final ObservableList<String> items = ObservableCollections.observableList();

        /// Toolkit-neutral observable set.
        private final ObservableSet<String> tags = ObservableCollections.observableSet();

        /// Toolkit-neutral observable map.
        private final ObservableMap<String, Integer> weights = ObservableCollections.observableMap();

        /// Toolkit-neutral list that observes changes inside existing elements.
        private final ObservableList<TrackedElement> trackedItems = ObservableCollections.observableList(
                element -> List.of(element.signal));

        /// Raw-preserving property used to verify lossless persistence behavior.
        private final RawPreservingObjectProperty<String> rawText =
                new RawPreservingObjectProperty<>(new String("stable"));

        /// Initializes and registers every observable fixture field.
        private DualSetting() {
            register();
        }
    }

    /// Gson adapter that creates the fully registered neutral fixture.
    @NotNullByDefault
    public static final class DualSettingAdapter extends ObservableSetting.Adapter<DualSetting> {
        /// Creates a new neutral settings fixture.
        @Override
        protected DualSetting createInstance() {
            return new DualSetting();
        }
    }

    /// Element fixture with serializable identity and a transient observable dependency.
    @NotNullByDefault
    private static final class TrackedElement {
        /// Stable serialized element name.
        private final String name;

        /// Internal observable dependency excluded from element JSON.
        private transient final space.minecraftstl.xyml.observable.property.SimpleStringProperty signal =
                new space.minecraftstl.xyml.observable.property.SimpleStringProperty();

        /// Creates an element with the supplied serialized name.
        private TrackedElement(String name) {
            this.name = name;
        }
    }
}
