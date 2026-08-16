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

import org.glavo.nbt.internal.snbt.EscapeStrategies;

/// Strategy for escaping characters in SNBT.
///
/// When converting a Tag object to SNBT, this strategy is used to determine which characters need to be escaped,
/// and how to escape them.
///
/// @see SNBTCodec
/// @see SNBTCodec#getEscapeStrategy()
/// @see SNBTCodec#withEscapeStrategy(EscapeStrategy)
public sealed interface EscapeStrategy permits EscapeStrategies {

    /// Returns the default escape strategy.
    static EscapeStrategy defaultStrategy() {
        return EscapeStrategies.DEFAULT;
    }

    /// Returns an escape strategy that escapes all non-ASCII characters.
    static EscapeStrategy escapeNotAscii() {
        return EscapeStrategies.NOT_ASCII;
    }
}
