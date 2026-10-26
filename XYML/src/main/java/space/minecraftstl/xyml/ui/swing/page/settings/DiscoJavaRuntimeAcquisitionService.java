// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.settings;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.download.java.JavaPackageType;
import space.minecraftstl.xyml.download.java.disco.DiscoJavaDistribution;
import space.minecraftstl.xyml.download.java.disco.DiscoJavaRemoteVersion;
import space.minecraftstl.xyml.java.JavaRuntime;
import space.minecraftstl.xyml.task.Task;
import space.minecraftstl.xyml.util.platform.Platform;

import java.util.List;

/// Exposes lazy third-party Java discovery and installation without coupling Swing controls to Disco network tasks.
///
/// Choice-returning methods never imply a selected item. Callers must explicitly provide a distribution, package
/// type, and version before a network-backed operation can be created.
@NotNullByDefault
public interface DiscoJavaRuntimeAcquisitionService {
    /// Returns the exact platform targeted by this service.
    ///
    /// @return current system platform
    Platform platform();

    /// Returns locally known Disco distributions supporting the current platform.
    ///
    /// @return immutable supported distribution list in declaration order
    @Unmodifiable List<DiscoJavaDistribution> supportedDistributions();

    /// Returns non-JavaFX package types supported by one available distribution.
    ///
    /// @param distribution explicitly selected distribution
    /// @return immutable package type list in enum declaration order
    @Unmodifiable List<JavaPackageType> supportedPackageTypes(DiscoJavaDistribution distribution);

    /// Creates a stopped task that lazily fetches versions for one explicit distribution and package type.
    ///
    /// @param distribution explicitly selected distribution
    /// @param packageType explicitly selected non-JavaFX package type
    /// @return stopped task yielding an immutable newest-first version list
    Task<@Unmodifiable List<DiscoJavaRemoteVersion>> loadVersions(
            DiscoJavaDistribution distribution,
            JavaPackageType packageType);

    /// Derives the deterministic managed runtime name from an explicit selection.
    ///
    /// @param distribution explicitly selected distribution
    /// @param packageType explicitly selected non-JavaFX package type
    /// @param version explicitly selected remote version
    /// @return deterministic suggested managed-runtime name
    String suggestedInstallName(
            DiscoJavaDistribution distribution,
            JavaPackageType packageType,
            DiscoJavaRemoteVersion version);

    /// Validates one proposed name against current-platform syntax, containment, reservation, and collision rules.
    ///
    /// This local check performs no network access and is intended to gate the install command before it starts.
    ///
    /// @param name proposed managed-runtime name
    /// @return exact validation status
    JavaRuntimeInstallNameStatus validateInstallName(String name);

    /// Creates a stopped task that validates metadata, downloads, inspects, normalizes, and atomically installs Java.
    ///
    /// @param distribution explicitly selected distribution
    /// @param packageType explicitly selected non-JavaFX package type
    /// @param version explicitly selected remote version
    /// @param installName validated managed-runtime name chosen by the user
    /// @return stopped acquisition task
    Task<JavaRuntime> install(
            DiscoJavaDistribution distribution,
            JavaPackageType packageType,
            DiscoJavaRemoteVersion version,
            String installName);
}
