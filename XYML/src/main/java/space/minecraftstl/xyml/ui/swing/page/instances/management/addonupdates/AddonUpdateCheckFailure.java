// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.instances.management.addonupdates;

import org.jetbrains.annotations.NotNullByDefault;

import java.nio.file.Path;
import java.util.Objects;

/// A local add-on whose update metadata could not be checked from any configured source.
///
/// @param fileName stable local display name
/// @param localFile exact locally installed file or directory
/// @param detail concise source failure summary
@NotNullByDefault
public record AddonUpdateCheckFailure(String fileName, Path localFile, String detail) {
    /// Validates immutable failure presentation data.
    public AddonUpdateCheckFailure {
        fileName = Objects.requireNonNull(fileName, "fileName");
        localFile = Objects.requireNonNull(localFile, "localFile").toAbsolutePath().normalize();
        detail = Objects.requireNonNull(detail, "detail");
    }
}
