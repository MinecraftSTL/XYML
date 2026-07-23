/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2026 huangyuhui <huanghongxun2008@126.com> and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package space.minecraftstl.xyml.ui.swing.page.instances.management;

import org.jetbrains.annotations.NotNullByDefault;

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
    Path resolve(String instanceId) throws IOException;
}
