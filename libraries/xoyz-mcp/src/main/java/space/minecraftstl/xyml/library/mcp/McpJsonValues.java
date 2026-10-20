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
package space.minecraftstl.xyml.library.mcp;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/// Creates immutable snapshots of values in the JSON data model.
@NotNullByDefault
final class McpJsonValues {
    /// Utility class; no instances are needed.
    private McpJsonValues() {
    }

    /// Recursively snapshots a JSON object while retaining its iteration order.
    ///
    /// @param source source JSON object
    /// @return immutable deep snapshot
    static @Unmodifiable Map<String, @Nullable Object> snapshotMap(
            Map<String, @Nullable Object> source) {
        return snapshotMapEntries(Objects.requireNonNull(source, "source"));
    }

    /// Recursively snapshots one JSON value.
    ///
    /// @param value source value
    /// @return immutable Map or List snapshot, or the original JSON scalar
    private static @Nullable Object snapshotValue(@Nullable Object value) {
        if (value == null || value instanceof String || value instanceof Boolean
                || value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long
                || value instanceof BigInteger || value instanceof BigDecimal) {
            return value;
        }
        if (value instanceof Float number) {
            if (Float.isFinite(number)) {
                return number;
            }
            throw new IllegalArgumentException("MCP JSON numbers must be finite");
        }
        if (value instanceof Double number) {
            if (Double.isFinite(number)) {
                return number;
            }
            throw new IllegalArgumentException("MCP JSON numbers must be finite");
        }
        if (value instanceof Number) {
            throw new IllegalArgumentException("Unsupported MCP JSON number type: " + value.getClass().getName());
        }
        if (value instanceof Map<?, ?> map) {
            return snapshotMapEntries(map);
        }
        if (value instanceof List<?> list) {
            return snapshotList(list);
        }
        throw new IllegalArgumentException("Unsupported MCP JSON value type: " + value.getClass().getName());
    }

    /// Recursively snapshots a JSON object whose key types are not yet validated.
    ///
    /// @param source source object
    /// @return immutable deep snapshot
    private static @Unmodifiable Map<String, @Nullable Object> snapshotMapEntries(
            Map<@Nullable ?, @Nullable ?> source) {
        Map<String, @Nullable Object> result = new LinkedHashMap<>();
        for (Map.Entry<@Nullable ?, @Nullable ?> entry : source.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw new IllegalArgumentException("MCP JSON object keys must be non-null strings");
            }
            result.put(key, snapshotValue(entry.getValue()));
        }
        return Collections.unmodifiableMap(result);
    }

    /// Recursively snapshots a JSON array.
    ///
    /// @param source source array
    /// @return immutable deep snapshot
    private static @Unmodifiable List<@Nullable Object> snapshotList(List<@Nullable ?> source) {
        List<@Nullable Object> result = new ArrayList<>(source.size());
        for (@Nullable Object value : source) {
            result.add(snapshotValue(value));
        }
        return Collections.unmodifiableList(result);
    }
}
