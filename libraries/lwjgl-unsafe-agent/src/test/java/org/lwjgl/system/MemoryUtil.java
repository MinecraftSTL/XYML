/*
 * Copyright 2026 MinecraftSTL
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
// Added by MinecraftSTL in 2026 for process-level agent verification.
package org.lwjgl.system;

import org.jetbrains.annotations.NotNullByDefault;

/// Minimal target whose supported methods must be replaced before they execute.
@NotNullByDefault
public final class MemoryUtil {
    /// Prevents construction of the static fixture class.
    private MemoryUtil() {
    }

    /// Fails unless the agent replaces this method with a direct native-memory read.
    ///
    /// @param address native address to read
    /// @return never returns without transformation
    public static int memGetInt(long address) {
        throw new AssertionError("memGetInt was not transformed");
    }

    /// Fails unless the agent replaces this method with a direct native-memory write.
    ///
    /// @param address native address to write
    /// @param value value to write
    public static void memPutInt(long address, int value) {
        throw new AssertionError("memPutInt was not transformed");
    }
}
