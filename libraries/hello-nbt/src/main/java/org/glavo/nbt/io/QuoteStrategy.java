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

import org.glavo.nbt.internal.snbt.QuoteStrategies;

/// Represents the quote strategy for SNBT.
///
/// When converting a Tag object to SNBT, for the value of string tags and the names of compound tag sub-tags,
/// this strategy is used to determine whether to use single quotes, double quotes, or no quotes.
///
/// @see SNBTCodec
/// @see SNBTCodec#getNameQuoteStrategy()
/// @see SNBTCodec#getValueQuoteStrategy()
public sealed interface QuoteStrategy permits QuoteStrategies.Always, QuoteStrategies.Smart, QuoteStrategies.WhenNeeded {

    /// Returns the default quote strategy for tag names.
    ///
    /// It is equivalent to [`whenNeeded()`][#whenNeeded()].
    static QuoteStrategy defaultNameStrategy() {
        return whenNeeded();
    }

    /// Returns the default quote strategy for tag values.
    ///
    /// It is equivalent to [`always()`][#always()].
    static QuoteStrategy defaultValueStrategy() {
        return always();
    }

    /// Returns a quote strategy that always uses double quotes.
    static QuoteStrategy always() {
        return QuoteStrategies.Always.DOUBLE_QUOTE;
    }

    /// Returns a quote strategy that always uses the specified quote character.
    ///
    /// @throws IllegalArgumentException if the quote character is not a valid quote character.
    static QuoteStrategy always(char quoteChar) {
        return switch (quoteChar) {
            case '"' -> QuoteStrategies.Always.DOUBLE_QUOTE;
            case '\'' -> QuoteStrategies.Always.SINGLE_QUOTE;
            default -> throw new IllegalArgumentException("Invalid quote char: " + quoteChar);
        };
    }

    /// Returns a quote strategy that always uses double quotes.
    static QuoteStrategy whenNeeded() {
        return QuoteStrategies.WhenNeeded.DOUBLE_QUOTE;
    }

    /// Returns a quote strategy that only uses the specified quote character when necessary.
    ///
    /// @throws IllegalArgumentException if the quote character is not a valid quote character.
    static QuoteStrategy whenNeeded(char quoteChar) {
        return switch (quoteChar) {
            case '"' -> QuoteStrategies.WhenNeeded.DOUBLE_QUOTE;
            case '\'' -> QuoteStrategies.WhenNeeded.SINGLE_QUOTE;
            default -> throw new IllegalArgumentException("Invalid quote char: " + quoteChar);
        };
    }

    /// Returns a "smart" quote strategy:
    ///
    /// - If `quoteByDefault` is true:
    ///   - If the input contains the preferred quote character, and not containing the other quote character, use the other quote character.
    ///   - Otherwise, use the preferred quote character.
    /// - If `quoteByDefault` is false:
    ///   - If the input can be written without quotes, do not use a quote character.
    ///   - If the input contains the preferred quote character, and not containing the other quote character, use the other quote character.
    ///   - Otherwise, use the preferred quote character.
    static QuoteStrategy smart(boolean quoteByDefault, char preferredQuoteChar) {
        if (preferredQuoteChar != '"' && preferredQuoteChar != '\'') {
            throw new IllegalArgumentException("Invalid quote char: " + preferredQuoteChar);
        }
        return new QuoteStrategies.Smart(quoteByDefault, preferredQuoteChar);
    }

    /// Returns the quote character for the given value.
    ///
    /// If the value should be written without quotes, this method returns `\0`;
    /// otherwise, it returns the quote character.
    char getQuoteChar(String value);
}
