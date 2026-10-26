// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.gradle.pack;

import org.jetbrains.annotations.NotNullByDefault;

/// Debian packaging metadata for one XYML release type.
///
/// The package name, installed command, desktop file, and alternatives
/// priority are intentionally centralized here so `CreateDeb` can stay focused
/// on archive layout instead of duplicating channel-specific branching.
@NotNullByDefault
public enum ReleaseType {
    /// Stable releases use three decimal components and win the generic Debian command alias.
    STABLE("stable", 3, "xyml", "XYML", 400),

    /// Public beta releases use four decimal components.
    BETA("beta", 4, "xyml-beta", "XYML (Beta)", 300),

    /// Internal alpha releases use five decimal components.
    ALPHA("alpha", 5, "xyml-alpha", "XYML (Alpha)", 200),

    /// Development releases use six decimal components.
    DEV("dev", 6, "xyml-dev", "XYML (Dev)", 100);

    /// Canonical lowercase release-channel name.
    private final String name;

    /// Exact number of decimal components required by this channel.
    private final int versionComponentCount;

    /// Debian package identifier.
    private final String packageName;

    /// Human-readable Debian application name.
    private final String displayName;

    /// Debian alternatives priority for the generic `xyml` command.
    private final int alternativesPriority;

    /// Creates the immutable metadata for one release channel.
    ///
    /// @param name canonical lowercase channel name
    /// @param versionComponentCount exact decimal version component count
    /// @param packageName Debian package identifier
    /// @param displayName human-readable application name
    /// @param alternativesPriority Debian alternatives priority
    ReleaseType(
            String name,
            int versionComponentCount,
            String packageName,
            String displayName,
            int alternativesPriority) {
        this.name = name;
        this.versionComponentCount = versionComponentCount;
        this.packageName = packageName;
        this.displayName = displayName;
        this.alternativesPriority = alternativesPriority;
    }

    /// Resolves a canonical channel name.
    ///
    /// @param name channel name
    /// @return matching release type
    /// @throws IllegalArgumentException when the name is not one of `stable`, `beta`, `alpha`, or `dev`
    public static ReleaseType fromName(String name) {
        for (ReleaseType releaseType : values()) {
            if (releaseType.name.equals(name)) {
                return releaseType;
            }
        }
        throw new IllegalArgumentException("Unsupported release channel: " + name);
    }

    /// Returns the canonical lowercase channel name.
    ///
    /// @return channel name
    public String getName() {
        return name;
    }

    /// Returns the exact number of decimal components required by this channel.
    ///
    /// @return version component count
    public int getVersionComponentCount() {
        return versionComponentCount;
    }

    /// Debian package name written into `control` and used in the output filename.
    ///
    /// @return Debian package name
    public String getPackageName() {
        return packageName;
    }

    /// Returns the channel-specific application name.
    ///
    /// @return display name
    public String getDisplayName() {
        return displayName;
    }

    /// Priority used when registering the generic `xyml` alias.
    ///
    /// More stable channels deliberately have higher priorities.
    ///
    /// @return Debian alternatives priority
    public int getAlternativesPriority() {
        return alternativesPriority;
    }
}
