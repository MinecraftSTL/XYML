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
// Modified by MinecraftSTL in 2026 for the XYML namespace and monorepo build.
package space.minecraftstl.xyml.library.nbt.internal.snbt;

import space.minecraftstl.xyml.library.nbt.io.LineBreakStrategy;
import space.minecraftstl.xyml.library.nbt.tag.ArrayTag;
import space.minecraftstl.xyml.library.nbt.tag.CompoundTag;
import space.minecraftstl.xyml.library.nbt.tag.ListTag;
import space.minecraftstl.xyml.library.nbt.tag.ParentTag;

public record LineBreakStrategyImpl(
        long compoundThreshold,
        long listThreshold,
        long arrayThreshold
) implements LineBreakStrategy {

    public static final LineBreakStrategyImpl DEFAULT = new LineBreakStrategyImpl(2, 2, Long.MAX_VALUE);

    public static final LineBreakStrategyImpl NEVER = new LineBreakStrategyImpl(Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE);

    @Override
    public boolean shouldBreakLines(ParentTag<?> tag) {
        if (tag instanceof CompoundTag compoundTag) {
            return compoundTag.size() >= compoundThreshold;
        } else if (tag instanceof ListTag<?> listTag) {
            return listTag.size() >= listThreshold;
        } else if (tag instanceof ArrayTag<?, ?, ?, ?> arrayTag) {
            return arrayTag.size() >= arrayThreshold;
        } else {
            throw new AssertionError("Unexpected tag: " + tag);
        }
    }
}
