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
package space.minecraftstl.xyml.ui.swing.page.instances;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import space.minecraftstl.xyml.addon.mod.ModLoaderType;
import space.minecraftstl.xyml.game.GameInstanceID;

import java.util.Objects;

/// Captures the analyzed catalog filters of one installed instance.
///
/// Add-on catalogs derived from an instance forward this context so their version and loader filters match the
/// instance. Unresolved values remain `null` and callers keep their own defaults instead of reporting a failure.
///
/// @param instanceId stable repository instance identifier
/// @param gameVersion analyzed Minecraft version, or `null` when unavailable
/// @param modLoader primary supported mod loader, or `null` when unavailable or unsupported
@NotNullByDefault
public record InstanceAddonContext(
        GameInstanceID instanceId,
        @Nullable String gameVersion,
        @Nullable ModLoaderType modLoader) {
    /// Normalizes one analyzed instance context.
    public InstanceAddonContext {
        Objects.requireNonNull(instanceId, "instanceId");
    }
}
