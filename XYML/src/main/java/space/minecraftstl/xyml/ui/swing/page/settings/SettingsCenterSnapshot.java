// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.settings;

import org.jetbrains.annotations.NotNullByDefault;
import space.minecraftstl.xyml.setting.DownloadSource;
import space.minecraftstl.xyml.setting.EnumCommonDirectory;
import space.minecraftstl.xyml.setting.ProxyType;
import space.minecraftstl.xyml.upgrade.UpdateChannel;
import space.minecraftstl.xyml.util.i18n.SupportedLocale;

import java.util.Objects;

/// Immutable launcher preferences rendered by [SettingsCenterPanel].
///
/// @param language selected launcher language
/// @param updateChannel selected source for automatic and manual launcher update checks
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
/// @param mcpEnabled whether the local MCP server is enabled
/// @param mcpBearerToken bearer token required by the local MCP listener, or an empty string when disabled
/// @param mcpPort loopback port used by the local MCP server
/// @param mcpConfirmInstanceDeletion whether MCP instance deletion requires interactive confirmation
/// @param mcpConfirmModDeletion whether MCP mod deletion requires interactive confirmation
/// @param showMcpEnablementWarning whether the Swing page warns before enabling MCP
/// @param writable whether changes can be persisted to launcher settings
/// @param mcpConfirmResourcePackDeletion whether MCP resource packs deletion requires confirmation
/// @param mcpConfirmWorldDeletion whether MCP worlds deletion requires confirmation
/// @param mcpConfirmServerDeletion whether MCP saved servers deletion requires confirmation
/// @param mcpConfirmShaderPackDeletion whether MCP shader packs deletion requires confirmation
/// @param mcpConfirmSchematicDeletion whether MCP schematic files and directories deletion requires confirmation
@NotNullByDefault
public record SettingsCenterSnapshot(
        SupportedLocale language,
        UpdateChannel updateChannel,
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
        String mcpBearerToken,
        int mcpPort,
        boolean mcpConfirmInstanceDeletion,
        boolean mcpConfirmModDeletion,
        boolean showMcpEnablementWarning,
        boolean writable,
        boolean mcpConfirmResourcePackDeletion,
        boolean mcpConfirmWorldDeletion,
        boolean mcpConfirmServerDeletion,
        boolean mcpConfirmShaderPackDeletion,
        boolean mcpConfirmSchematicDeletion) {
    /// Validates non-null values and the numeric setting invariants.
    public SettingsCenterSnapshot {
        Objects.requireNonNull(language, "language");
        Objects.requireNonNull(updateChannel, "updateChannel");
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
        Objects.requireNonNull(mcpBearerToken, "mcpBearerToken");
        if (downloadThreads <= 0) {
            throw new IllegalArgumentException("downloadThreads must be positive");
        }
        if (mcpPort < 1 || mcpPort > 0xFFFF) {
            throw new IllegalArgumentException("mcpPort must be in range 1..65535");
        }
    }

    /// Creates a snapshot with safe defaults for additional deletion categories.
    ///
    /// @param language selected launcher language
    /// @param updateChannel selected source for automatic and manual launcher update checks
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
    /// @param mcpEnabled whether the local MCP server is enabled
    /// @param mcpBearerToken bearer token required by the local MCP listener, or an empty string when disabled
    /// @param mcpPort loopback port used by the local MCP server
    /// @param mcpConfirmInstanceDeletion whether MCP instance deletion requires interactive confirmation
    /// @param mcpConfirmModDeletion whether MCP mod deletion requires interactive confirmation
    /// @param showMcpEnablementWarning whether the Swing page warns before enabling MCP
    /// @param writable whether changes can be persisted to launcher settings
    public SettingsCenterSnapshot(
            SupportedLocale language,
            UpdateChannel updateChannel,
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
            String mcpBearerToken,
            int mcpPort,
            boolean mcpConfirmInstanceDeletion,
            boolean mcpConfirmModDeletion,
            boolean showMcpEnablementWarning,
            boolean writable) {
        this(
                language,
                updateChannel,
                disableAutomaticUpdatePrompt,
                disableAprilFools,
                commonDirectoryType,
                commonDirectory,
                resolvedCommonDirectory,
                autoDownloadThreads,
                downloadThreads,
                versionListSource,
                fileDownloadSource,
                defaultAddonSource,
                proxyType,
                proxyHost,
                proxyPort,
                proxyAuthenticationEnabled,
                proxyUsername,
                proxyPassword,
                mcpEnabled,
                mcpBearerToken,
                mcpPort,
                mcpConfirmInstanceDeletion,
                mcpConfirmModDeletion,
                showMcpEnablementWarning,
                writable,
                true,
                true,
                true,
                true,
                true);
    }

    /// Creates a snapshot using the legacy MCP fields and the current safe defaults for new fields.
    ///
    /// This overload preserves source and binary compatibility for callers compiled against the snapshot before
    /// bearer-token authentication and the enablement-warning preference were added.
    ///
    /// @param language selected launcher language
    /// @param updateChannel selected source for automatic and manual launcher update checks
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
    /// @param mcpEnabled whether the local MCP server is enabled
    /// @param mcpPort loopback port used by the local MCP server
    /// @param mcpConfirmInstanceDeletion whether MCP instance deletion requires interactive confirmation
    /// @param mcpConfirmModDeletion whether MCP mod deletion requires interactive confirmation
    /// @param writable whether changes can be persisted to launcher settings
    public SettingsCenterSnapshot(
            SupportedLocale language,
            UpdateChannel updateChannel,
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
            boolean mcpConfirmInstanceDeletion,
            boolean mcpConfirmModDeletion,
            boolean writable) {
        this(
                language,
                updateChannel,
                disableAutomaticUpdatePrompt,
                disableAprilFools,
                commonDirectoryType,
                commonDirectory,
                resolvedCommonDirectory,
                autoDownloadThreads,
                downloadThreads,
                versionListSource,
                fileDownloadSource,
                defaultAddonSource,
                proxyType,
                proxyHost,
                proxyPort,
                proxyAuthenticationEnabled,
                proxyUsername,
                proxyPassword,
                mcpEnabled,
                "",
                mcpPort,
                mcpConfirmInstanceDeletion,
                mcpConfirmModDeletion,
                true,
                writable);
    }
}
