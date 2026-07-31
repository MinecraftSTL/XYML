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
package space.minecraftstl.xyml.ui.swing.page.instances.management.installers;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import space.minecraftstl.xyml.download.LibraryAnalyzer;

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
        public static StructureState fromAnalyzerStatus(LibraryAnalyzer.LibraryMark.LibraryStatus status) {
            return Objects.requireNonNull(status, "status") == LibraryAnalyzer.LibraryMark.LibraryStatus.CLEAR
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
