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
package space.minecraftstl.xyml.game.export;

import org.jetbrains.annotations.NotNullByDefault;

/// Identifies one launcher-supported modpack archive format.
@NotNullByDefault
public enum ModpackExportFormat {
    /// Exports the MCBBS-compatible dual-manifest ZIP format.
    MCBBS(".zip"),

    /// Exports a MultiMC-compatible instance ZIP format.
    MULTIMC(".zip"),

    /// Exports the XYML server-pack ZIP format.
    SERVER(".zip"),

    /// Exports the Modrinth `.mrpack` format without remote-file discovery.
    MODRINTH(".mrpack");

    /// Conventional output suffix for this archive format.
    private final String fileSuffix;

    /// Creates one format with its conventional output suffix.
    ModpackExportFormat(String fileSuffix) {
        this.fileSuffix = fileSuffix;
    }

    /// Returns the conventional output suffix, including the leading dot.
    public String fileSuffix() {
        return fileSuffix;
    }
}
