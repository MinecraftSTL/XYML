// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.game.analyzer;

import org.jetbrains.annotations.NotNullByDefault;

/// Stable identifiers for the deliberately limited launch-log causes.
@NotNullByDefault
public enum ResultID {
    /// HotSpot terminated while compiling code with the C2 optimizing compiler.
    C2_COMPILER,

    /// A client-only mod or class was loaded by a dedicated server.
    CLIENT_MOD_ON_SERVER,

    /// Windows legacy code-page handling failed around a non-ASCII launch path.
    CODE_PAGE,

    /// A 32-bit Java runtime could not reserve the configured heap.
    JRE_32BIT,

    /// The selected Java major version conflicts with verified launch evidence.
    JRE_VERSION,

    /// The operating system could not commit enough physical or virtual memory.
    VIRTUAL_MEMORY,

    /// Forge reported a required mod dependency whose actual version is missing.
    FORGE_MISSING_DEPENDENCY,

    /// Fabric reported a hard missing mod dependency.
    FABRIC_MISSING_DEPENDENCY,

    /// A legacy Minecraft launch on Java 8 requires the Legacy Java Fixer compatibility mod.
    LEGACY_JAVA_FIXER,

    /// Fabric reported an explicit Indium, Iris, or Iris Flywheel compatibility failure.
    RENDERER_MOD_COMPATIBILITY
}
