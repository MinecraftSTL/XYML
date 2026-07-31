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
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.modpack.mcbbs.McbbsModpackManifest;

import java.util.List;
import java.util.Objects;

/// Immutable user-supplied metadata shared by the supported modpack exporters.
///
/// Optional textual values use the empty string so nullability does not leak into the Swing workflow.
/// The launcher-bundling option from the removed JavaFX exporter is deliberately absent because the
/// self-contained jlink/jpackage distribution is not a single embeddable launcher JAR.
///
/// @param name exported modpack name
/// @param version exported modpack version
/// @param author exported author, or an empty string when the selected format does not use it
/// @param description exported description, or an empty string
/// @param fileApi server file API, or an empty string when unused
/// @param url project introduction URL, or an empty string when unused
/// @param forceUpdate whether compatible manifests should request forced updates
/// @param minMemory minimum heap size in MiB
/// @param supportedJavaVersions immutable supported Java-major-version list
/// @param launchArguments immutable game argument text
/// @param javaArguments immutable JVM argument text
/// @param authlibInjectorServer selected authentication server URL, or an empty string
/// @param origins immutable MCBBS origin metadata
@NotNullByDefault
public record ModpackExportMetadata(
        String name,
        String version,
        String author,
        String description,
        String fileApi,
        String url,
        boolean forceUpdate,
        int minMemory,
        @Unmodifiable List<Integer> supportedJavaVersions,
        String launchArguments,
        String javaArguments,
        String authlibInjectorServer,
        @Unmodifiable List<McbbsModpackManifest.Origin> origins) {

    /// Defensively copies collection components and rejects invalid common metadata.
    public ModpackExportMetadata {
        name = requireNonBlank(name, "name");
        version = requireNonBlank(version, "version");
        author = Objects.requireNonNull(author, "author");
        description = Objects.requireNonNull(description, "description");
        fileApi = Objects.requireNonNull(fileApi, "fileApi");
        url = Objects.requireNonNull(url, "url");
        if (minMemory < 0) {
            throw new IllegalArgumentException("minMemory must not be negative");
        }
        supportedJavaVersions = List.copyOf(supportedJavaVersions);
        launchArguments = Objects.requireNonNull(launchArguments, "launchArguments");
        javaArguments = Objects.requireNonNull(javaArguments, "javaArguments");
        authlibInjectorServer = Objects.requireNonNull(authlibInjectorServer, "authlibInjectorServer");
        origins = List.copyOf(origins);
    }

    /// Creates metadata containing only the fields every export format requires.
    ///
    /// @param name exported modpack name
    /// @param version exported modpack version
    /// @return immutable minimal metadata with empty optional values
    public static ModpackExportMetadata minimal(String name, String version) {
        return new ModpackExportMetadata(
                name,
                version,
                "",
                "",
                "",
                "",
                false,
                0,
                List.of(),
                "",
                "",
                "",
                List.of());
    }

    /// Requires one non-null, non-blank metadata value.
    ///
    /// @param value candidate value
    /// @param fieldName diagnostic field name
    /// @return original non-blank value
    private static String requireNonBlank(String value, String fieldName) {
        Objects.requireNonNull(value, fieldName);
        if (value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value;
    }
}
