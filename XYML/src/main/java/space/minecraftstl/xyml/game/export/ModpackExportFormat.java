// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

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
