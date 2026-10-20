/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2021  huangyuhui <huanghongxun2008@126.com> and contributors
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
package space.minecraftstl.xyml.setting;

import space.minecraftstl.xyml.download.ArtifactMalformedException;
import space.minecraftstl.xyml.download.DownloadProvider;
import space.minecraftstl.xyml.task.DownloadException;
import space.minecraftstl.xyml.task.FetchTask;
import space.minecraftstl.xyml.util.StringUtils;
import space.minecraftstl.xyml.util.i18n.I18n;
import space.minecraftstl.xyml.util.io.ResponseCodeException;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import javax.net.ssl.SSLHandshakeException;
import java.io.FileNotFoundException;
import java.net.SocketTimeoutException;
import java.nio.file.AccessDeniedException;
import java.util.concurrent.CancellationException;

import static space.minecraftstl.xyml.setting.SettingsManager.settings;
import static space.minecraftstl.xyml.task.FetchTask.DEFAULT_CONCURRENCY;
import static space.minecraftstl.xyml.util.i18n.I18n.i18n;

/// Owns the launcher-wide download provider selection and download error localization.
@NotNullByDefault
public final class DownloadProviders {
    /// Default BMCLAPI mirror root used when no override is configured.
    private static final String DEFAULT_BMCLAPI_ROOT = "https://bmclapi2.bangbang93.com";

    /// Effective BMCLAPI mirror root without a trailing separator.
    private static final String BMCLAPI_ROOT = resolveBMCLAPIROOT();

    /// Stable provider whose source preferences follow the launcher settings.
    private static final LauncherDownloadProvider PROVIDER = new LauncherDownloadProvider(BMCLAPI_ROOT);

    /// Prevents instantiation.
    private DownloadProviders() {
    }

    /// Initializes download provider settings and synchronizes download thread settings.
    public static void init() {
        Runnable onChangeDownloadThreads = () -> {
            FetchTask.setDownloadExecutorConcurrency(settings().autoDownloadThreadsProperty().get()
                    ? DEFAULT_CONCURRENCY
                    : settings().downloadThreadsProperty().get());
        };
        settings().autoDownloadThreadsProperty().subscribe(change -> onChangeDownloadThreads.run());
        settings().downloadThreadsProperty().subscribe(change -> onChangeDownloadThreads.run());
        onChangeDownloadThreads.run();

        Runnable onChangeDownloadSource = () -> {
            PROVIDER.setVersionListSource(normalizeSource(settings().versionListSourceProperty().get()));
            PROVIDER.setFileSource(normalizeSource(settings().fileDownloadSourceProperty().get()));
        };
        settings().versionListSourceProperty().subscribe(change -> onChangeDownloadSource.run());
        settings().fileDownloadSourceProperty().subscribe(change -> onChangeDownloadSource.run());
        onChangeDownloadSource.run();
    }

    /// Resolves the configured BMCLAPI mirror root, ignoring blank values.
    ///
    /// @return mirror root without a trailing separator
    private static String resolveBMCLAPIROOT() {
        @Nullable String override = System.getProperty("xyml.bmclapi.override");
        String root = StringUtils.isBlank(override) ? DEFAULT_BMCLAPI_ROOT : override.trim();
        return StringUtils.removeSuffix(root, "/");
    }

    /// Normalizes a nullable source preference to the automatic default.
    ///
    /// @param source configured source preference, or `null`
    /// @return non-null source preference
    private static DownloadSource normalizeSource(@Nullable DownloadSource source) {
        return source != null ? source : DownloadSource.DEFAULT;
    }

    /// Returns the stable launcher-wide download provider.
    ///
    /// @return stable provider delegating to the current source preference
    public static DownloadProvider getDownloadProvider() {
        return PROVIDER;
    }

    /// Converts a download failure into a localized user-facing message and diagnostic detail.
    ///
    /// @param exception failure to describe
    /// @return localized failure detail
    public static String localizeErrorMessage(Throwable exception) {
        if (exception instanceof DownloadException) {
            String url = ((DownloadException) exception).getUrl();
            if (exception.getCause() instanceof SocketTimeoutException) {
                return i18n("install.failed.downloading.timeout", url);
            } else if (exception.getCause() instanceof ResponseCodeException) {
                ResponseCodeException responseCodeException = (ResponseCodeException) exception.getCause();
                if (I18n.hasKey("download.code." + responseCodeException.getResponseCode())) {
                    return i18n("download.code." + responseCodeException.getResponseCode(), url);
                } else {
                    return i18n("install.failed.downloading.detail", url) + "\n" + StringUtils.getStackTrace(exception.getCause());
                }
            } else if (exception.getCause() instanceof FileNotFoundException) {
                return i18n("download.code.404", url);
            } else if (exception.getCause() instanceof AccessDeniedException) {
                return i18n("install.failed.downloading.detail", url) + "\n" + i18n("exception.access_denied", ((AccessDeniedException) exception.getCause()).getFile());
            } else if (exception.getCause() instanceof ArtifactMalformedException) {
                return i18n("install.failed.downloading.detail", url) + "\n" + i18n("exception.artifact_malformed");
            } else if (exception.getCause() instanceof SSLHandshakeException && !(exception.getCause().getMessage() != null && exception.getCause().getMessage().contains("Remote host terminated"))) {
                if (exception.getCause().getMessage() != null && (exception.getCause().getMessage().contains("No name matching") || exception.getCause().getMessage().contains("No subject alternative DNS name matching"))) {
                    return i18n("install.failed.downloading.detail", url) + "\n" + i18n("exception.dns.pollution");
                }
                return i18n("install.failed.downloading.detail", url) + "\n" + i18n("exception.ssl_handshake");
            } else {
                return i18n("install.failed.downloading.detail", url) + "\n" + StringUtils.getStackTrace(exception.getCause());
            }
        } else if (exception instanceof ArtifactMalformedException) {
            return i18n("exception.artifact_malformed");
        } else if (exception instanceof CancellationException) {
            return i18n("message.cancelled");
        }
        return StringUtils.getStackTrace(exception);
    }
}
