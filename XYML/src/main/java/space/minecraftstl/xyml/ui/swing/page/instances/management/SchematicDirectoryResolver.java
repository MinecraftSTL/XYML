// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.instances.management;

import org.jetbrains.annotations.NotNullByDefault;
import space.minecraftstl.xyml.game.GameInstanceID;

import java.io.IOException;
import java.nio.file.Path;

/// Resolves the schematic root for one stable instance away from the Swing event dispatch thread.
///
/// Implementations may perform blocking repository or file-system work. Callers must therefore
/// invoke them through an asynchronous executor that never runs submitted resolution work inline
/// on the Swing event dispatch thread.
@FunctionalInterface
@NotNullByDefault
public interface SchematicDirectoryResolver {
    /// Resolves the schematic root for an exact instance identifier.
    ///
    /// @param instanceId stable repository instance identifier
    /// @return schematic navigation root
    /// @throws IOException when repository or file-system state cannot resolve the root
    Path resolve(GameInstanceID instanceId) throws IOException;
}
