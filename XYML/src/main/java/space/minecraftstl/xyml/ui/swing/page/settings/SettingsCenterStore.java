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
import space.minecraftstl.xyml.observable.Subscription;
import space.minecraftstl.xyml.observable.ValueChangeListener;
import space.minecraftstl.xyml.setting.DownloadSource;
import space.minecraftstl.xyml.setting.EnumCommonDirectory;
import space.minecraftstl.xyml.setting.ProxyType;
import space.minecraftstl.xyml.util.i18n.SupportedLocale;

/// Owns toolkit-neutral reads and writes for the general and network settings center.
///
/// Implementations may deliver change callbacks from any thread. Consumers that touch Swing components must dispatch
/// those callbacks to the event dispatch thread.
@NotNullByDefault
public interface SettingsCenterStore extends AutoCloseable {
    /// Returns the latest immutable settings state.
    ///
    /// @return current settings-center snapshot
    SettingsCenterSnapshot snapshot();

    /// Registers for settings-center snapshot changes.
    ///
    /// @param listener listener receiving ordered store transitions
    /// @return independently removable registration
    Subscription subscribe(ValueChangeListener<SettingsCenterSnapshot> listener);

    /// Persists the selected launcher language.
    ///
    /// @param language requested display language
    void setLanguage(SupportedLocale language);

    /// Persists whether preview updates may be offered.
    ///
    /// @param accepted whether preview updates are eligible
    void setAcceptPreviewUpdates(boolean accepted);

    /// Persists whether automatic update dialogs are suppressed.
    ///
    /// @param disabled whether automatic update dialogs are disabled
    void setAutomaticUpdatePromptDisabled(boolean disabled);

    /// Persists whether April Fools behavior is disabled.
    ///
    /// @param disabled whether April Fools behavior is disabled
    void setAprilFoolsDisabled(boolean disabled);

    /// Persists the common Minecraft-directory selection mode.
    ///
    /// @param directoryType selected directory mode
    void setCommonDirectoryType(EnumCommonDirectory directoryType);

    /// Persists the custom common Minecraft directory.
    ///
    /// @param directory configured directory string, which may be empty
    void setCommonDirectory(String directory);

    /// Persists whether download concurrency is determined automatically.
    ///
    /// @param automatic whether automatic concurrency is enabled
    void setAutomaticDownloadThreads(boolean automatic);

    /// Persists a positive manual download concurrency value.
    ///
    /// @param threads positive download concurrency
    void setDownloadThreads(int threads);

    /// Persists the game-version list source preference.
    ///
    /// @param source preferred version-list source
    void setVersionListSource(DownloadSource source);

    /// Persists the file-download source preference.
    ///
    /// @param source preferred file-download source
    void setFileDownloadSource(DownloadSource source);

    /// Persists the default remote add-on catalogue source ID.
    ///
    /// @param sourceId non-blank catalogue source ID
    void setDefaultAddonSource(String sourceId);

    /// Persists the selected proxy strategy.
    ///
    /// @param proxyType selected proxy type
    void setProxyType(ProxyType proxyType);

    /// Persists the custom proxy host.
    ///
    /// @param host configured host, which may be empty until a custom proxy is complete
    void setProxyHost(String host);

    /// Persists a valid TCP proxy port.
    ///
    /// @param port proxy port from `0` through `65535`
    void setProxyPort(int port);

    /// Persists whether proxy credentials are used.
    ///
    /// @param enabled whether proxy credentials are enabled
    void setProxyAuthenticationEnabled(boolean enabled);

    /// Persists the proxy authentication username.
    ///
    /// @param username configured username, which may be empty
    void setProxyUsername(String username);

    /// Persists the proxy authentication password.
    ///
    /// @param password configured password, which may be empty
    void setProxyPassword(String password);

    /// Releases every store-owned listener.
    @Override
    void close();
}
