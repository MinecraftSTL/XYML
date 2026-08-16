/*
 * Copyright 2026 Glavo
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.glavo.nbt.io;

import org.jetbrains.annotations.Contract;

import java.util.Objects;

/// Represents the surrounding spaces for SNBT.
///
/// @see SNBTCodec
/// @see SNBTCodec#getSurroundingSpaces()
/// @see SNBTCodec#withSurroundingSpaces(SurroundingSpaces)
public final class SurroundingSpaces {
    /// Surrounding spaces for compact SNBT.
    public static final SurroundingSpaces COMPACT = new SurroundingSpaces(0, 0, 0, 0, 0);

    /// Surrounding spaces for pretty SNBT.
    public static final SurroundingSpaces PRETTY = new SurroundingSpaces(0, 1, 0, 1, 1);

    /// Creates a new builder for [SurroundingSpaces].
    public static SurroundingSpaces.Builder newBuilder() {
        return new SurroundingSpaces.Builder();
    }

    private final int spacesBeforeColon;
    private final int spacesAfterColon;
    private final int spacesBeforeComma;
    private final int spacesAfterComma;
    private final int spacesInsideBrackets;

    private SurroundingSpaces(int spacesBeforeColon, int spacesAfterColon, int spacesBeforeComma, int spacesAfterComma, int spacesInsideBrackets) {
        this.spacesBeforeColon = spacesBeforeColon;
        this.spacesAfterColon = spacesAfterColon;
        this.spacesBeforeComma = spacesBeforeComma;
        this.spacesAfterComma = spacesAfterComma;
        this.spacesInsideBrackets = spacesInsideBrackets;
    }

    /// The number of spaces before the colon.
    public int getSpacesBeforeColon() {
        return spacesBeforeColon;
    }

    /// The number of spaces after the colon.
    public int getSpacesAfterColon() {
        return spacesAfterColon;
    }

    /// The number of spaces before the comma.
    public int getSpacesBeforeComma() {
        return spacesBeforeComma;
    }

    /// The number of spaces after the comma.
    public int getSpacesAfterComma() {
        return spacesAfterComma;
    }

    /// The number of spaces inside brackets.
    public int getSpacesInsideBrackets() {
        return spacesInsideBrackets;
    }

    @Override
    public int hashCode() {
        return Objects.hash(spacesBeforeColon, spacesAfterColon, spacesBeforeComma, spacesAfterComma, spacesInsideBrackets);
    }

    @Override
    public boolean equals(Object obj) {
        return this == obj || obj instanceof SurroundingSpaces that
                && spacesBeforeColon == that.spacesBeforeColon
                && spacesAfterColon == that.spacesAfterColon
                && spacesBeforeComma == that.spacesBeforeComma
                && spacesAfterComma == that.spacesAfterComma
                && spacesInsideBrackets == that.spacesInsideBrackets;
    }

    @Override
    public String toString() {
        return "Surrounding[spacesBeforeColon=%d, spacesAfterColon=%d, spacesBeforeComma=%d, spacesAfterComma=%d, spacesInsideBrackets=%d]".formatted(
                spacesBeforeColon, spacesAfterColon, spacesBeforeComma, spacesAfterComma, spacesInsideBrackets);
    }

    /// Builder for [SurroundingSpaces].
    public static final class Builder {
        int spacesBeforeColon;
        int spacesAfterColon;
        int spacesBeforeComma;
        int spacesAfterComma;
        int spacesInsideBrackets;

        Builder() {
        }

        /// Builds a new [SurroundingSpaces] instance.
        @Contract(pure = true)
        public SurroundingSpaces build() {
            return new SurroundingSpaces(spacesBeforeColon, spacesAfterColon, spacesBeforeComma, spacesAfterComma, spacesInsideBrackets);
        }

        /// Sets the number of spaces before the colon.
        @Contract(value = "_ -> this", mutates = "this")
        public Builder setSpacesBeforeColon(int spacesBeforeColon) {
            if (spacesBeforeColon < 0) {
                throw new IllegalArgumentException("spacesBeforeColon must be non-negative");
            }
            this.spacesBeforeColon = spacesBeforeColon;
            return this;
        }

        /// Sets the number of spaces after the colon.
        @Contract(value = "_ -> this", mutates = "this")
        public Builder setSpacesAfterColon(int spacesAfterColon) {
            if (spacesAfterColon < 0) {
                throw new IllegalArgumentException("spacesAfterColon must be non-negative");
            }
            this.spacesAfterColon = spacesAfterColon;
            return this;
        }

        /// Sets the number of spaces before the comma.
        @Contract(value = "_ -> this", mutates = "this")
        public Builder setSpacesBeforeComma(int spacesBeforeComma) {
            if (spacesBeforeComma < 0) {
                throw new IllegalArgumentException("spacesBeforeComma must be non-negative");
            }
            this.spacesBeforeComma = spacesBeforeComma;
            return this;
        }

        @Contract(value = "_ -> this", mutates = "this")
        public Builder setSpacesAfterComma(int spacesAfterComma) {
            if (spacesAfterComma < 0) {
                throw new IllegalArgumentException("spacesAfterComma must be non-negative");
            }
            this.spacesAfterComma = spacesAfterComma;
            return this;
        }

        @Contract(value = "_ -> this", mutates = "this")
        public Builder setSpacesInsideBrackets(int spacesInsideBrackets) {
            if (spacesInsideBrackets < 0) {
                throw new IllegalArgumentException("spacesInsideBrackets must be non-negative");
            }
            this.spacesInsideBrackets = spacesInsideBrackets;
            return this;
        }
    }
}
