// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.game;

import com.google.gson.TypeAdapter;
import com.google.gson.annotations.JsonAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import space.minecraftstl.xyml.util.gson.JsonSerializable;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;

/// Stable identifier of one installed game instance.
///
/// @param id non-blank directory and manifest identifier
@NotNullByDefault
@JsonAdapter(GameInstanceID.Adapter.class)
@JsonSerializable
public record GameInstanceID(String id) implements Comparable<GameInstanceID> {
    /// Validates that the serialized instance identifier is non-blank.
    public GameInstanceID {
        if (id.isBlank()) {
            throw new IllegalArgumentException("Game instance id cannot be empty");
        }
    }

    /// Compares identifiers lexicographically by their serialized value.
    @Override
    public int compareTo(GameInstanceID that) {
        return this.id.compareTo(that.id);
    }

    /// Returns the serialized identifier value.
    @Override
    public String toString() {
        return id;
    }

    /// Gson adapter that serializes identifiers as their legacy JSON string representation.
    static final class Adapter extends TypeAdapter<@Nullable GameInstanceID> {

        /// Reads a nullable identifier string.
        @Override
        public @Nullable GameInstanceID read(JsonReader in) throws IOException {
            if (in.peek() == JsonToken.NULL) {
                in.nextNull();
                return null;
            }

            return new GameInstanceID(in.nextString());
        }

        /// Writes a nullable identifier as a JSON string or null.
        @Override
        public void write(JsonWriter out, @Nullable GameInstanceID value) throws IOException {
            out.value(value != null ? value.id() : null);
        }
    }
}
