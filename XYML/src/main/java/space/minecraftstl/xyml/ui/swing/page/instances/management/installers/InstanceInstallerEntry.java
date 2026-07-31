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
import space.minecraftstl.xyml.download.LibraryAnalyzer;
import space.minecraftstl.xyml.ui.swing.page.downloads.loaders.GameLoaderKind;

import java.util.Objects;

/// Immutable description of one recognized loader or loader-adjacent component installed by an instance.
///
/// The status comes directly from [LibraryAnalyzer] and lets a future Swing surface distinguish an
/// explicit XYML patch from a library discovered in third-party metadata before offering destructive actions.
///
/// @param kind recognized loader catalog kind
/// @param version detected installed version text
/// @param status certainty of the detected library mapping
@NotNullByDefault
public record InstanceInstallerEntry(
        GameLoaderKind kind,
        String version,
        LibraryAnalyzer.LibraryMark.LibraryStatus status) {
    /// Validates one immutable recognized installer entry.
    public InstanceInstallerEntry {
        kind = Objects.requireNonNull(kind, "kind");
        version = Objects.requireNonNull(version, "version");
        status = Objects.requireNonNull(status, "status");
        if (version.isBlank()) {
            throw new IllegalArgumentException("version must not be blank");
        }
    }
}
