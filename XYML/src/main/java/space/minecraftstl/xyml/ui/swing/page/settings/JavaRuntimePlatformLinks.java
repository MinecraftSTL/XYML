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
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.util.platform.Platform;

import java.net.URI;
import java.util.List;
import java.util.Objects;

/// Resolves the launcher-maintained external Java download choices for exceptional Linux architectures.
///
/// The helper is deliberately free of desktop integration so its platform matrix remains deterministic in tests.
@NotNullByDefault
final class JavaRuntimePlatformLinks {
    /// Product page for the Banshan JDK 8 RISC-V build retained from the previous Java download page.
    private static final URI BANSHAN_JDK_8 = URI.create("https://www.zthread.cn/#product");

    /// Loongnix JDK 21 page for the Linux LoongArch old-world ABI.
    private static final URI LOONGNIX_JDK_21 = URI.create(
            "https://www.loongnix.cn/zh/api/java/downloads-jdk21/index.html");

    /// Prevents construction of this platform-policy helper.
    private JavaRuntimePlatformLinks() {
    }

    /// Returns the immutable external choices applicable to one exact platform.
    ///
    /// @param platform platform represented by the acquisition page
    /// @return immutable external-link choices in display order
    static @Unmodifiable List<Link> forPlatform(Platform platform) {
        Platform target = Objects.requireNonNull(platform, "platform");
        if (target.equals(Platform.LINUX_RISCV64)) {
            return List.of(new Link("java.download.banshanjdk-8", BANSHAN_JDK_8));
        }
        if (target.equals(Platform.LINUX_LOONGARCH64_OW)) {
            return List.of(new Link("download.external_link", LOONGNIX_JDK_21));
        }
        return List.of();
    }

    /// Describes one localized external Java download command.
    ///
    /// @param labelKey localization key for the visible command
    /// @param uri validated HTTPS destination
    @NotNullByDefault
    record Link(String labelKey, URI uri) {
        /// Validates the immutable localized link description.
        Link {
            Objects.requireNonNull(labelKey, "labelKey");
            URI target = Objects.requireNonNull(uri, "uri");
            if (!"https".equalsIgnoreCase(target.getScheme()) || target.getHost() == null) {
                throw new IllegalArgumentException("External Java download link must use HTTPS");
            }
        }
    }
}
