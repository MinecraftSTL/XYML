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

import org.glavo.nbt.internal.snbt.LineBreakStrategyImpl;
import org.glavo.nbt.tag.ArrayTag;
import org.glavo.nbt.tag.CompoundTag;
import org.glavo.nbt.tag.ListTag;
import org.glavo.nbt.tag.ParentTag;

/// Strategy for breaking lines in SNBT.
///
/// When converting a Tag object to SNBT, this strategy is used to determine whether to break lines between the child tags
/// of a [parent tag][ParentTag].
///
/// @see SNBTCodec
/// @see SNBTCodec#getLineBreakStrategy()
/// @see SNBTCodec#withLineBreakStrategy(LineBreakStrategy)
public sealed interface LineBreakStrategy permits org.glavo.nbt.internal.snbt.LineBreakStrategyImpl {

    /// Returns the default line break strategy:
    ///
    /// - For [CompoundTag], break lines if the size is greater than 1;
    /// - For [ListTag], break lines if the size is greater than 1;
    /// - For [ArrayTag], never break lines.
    static LineBreakStrategy defaultStrategy() {
        return LineBreakStrategyImpl.DEFAULT;
    }

    /// Returns a line break strategy that never breaks lines.
    static LineBreakStrategy never() {
        return LineBreakStrategyImpl.NEVER;
    }

    /// Returns true if the elements of the tag need to be broken into separate lines.
    boolean shouldBreakLines(ParentTag<?> tag);
}
