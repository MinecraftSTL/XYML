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
