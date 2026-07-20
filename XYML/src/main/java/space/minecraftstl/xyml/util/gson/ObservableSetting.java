/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2025 huangyuhui <huanghongxun2008@126.com> and contributors
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

import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;
import com.google.gson.annotations.SerializedName;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.observable.Subscription;
import space.minecraftstl.xyml.observable.ValueChangeListener;
import space.minecraftstl.xyml.observable.ValueChangeSupport;
import space.minecraftstl.xyml.observable.collection.ObservableList;
import space.minecraftstl.xyml.observable.collection.ObservableMap;
import space.minecraftstl.xyml.observable.collection.ObservableSet;
import space.minecraftstl.xyml.observable.property.ObservableValue;
import space.minecraftstl.xyml.observable.property.Property;
import space.minecraftstl.xyml.observable.property.SimpleLongProperty;
import space.minecraftstl.xyml.util.TypeUtils;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static space.minecraftstl.xyml.util.logging.Logger.LOG;

/// Base for JSON settings composed of observable property and collection fields.
///
/// Non-static, non-transient subclass fields use the toolkit-neutral property and collection APIs in
/// `space.minecraftstl.xyml.observable`. Dirty tracking and the [#changes()] revision cover every supported field.
/// Serialization omits fields that have not changed or been read from JSON, while unknown fields and unsupported raw
/// property JSON survive round trips.
/// Subclasses must invoke [#register()] once after initializing all observable fields.
///
/// @author Glavo
@NotNullByDefault
public abstract class ObservableSetting {
    /// Caches reflected observable-field metadata independently for every concrete settings class.
    private static final ClassValue<@Unmodifiable List<? extends ObservableField<?>>> FIELDS =
            new ClassValue<>() {
                /// Resolves and validates every persistent observable field declared by the settings subtype.
                @Override
                protected @Unmodifiable List<? extends ObservableField<?>> computeValue(Class<?> type) {
                    if (!ObservableSetting.class.isAssignableFrom(type)) {
                        throw new AssertionError("Type: " + type);
                    }

                    try {
                        ArrayList<ObservableField<?>> allFields = new ArrayList<>();
                        for (Class<?> current = type;
                             current != ObservableSetting.class;
                             current = current.getSuperclass()) {
                            MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(
                                    current,
                                    MethodHandles.lookup());
                            for (Field field : current.getDeclaredFields()) {
                                int modifiers = field.getModifiers();
                                if (!Modifier.isTransient(modifiers) && !Modifier.isStatic(modifiers)) {
                                    allFields.add(ObservableField.of(lookup, field));
                                }
                            }
                        }
                        return List.copyOf(allFields);
                    } catch (IllegalAccessException exception) {
                        throw new ExceptionInInitializerError(exception);
                    }
                }
            };

    /// Unknown JSON members retained for lossless serialization.
    protected transient final Map<String, JsonElement> unknownFields = new HashMap<>();

    /// Tracks dirty fields by observable identity.
    private transient final Set<Object> dirtyFields = Collections.newSetFromMap(new IdentityHashMap<>());

    /// Retains cancellable subscriptions installed on persistent fields.
    private transient final List<Subscription> subscriptions = new ArrayList<>();

    /// Serializes revision increments from fields that may change on different threads.
    private transient final Object changesLock = new Object();

    /// Monotonic aggregate revision shared by all persistent fields.
    private transient final SimpleLongProperty changes = new SimpleLongProperty(this, "changes");

    /// Publishes the exact persistent field responsible for every aggregate revision.
    private transient final ChangedFieldObservable changedFields = new ChangedFieldObservable();

    /// Whether at least one deferred field change still needs to be saved.
    private transient volatile boolean savePending;

    /// Prevents duplicate reflection registration and listener installation.
    private transient boolean registered;

    /// Installs dirty tracking and aggregate notifications for all declared persistent fields exactly once.
    protected final synchronized void register() {
        if (registered) {
            return;
        }

        registered = true;
        for (ObservableField<ObservableSetting> field : observableFields(this)) {
            Object fieldValue = field.get(this);
            subscriptions.add(subscribe(fieldValue));
        }
    }

    /// Returns the monotonic neutral revision incremented by every observed field change.
    public final ObservableValue<Long> changes() {
        return changes;
    }

    /// Returns a neutral observable that publishes the exact property or collection field that changed.
    public final ObservableValue<Object> changedFields() {
        return changedFields;
    }

    /// Returns whether a change to the supplied persistent field should be saved immediately.
    ///
    /// @param changedField exact property or collection field reported by [#changedFields()]
    /// @return whether the change should trigger persistence immediately
    public boolean shouldSaveImmediately(Object changedField) {
        Objects.requireNonNull(changedField, "changedField");
        return true;
    }

    /// Returns whether at least one deferred field change still needs to be saved.
    public final boolean isSavePending() {
        return savePending;
    }

    /// Sets whether at least one deferred field change still needs to be saved.
    ///
    /// @param savePending whether a deferred field change remains unsaved
    public final void setSavePending(boolean savePending) {
        this.savePending = savePending;
    }

    /// Subscribes to one toolkit-neutral property or collection field.
    private Subscription subscribe(Object fieldValue) {
        if (fieldValue instanceof Property<?> property) {
            return property.subscribe(change -> fieldChanged(fieldValue));
        }
        if (fieldValue instanceof ObservableList<?> list) {
            return list.subscribe(change -> fieldChanged(fieldValue));
        }
        if (fieldValue instanceof ObservableSet<?> set) {
            return set.subscribe(change -> fieldChanged(fieldValue));
        }
        if (fieldValue instanceof ObservableMap<?, ?> map) {
            return map.subscribe(change -> fieldChanged(fieldValue));
        }
        throw new IllegalArgumentException("Unsupported observable field type: " + fieldValue.getClass());
    }

    /// Marks one field dirty and increments the shared revision.
    private void fieldChanged(Object fieldValue) {
        synchronized (dirtyFields) {
            dirtyFields.add(fieldValue);
        }
        changedFields.publish(fieldValue);
        incrementChanges();
    }

    /// Observable facade that preserves the identity of each changed field, including repeated changes to one field.
    @NotNullByDefault
    private final class ChangedFieldObservable implements ObservableValue<Object> {
        /// Synchronous subscriptions sourced from this settings object.
        private final ValueChangeSupport<Object> changeSupport = new ValueChangeSupport<>(ObservableSetting.this);

        /// Most recently changed field, or null before the first persistent-field change.
        private volatile @Nullable Object currentField;

        /// Returns the most recently changed persistent field.
        @Override
        public @Nullable Object getValue() {
            return currentField;
        }

        /// Registers a listener for exact persistent-field changes.
        @Override
        public Subscription subscribe(ValueChangeListener<Object> listener) {
            return changeSupport.subscribe(listener);
        }

        /// Publishes one field identity even when the same field changed immediately beforehand.
        private void publish(Object fieldValue) {
            currentField = Objects.requireNonNull(fieldValue, "fieldValue");
            changeSupport.fireChange(null, fieldValue);
        }
    }

    /// Increments the aggregate revision while preserving strict publication order.
    private void incrementChanges() {
        synchronized (changesLock) {
            changes.set(changes.get() + 1L);
        }
    }

    /// Returns whether the supplied field should be serialized.
    final boolean isDirty(Object fieldValue) {
        synchronized (dirtyFields) {
            return dirtyFields.contains(fieldValue);
        }
    }

    /// Marks a deserialized field dirty before applying its JSON value.
    protected final void markDirty(Object fieldValue) {
        synchronized (dirtyFields) {
            dirtyFields.add(fieldValue);
        }
    }

    /// Returns concrete field metadata with the generic owner type restored after ClassValue lookup.
    @SuppressWarnings("unchecked")
    private static <T extends ObservableSetting> @Unmodifiable List<ObservableField<T>> observableFields(T setting) {
        return (List<ObservableField<T>>) (List<?>) FIELDS.get(setting.getClass());
    }

    /// Describes one reflected persistent field and its JSON conversion strategy.
    @NotNullByDefault
    private static sealed abstract class ObservableField<T> {
        /// Primary JSON member name.
        private final String serializedName;

        /// Direct accessor for the final observable field.
        private final VarHandle varHandle;

        /// Creates metadata shared by all observable field strategies.
        private ObservableField(String serializedName, VarHandle varHandle) {
            this.serializedName = serializedName;
            this.varHandle = varHandle;
        }

        /// Resolves the field's names, accessor, observable family, and generic JSON type.
        private static <T> ObservableField<T> of(MethodHandles.Lookup lookup, Field field) {
            @Nullable SerializedName serializedNameAnnotation = field.getAnnotation(SerializedName.class);
            String name = serializedNameAnnotation == null
                    ? field.getName()
                    : serializedNameAnnotation.value();
            VarHandle varHandle;
            try {
                varHandle = lookup.unreflectVarHandle(field);
            } catch (IllegalAccessException exception) {
                throw new IllegalArgumentException("Cannot access setting field " + field.getName(), exception);
            }

            Class<?> fieldClass = field.getType();
            if (isListClass(fieldClass)) {
                Type listType = requireParameterizedSupertype(field, List.class, "list");
                return new CollectionField<>(name, varHandle, listType, listType);
            }
            if (isSetClass(fieldClass)) {
                ParameterizedType setType = requireParameterizedSupertype(field, Set.class, "set");
                ParameterizedType listType = TypeUtils.newParameterizedTypeWithOwner(
                        null,
                        List.class,
                        setType.getActualTypeArguments()[0]);
                return new CollectionField<>(name, varHandle, setType, listType);
            }
            if (isMapClass(fieldClass)) {
                Type mapType = requireParameterizedSupertype(field, Map.class, "map");
                return new MapField<>(name, varHandle, mapType);
            }
            if (Property.class.isAssignableFrom(fieldClass)) {
                ParameterizedType propertyType = requireParameterizedSupertype(
                        field,
                        Property.class,
                        "property");
                return new PropertyField<>(
                        name,
                        varHandle,
                        propertyType.getActualTypeArguments()[0]);
            }
            throw new IllegalArgumentException(
                    "Field " + field.getName() + " is not a supported property or observable collection");
        }

        /// Returns whether a field class is a supported observable list.
        private static boolean isListClass(Class<?> fieldClass) {
            return ObservableList.class.isAssignableFrom(fieldClass);
        }

        /// Returns whether a field class is a supported observable set.
        private static boolean isSetClass(Class<?> fieldClass) {
            return ObservableSet.class.isAssignableFrom(fieldClass);
        }

        /// Returns whether a field class is a supported observable map.
        private static boolean isMapClass(Class<?> fieldClass) {
            return ObservableMap.class.isAssignableFrom(fieldClass);
        }

        /// Resolves one generic Java supertype or rejects raw observable field declarations.
        private static ParameterizedType requireParameterizedSupertype(
                Field field,
                Class<?> supertype,
                String description) {
            Type resolved = TypeUtils.getSupertype(field.getGenericType(), field.getType(), supertype);
            if (resolved instanceof ParameterizedType parameterizedType) {
                return parameterizedType;
            }
            throw new IllegalArgumentException(
                    "Cannot resolve the " + description + " type of setting field " + field.getName());
        }

        /// Returns the primary JSON member name.
        final String getSerializedName() {
            return serializedName;
        }

        /// Returns the non-null observable field value from the supplied settings instance.
        final Object get(T value) {
            return Objects.requireNonNull(varHandle.get(value), "Observable setting field " + serializedName);
        }

        /// Writes this field to the result object when its current value is serializable.
        protected abstract void serialize(JsonObject result, T value, JsonSerializationContext context);

        /// Applies one JSON member to this field.
        protected abstract void deserialize(T value, JsonElement element, JsonDeserializationContext context);

        /// Handles toolkit-neutral scalar properties.
        @NotNullByDefault
        private static final class PropertyField<T> extends ObservableField<T> {
            /// Gson type of the property's contained value.
            private final Type elementType;

            /// Creates scalar property metadata.
            private PropertyField(
                    String serializedName,
                    VarHandle varHandle,
                    Type elementType) {
                super(serializedName, varHandle);
                this.elementType = elementType;
            }

            /// Serializes retained raw JSON first, otherwise the property's current typed value.
            @Override
            protected void serialize(JsonObject result, T value, JsonSerializationContext context) {
                Object property = get(value);
                if (property instanceof RawPreservingProperty<?> rawPreserving) {
                    @Nullable JsonElement rawJson = rawPreserving.getRawJson();
                    if (rawJson != null) {
                        result.add(getSerializedName(), rawJson);
                        return;
                    }
                }

                @Nullable Object propertyValue = readPropertyValue(property);
                @Nullable JsonElement serialized = context.serialize(propertyValue, elementType);
                if (serialized != null && !serialized.isJsonNull()) {
                    result.add(getSerializedName(), serialized);
                }
            }

            /// Deserializes and writes a typed value, retaining the original JSON when a raw-aware property rejects it.
            @Override
            protected void deserialize(T value, JsonElement element, JsonDeserializationContext context) {
                Object property = get(value);
                try {
                    writePropertyValue(property, context.deserialize(element, elementType));
                } catch (RuntimeException exception) {
                    if (property instanceof RawPreservingProperty<?> rawPreserving) {
                        rawPreserving.setRawJson(element);
                    } else {
                        throw exception;
                    }
                }
            }

            /// Reads the current property value.
            private static @Nullable Object readPropertyValue(Object property) {
                if (property instanceof Property<?> neutralProperty) {
                    return neutralProperty.getValue();
                }
                throw new IllegalArgumentException("Unsupported property type: " + property.getClass());
            }

            /// Writes a possibly-null property value.
            @SuppressWarnings({"rawtypes", "unchecked"})
            private static void writePropertyValue(Object property, @Nullable Object value) {
                if (property instanceof Property neutralProperty) {
                    neutralProperty.setValue(value);
                    return;
                }
                throw new IllegalArgumentException("Unsupported property type: " + property.getClass());
            }
        }

        /// Handles toolkit-neutral observable lists and sets.
        @NotNullByDefault
        private static final class CollectionField<T> extends ObservableField<T> {
            /// Gson type used when serializing the collection.
            private final Type collectionType;

            /// Gson list type used to deserialize both lists and insertion-ordered sets.
            private final Type listType;

            /// Creates observable collection metadata.
            private CollectionField(
                    String serializedName,
                    VarHandle varHandle,
                    Type collectionType,
                    Type listType) {
                super(serializedName, varHandle);
                this.collectionType = collectionType;
                this.listType = listType;
            }

            /// Serializes the observable collection through its ordinary Java collection supertype.
            @Override
            protected void serialize(JsonObject result, T value, JsonSerializationContext context) {
                result.add(getSerializedName(), context.serialize(get(value), collectionType));
            }

            /// Replaces list contents or clears and repopulates set contents.
            @Override
            @SuppressWarnings("unchecked")
            protected void deserialize(T value, JsonElement element, JsonDeserializationContext context) {
                List<?> deserialized = Objects.requireNonNull(
                        context.deserialize(element, listType),
                        "deserialized collection");
                Object fieldValue = get(value);

                if (fieldValue instanceof ObservableList<?> list) {
                    ((ObservableList<Object>) list).setAll(deserialized);
                } else if (fieldValue instanceof ObservableSet<?> set) {
                    replaceSet((Set<Object>) set, deserialized);
                } else {
                    throw new JsonParseException("Unsupported collection type: " + fieldValue.getClass());
                }
            }

            /// Clears and repopulates an observable set while preserving JSON encounter order.
            private static void replaceSet(Set<Object> set, Collection<?> values) {
                set.clear();
                set.addAll(values);
            }
        }

        /// Handles toolkit-neutral observable maps.
        @NotNullByDefault
        private static final class MapField<T> extends ObservableField<T> {
            /// Gson type of the map key and value pair.
            private final Type mapType;

            /// Creates observable map metadata.
            private MapField(
                    String serializedName,
                    VarHandle varHandle,
                    Type mapType) {
                super(serializedName, varHandle);
                this.mapType = mapType;
            }

            /// Serializes the map through its ordinary Java map supertype.
            @Override
            protected void serialize(JsonObject result, T value, JsonSerializationContext context) {
                result.add(getSerializedName(), context.serialize(get(value), mapType));
            }

            /// Replaces all entries in the observable map.
            @Override
            @SuppressWarnings("unchecked")
            protected void deserialize(T value, JsonElement element, JsonDeserializationContext context) {
                Map<Object, Object> deserialized = Objects.requireNonNull(
                        context.deserialize(element, mapType),
                        "deserialized map");
                Object fieldValue = get(value);
                if (fieldValue instanceof ObservableMap<?, ?> map) {
                    Map<Object, Object> writableMap = (Map<Object, Object>) map;
                    writableMap.clear();
                    writableMap.putAll(deserialized);
                } else {
                    throw new JsonParseException("Unsupported map type: " + fieldValue.getClass());
                }
            }
        }
    }

    /// Gson adapter base that serializes dirty fields and retains unknown JSON members.
    @NotNullByDefault
    public abstract static class Adapter<T extends ObservableSetting>
            implements JsonSerializer<T>, JsonDeserializer<T> {
        /// Creates a fully initialized settings instance whose constructor has called [#register()].
        protected abstract T createInstance();

        /// Serializes dirty known fields followed by all retained unknown fields.
        @Override
        public JsonElement serialize(
                @Nullable T setting,
                Type typeOfSrc,
                JsonSerializationContext context) {
            if (setting == null) {
                return JsonNull.INSTANCE;
            }

            JsonObject result = new JsonObject();
            for (ObservableField<T> field : observableFields(setting)) {
                Object fieldValue = field.get(setting);
                if (setting.isDirty(fieldValue)) {
                    field.serialize(result, setting, context);
                }
            }
            setting.unknownFields.forEach(result::add);
            return result;
        }

        /// Deserializes known fields by their current names and retains every unrecognized member.
        @Override
        public @Nullable T deserialize(
                @Nullable JsonElement json,
                Type typeOfT,
                JsonDeserializationContext context) throws JsonParseException {
            if (json == null || json.isJsonNull()) {
                return null;
            }
            if (!json.isJsonObject()) {
                throw new JsonParseException("Config is not an object: " + json);
            }

            T setting = createInstance();
            LinkedHashMap<String, JsonElement> values = new LinkedHashMap<>(json.getAsJsonObject().asMap());
            for (ObservableField<T> field : observableFields(setting)) {
                @Nullable JsonElement value = values.remove(field.getSerializedName());
                if (value != null) {
                    setting.markDirty(field.get(setting));
                    try {
                        field.deserialize(setting, value, context);
                    } catch (RuntimeException exception) {
                        LOG.warning(
                                "Ignoring invalid setting field "
                                        + setting.getClass().getName()
                                        + "."
                                        + field.getSerializedName(),
                                exception);
                    }
                }
            }

            setting.unknownFields.putAll(values);
            return setting;
        }
    }
}
