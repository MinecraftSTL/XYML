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
package space.minecraftstl.xyml.ui.swing.page.settings;

import org.jetbrains.annotations.NotNullByDefault;
import space.minecraftstl.xyml.java.JavaInfo;

import java.nio.file.Path;
import java.util.Objects;

/// Immutable result of validating a local Java runtime archive.
///
/// @param archiveFile normalized absolute path of the inspected archive
/// @param suggestedName archive root directory name offered to the user without automatic acceptance
/// @param javaHomeRelativePath slash-separated Java Home path below the archive root
/// @param javaInfo Java platform, version, and vendor metadata read from the archive release file
/// @param archiveSize exact archive byte length captured during stable inspection, or `-1` when unverified
/// @param sha256 lowercase SHA-256 of the complete archive, or an empty string when unverified
@NotNullByDefault
public record LocalJavaArchiveInspection(
        Path archiveFile,
        String suggestedName,
        String javaHomeRelativePath,
        JavaInfo javaInfo,
        long archiveSize,
        String sha256) {
    /// Normalizes the archive path and rejects absent or blank inspection data.
    public LocalJavaArchiveInspection {
        archiveFile = Objects.requireNonNull(archiveFile, "archiveFile").toAbsolutePath().normalize();
        suggestedName = Objects.requireNonNull(suggestedName, "suggestedName");
        if (suggestedName.isBlank()) {
            throw new IllegalArgumentException("suggestedName must not be blank");
        }
        javaHomeRelativePath = Objects.requireNonNull(javaHomeRelativePath, "javaHomeRelativePath");
        if (javaHomeRelativePath.isBlank()
                || javaHomeRelativePath.startsWith("/")
                || javaHomeRelativePath.endsWith("/")) {
            throw new IllegalArgumentException("javaHomeRelativePath must be a non-empty relative archive path");
        }
        javaInfo = Objects.requireNonNull(javaInfo, "javaInfo");
        sha256 = Objects.requireNonNull(sha256, "sha256");
        if (!((archiveSize == -1L && sha256.isEmpty())
                || (archiveSize >= 0L && isLowercaseSha256(sha256)))) {
            throw new IllegalArgumentException(
                    "archiveSize and sha256 must form either a verified fingerprint or the unverified sentinel");
        }
    }

    /// Returns whether this result carries a complete stable archive fingerprint.
    ///
    /// @return whether byte length and SHA-256 were captured by archive inspection
    public boolean hasVerifiedFingerprint() {
        return archiveSize >= 0L;
    }

    /// Checks that a digest is exactly 64 lowercase hexadecimal characters.
    ///
    /// @param digest candidate digest
    /// @return whether the digest is a canonical SHA-256 hexadecimal value
    private static boolean isLowercaseSha256(String digest) {
        if (digest.length() != 64) {
            return false;
        }
        for (int index = 0; index < digest.length(); index++) {
            char character = digest.charAt(index);
            if (!((character >= '0' && character <= '9')
                    || (character >= 'a' && character <= 'f'))) {
                return false;
            }
        }
        return true;
    }
}
