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

import org.glavo.nbt.internal.snbt.SNBTParser;
import org.glavo.nbt.internal.snbt.SNBTWriter;
import org.glavo.nbt.tag.Tag;
import org.jetbrains.annotations.Contract;

import java.io.IOException;
import java.nio.CharBuffer;
import java.util.Objects;

/// The codec for reading and writing Stringified NBT data.
///
/// Each SNBTCodec instance is immutable and thread-safe.
///
/// # Getting SNBTCodec Instances
///
/// SNBTCodec provides two factory methods to obtain SNBTCodec instances:
///
/// - [SNBTCodec#of()] returns the default [SNBTCodec] with pretty formatting.
/// - [SNBTCodec#ofCompact()] returns a [SNBTCodec] with compact formatting.
///
/// They are identical when reading SNBT, but when writing SNBT, the former adds line breaks and indentation, while the latter does not.
///
/// SNBTCodec also provides many options to control the output format, such as indentation, line breaks, quotes, etc.
/// After obtaining an SNBTCodec instance, you can use various `withXxx` methods to modify these options:
///
/// - [SNBTCodec#withLineBreakStrategy(LineBreakStrategy)]:
/// - [SNBTCodec#withIndentation(String)]
/// - [SNBTCodec#withSurroundingSpaces(SurroundingSpaces)]
/// - [SNBTCodec#withEscapeStrategy(EscapeStrategy)]
/// - [SNBTCodec#withNameQuoteStrategy(QuoteStrategy)]
/// - [SNBTCodec#withValueQuoteStrategy(QuoteStrategy)]
///
/// # Reading and Writing SNBT
///
/// SNBTCodec supports reading and writing Stringified NBT data:
///
/// ```java
/// var codec = SNBTCodec.of();
///
/// String snbt;
///
/// // Read from a CharSequence
/// snbt = codec.readTag("...");
///
/// // Read from a Readable
/// try (var reader = Files.newBufferedReader(Path.of("/path/to/file"))) {
///     snbt = codec.readTag(reader);
/// }
///
/// // Write to a Appendable
/// codec.writeTag(new StringBuilder(), tag);
/// ```
///
/// You can also convert a Tag to an SNBT formatted string like this:
///
/// ```java
/// String snbt = codec.toString(tag);
/// ```
///
/// @see <a href="https://minecraft.wiki/w/NBT_format#SNBT_format">NBT format - Minecraft Wiki</a>
public final class SNBTCodec {

    private static final SNBTCodec COMPACT = new SNBTCodec(
            LineBreakStrategy.never(),
            "", // No indentation
            SurroundingSpaces.COMPACT,
            EscapeStrategy.defaultStrategy(),
            QuoteStrategy.defaultNameStrategy(),
            QuoteStrategy.defaultValueStrategy()
    );

    private static final SNBTCodec PRETTY = new SNBTCodec(
            LineBreakStrategy.defaultStrategy(),
            "    ", // 4 spaces
            SurroundingSpaces.PRETTY,
            EscapeStrategy.defaultStrategy(),
            QuoteStrategy.defaultNameStrategy(),
            QuoteStrategy.defaultValueStrategy()
    );

    public static SNBTCodec of() {
        return PRETTY;
    }

    /// Returns a [SNBTCodec] with compact formatting.
    public static SNBTCodec ofCompact() {
        return COMPACT;
    }

    private final LineBreakStrategy lineBreakStrategy;
    private final String indentation;
    private final SurroundingSpaces surroundingSpaces;
    private final EscapeStrategy escapeStrategy;
    private final QuoteStrategy nameQuoteStrategy;
    private final QuoteStrategy valueQuoteStrategy;

    private SNBTCodec(LineBreakStrategy lineBreakStrategy, String indentation, SurroundingSpaces surroundingSpaces, EscapeStrategy escapeStrategy, QuoteStrategy nameQuoteStrategy, QuoteStrategy valueQuoteStrategy) {
        this.lineBreakStrategy = lineBreakStrategy;
        this.indentation = indentation;
        this.surroundingSpaces = surroundingSpaces;
        this.escapeStrategy = escapeStrategy;
        this.nameQuoteStrategy = nameQuoteStrategy;
        this.valueQuoteStrategy = valueQuoteStrategy;
    }

    /// Returns the line break strategy for all parent tags.
    ///
    /// @see #withLineBreakStrategy(LineBreakStrategy)
    /// @see LineBreakStrategy
    @Contract(pure = true)
    public LineBreakStrategy getLineBreakStrategy() {
        return lineBreakStrategy;
    }

    /// Returns a new codec with the specified line break strategy for all parent tags.
    ///
    /// @see #getLineBreakStrategy()
    /// @see LineBreakStrategy
    @Contract(value = "_ -> new", pure = true)
    public SNBTCodec withLineBreakStrategy(LineBreakStrategy strategy) {
        Objects.requireNonNull(strategy, "strategy");
        return new SNBTCodec(strategy, indentation, surroundingSpaces, escapeStrategy, nameQuoteStrategy, valueQuoteStrategy);
    }

    /// Returns the indentation string before each line.
    @Contract(pure = true)
    public String getIndentation() {
        return indentation;
    }

    /// Returns a new codec with the specified indentation string.
    ///
    /// The indentation string must be a sequence of spaces or tabs.
    ///
    /// @throws IllegalArgumentException if the indentation string is not a sequence of spaces or tabs.
    /// @see #getIndentation()
    @Contract(value = "_ -> new", pure = true)
    public SNBTCodec withIndentation(String indentation) {
        for (int i = 0; i < indentation.length(); i++) { // implicit null check of indentation
            char ch = indentation.charAt(i);
            if (ch != ' ' && ch != '\t') {
                throw new IllegalArgumentException("Indentation must be a sequence of spaces or tabs");
            }
        }

        return new SNBTCodec(lineBreakStrategy, indentation, surroundingSpaces, escapeStrategy, nameQuoteStrategy, valueQuoteStrategy);

    }

    /// Returns a new codec with the specified indentation.
    ///
    /// @param spaces The number of spaces for indentation.
    /// @throws IllegalArgumentException if the indentation is not a positive integer.
    /// @see #getIndentation()
    /// @see #withIndentation(String)
    @Contract(value = "_ -> new", pure = true)
    public SNBTCodec withIndentation(int spaces) {
        return new SNBTCodec(lineBreakStrategy, " ".repeat(spaces), surroundingSpaces, escapeStrategy, nameQuoteStrategy, valueQuoteStrategy);
    }

    /// Returns the surrounding spaces for SNBT.
    @Contract(pure = true)
    public SurroundingSpaces getSurroundingSpaces() {
        return surroundingSpaces;
    }

    /// Returns a new codec with the specified surrounding spaces.
    @Contract(value = "_ -> new", pure = true)
    public SNBTCodec withSurroundingSpaces(SurroundingSpaces surroundingSpaces) {
        Objects.requireNonNull(surroundingSpaces, "surroundingSpaces");
        return new SNBTCodec(lineBreakStrategy, indentation, surroundingSpaces, escapeStrategy, nameQuoteStrategy, valueQuoteStrategy);
    }

    /// Returns the escape strategy for SNBT.
    ///
    /// @see #withEscapeStrategy(EscapeStrategy)
    /// @see EscapeStrategy
    @Contract(pure = true)
    public EscapeStrategy getEscapeStrategy() {
        return escapeStrategy;
    }

    /// Returns a new codec with the specified escape strategy.
    ///
    /// @see #getEscapeStrategy()
    /// @see EscapeStrategy
    @Contract(value = "_ -> new", pure = true)
    public SNBTCodec withEscapeStrategy(EscapeStrategy escapeStrategy) {
        Objects.requireNonNull(escapeStrategy, "escapeStrategy");
        return new SNBTCodec(lineBreakStrategy, indentation, surroundingSpaces, escapeStrategy, nameQuoteStrategy, valueQuoteStrategy);
    }

    /// Returns the quote strategy for SNBT tag names.
    ///
    /// @see #withNameQuoteStrategy(QuoteStrategy)
    /// @see QuoteStrategy
    @Contract(pure = true)
    public QuoteStrategy getNameQuoteStrategy() {
        return nameQuoteStrategy;
    }

    /// Returns a new codec with the specified quote strategy for tag names.
    ///
    /// @see #getNameQuoteStrategy()
    /// @see QuoteStrategy
    @Contract(value = "_ -> new", pure = true)
    public SNBTCodec withNameQuoteStrategy(QuoteStrategy quoteStrategy) {
        Objects.requireNonNull(quoteStrategy, "quoteStrategy");
        return new SNBTCodec(lineBreakStrategy, indentation, surroundingSpaces, escapeStrategy, quoteStrategy, valueQuoteStrategy);
    }

    /// Returns the quote strategy for SNBT tag values.
    ///
    /// @see #withValueQuoteStrategy(QuoteStrategy)
    /// @see QuoteStrategy
    @Contract(pure = true)
    public QuoteStrategy getValueQuoteStrategy() {
        return valueQuoteStrategy;
    }

    /// Returns a new codec with the specified quote strategy for tag values.
    ///
    /// @see #getValueQuoteStrategy()
    /// @see QuoteStrategy
    @Contract(value = "_ -> new", pure = true)
    public SNBTCodec withValueQuoteStrategy(QuoteStrategy quoteStrategy) {
        Objects.requireNonNull(quoteStrategy, "quoteStrategy");
        return new SNBTCodec(lineBreakStrategy, indentation, surroundingSpaces, escapeStrategy, nameQuoteStrategy, quoteStrategy);
    }

    /// Reads a NBT tag from the Stringified NBT data.
    ///
    /// @throws IOException if the input is not a valid Stringified NBT data.
    @Contract(pure = true)
    public Tag readTag(CharSequence input) throws IOException {
        return readTag(input, 0, input.length());
    }

    /// Reads a NBT tag from the Stringified NBT data.
    ///
    /// @throws IndexOutOfBoundsException if the range is out of bounds.
    /// @throws IOException               if the input is not a valid Stringified NBT data.
    @Contract(pure = true)
    public Tag readTag(CharSequence input, int startInclusive, int endExclusive) throws IOException {
        Tag tag;
        try {
            tag = new SNBTParser(input, startInclusive, endExclusive).nextTag();
        } catch (IllegalArgumentException e) {
            throw new IOException(e);
        }
        if (tag == null) {
            throw new IOException("Unexpected end of input");
        }
        return tag;
    }

    /// Reads a NBT tag from the Stringified NBT data.
    ///
    /// @throws IOException if an I/O error occurs.
    @Contract(mutates = "param1")
    public Tag readTag(Readable readable) throws IOException {
        var builder = new StringBuilder();

        CharBuffer buffer = CharBuffer.allocate(8192);
        while (readable.read(buffer) != -1) {
            buffer.flip();
            builder.append(buffer);
            buffer.clear();
        }
        try {
            return readTag(builder);
        } catch (IllegalArgumentException e) {
            throw new IOException(e);
        }
    }

    /// Writes a NBT tag as Stringified NBT to the given [Appendable].
    ///
    /// @throws IOException if an I/O error occurs.
    @Contract(mutates = "param1")
    public void writeTag(Appendable appendable, Tag tag) throws IOException {
        new SNBTWriter<>(this, appendable).writeTag(tag);
    }

    /// Writes a NBT tag as Stringified NBT to the given [StringBuilder].
    @Contract(mutates = "param1")
    public void writeTag(StringBuilder builder, Tag tag) {
        try {
            writeTag((Appendable) builder, tag);
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    /// Returns the Stringified NBT of the tag.
    public String toString(Tag tag) {
        var builder = new StringBuilder();
        writeTag(builder, tag);
        return builder.toString();
    }

    @Override
    public boolean equals(Object o) {
        return this == o || o instanceof SNBTCodec that
                && this.lineBreakStrategy.equals(that.lineBreakStrategy)
                && this.indentation.equals(that.indentation)
                && this.surroundingSpaces.equals(that.surroundingSpaces)
                && this.escapeStrategy.equals(that.escapeStrategy)
                && this.nameQuoteStrategy.equals(that.nameQuoteStrategy)
                && this.valueQuoteStrategy.equals(that.valueQuoteStrategy);
    }

    @Override
    public int hashCode() {
        return Objects.hash(lineBreakStrategy, indentation, surroundingSpaces, escapeStrategy, nameQuoteStrategy, valueQuoteStrategy);
    }

    @Override
    public String toString() {
        return "SNBTCodec[lineBreakStrategy=%s, indentation=%s, surroundingSpaces=%s, escapeStrategy=%s, nameQuoteStrategy=%s, valueQuoteStrategy=%s]".formatted(
                lineBreakStrategy, indentation, surroundingSpaces, escapeStrategy, nameQuoteStrategy, valueQuoteStrategy
        );
    }
}
