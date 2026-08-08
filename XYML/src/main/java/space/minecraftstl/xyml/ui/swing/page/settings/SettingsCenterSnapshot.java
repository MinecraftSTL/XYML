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
import space.minecraftstl.xyml.setting.DownloadSource;
import space.minecraftstl.xyml.setting.EnumCommonDirectory;
import space.minecraftstl.xyml.setting.ProxyType;
import space.minecraftstl.xyml.util.i18n.SupportedLocale;

import java.util.Objects;

/// Immutable launcher preferences rendered by [SettingsCenterPanel].
///
/// @param language selected launcher language
/// @param acceptPreviewUpdates whether preview releases are eligible for update checks
/// @param disableAutomaticUpdatePrompt whether available updates remain non-modal
/// @param disableAprilFools whether seasonal launcher behavior is disabled
/// @param commonDirectoryType resolved common-directory selection mode
/// @param commonDirectory custom common-directory value, or an empty string when unused
/// @param resolvedCommonDirectory effective common directory shown to the user
/// @param autoDownloadThreads whether the launcher determines download concurrency
/// @param downloadThreads positive manual download concurrency
/// @param versionListSource selected game-version list source
/// @param fileDownloadSource selected file-download source
/// @param defaultAddonSource selected Mod or resource-pack catalogue source ID
/// @param proxyType selected network proxy strategy
/// @param proxyHost custom proxy host, or an empty string when unused
/// @param proxyPort custom proxy port
/// @param proxyAuthenticationEnabled whether proxy credentials are enabled
/// @param proxyUsername proxy authentication username, or an empty string when unused
/// @param proxyPassword proxy authentication password, or an empty string when unused
/// @param mcpEnabled whether the local AI MCP server is enabled
/// @param mcpPort configured local MCP listener port
/// @param writable whether changes can be persisted to launcher settings
@NotNullByDefault
public record SettingsCenterSnapshot(
        SupportedLocale language,
        boolean acceptPreviewUpdates,
        boolean disableAutomaticUpdatePrompt,
        boolean disableAprilFools,
        EnumCommonDirectory commonDirectoryType,
        String commonDirectory,
        String resolvedCommonDirectory,
        boolean autoDownloadThreads,
        int downloadThreads,
        DownloadSource versionListSource,
        DownloadSource fileDownloadSource,
        String defaultAddonSource,
        ProxyType proxyType,
        String proxyHost,
        int proxyPort,
        boolean proxyAuthenticationEnabled,
        String proxyUsername,
        String proxyPassword,
        boolean mcpEnabled,
        int mcpPort,
        boolean writable) {
    /// Validates non-null values and the download-concurrency invariant.
    public SettingsCenterSnapshot {
        Objects.requireNonNull(language, "language");
        Objects.requireNonNull(commonDirectoryType, "commonDirectoryType");
        Objects.requireNonNull(commonDirectory, "commonDirectory");
        Objects.requireNonNull(resolvedCommonDirectory, "resolvedCommonDirectory");
        Objects.requireNonNull(versionListSource, "versionListSource");
        Objects.requireNonNull(fileDownloadSource, "fileDownloadSource");
        Objects.requireNonNull(defaultAddonSource, "defaultAddonSource");
        Objects.requireNonNull(proxyType, "proxyType");
        Objects.requireNonNull(proxyHost, "proxyHost");
        Objects.requireNonNull(proxyUsername, "proxyUsername");
        Objects.requireNonNull(proxyPassword, "proxyPassword");
        if (downloadThreads <= 0) {
            throw new IllegalArgumentException("downloadThreads must be positive");
        }
        if (mcpPort < 1 || mcpPort > 0xFFFF) {
            throw new IllegalArgumentException("mcpPort must be in range 1..65535");
        }
    }
}
