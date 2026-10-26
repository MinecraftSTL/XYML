// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.instances.management.installers;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/// Immutable description of a removable library not represented by [GameLoaderKind].
///
/// This preserves installer behavior for third-party patches while explicitly
/// distinguishing a structure that XYML can see clearly from an external or uncertain discovery.
/// It deliberately does not infer a loader kind for unknown Core library identifiers.
///
/// @param libraryId exact Core patch identifier
/// @param version detected display version, when the resolved metadata exposes one
/// @param structureState structural certainty relevant to destructive-action presentation
@NotNullByDefault
public record InstanceOtherLibraryEntry(
        String libraryId,
        @Nullable String version,
        StructureState structureState) {
    /// Validates one stable third-party library entry without normalizing visible text.
    public InstanceOtherLibraryEntry {
        libraryId = requireNonBlank(libraryId, "libraryId");
        if (version != null && version.isBlank()) {
            throw new IllegalArgumentException("version must not be blank when supplied");
        }
        structureState = Objects.requireNonNull(structureState, "structureState");
    }

    /// Represents whether Core can describe the library's mutation structure unambiguously.
    @NotNullByDefault
    public enum StructureState {
        /// The library comes from an explicit version patch and has a clear removal structure.
        CLEAR,

        /// The library was discovered from third-party or otherwise uncertain resolved metadata.
        EXTERNALLY_UNCERTAIN;

        /// Normalizes Core's detailed analysis status to the two presentation states used by management UI.
        ///
        /// @param status raw Core analyzer status
        /// @return [CLEAR] only for a clear explicit patch; otherwise [EXTERNALLY_UNCERTAIN]
        public static StructureState fromAnalyzerStatus(InstallerStructureStatus status) {
            return Objects.requireNonNull(status, "status") == InstallerStructureStatus.CLEAR
                    ? CLEAR
                    : EXTERNALLY_UNCERTAIN;
        }
    }

    /// Validates an exact non-blank Core patch identifier.
    ///
    /// @param value candidate identifier
    /// @param name parameter name used in diagnostics
    /// @return exact non-blank identifier
    private static String requireNonBlank(String value, String name) {
        String candidate = Objects.requireNonNull(value, name);
        if (candidate.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return candidate;
    }
}
