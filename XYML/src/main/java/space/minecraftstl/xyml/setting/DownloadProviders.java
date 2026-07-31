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

import space.minecraftstl.xyml.download.*;
import space.minecraftstl.xyml.task.DownloadException;
import space.minecraftstl.xyml.task.FetchTask;
import space.minecraftstl.xyml.util.StringUtils;
import space.minecraftstl.xyml.util.i18n.I18n;
import space.minecraftstl.xyml.util.i18n.LocaleUtils;
import space.minecraftstl.xyml.util.io.ResponseCodeException;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import javax.net.ssl.SSLHandshakeException;
import java.io.FileNotFoundException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.file.AccessDeniedException;
import java.util.List;
import java.util.concurrent.CancellationException;

import static space.minecraftstl.xyml.setting.SettingsManager.settings;
import static space.minecraftstl.xyml.task.FetchTask.DEFAULT_CONCURRENCY;
import static space.minecraftstl.xyml.util.i18n.I18n.i18n;

/// Owns the launcher-wide download provider selection and download error localization.
@NotNullByDefault
public final class DownloadProviders {
    /// Prevents instantiation.
    private DownloadProviders() {
    }

    /// Stable delegating provider exposed to consumers while its selected backend changes.
    private static final DownloadProviderWrapper PROVIDER_WRAPPER;

    /// Official Mojang download backend.
    private static final DownloadProvider MOJANG_PROVIDER;

    /// BMCLAPI mirror download backend.
    private static final BMCLAPIDownloadProvider BMCLAPI_PROVIDER;

    /// Initial locale-aware provider composition.
    private static final DownloadProvider DEFAULT_PROVIDER;

    static {
        String bmclapiRoot = System.getProperty("xyml.bmclapi.override", "https://bmclapi2.bangbang93.com");
        BMCLAPI_PROVIDER = new BMCLAPIDownloadProvider(bmclapiRoot);
        MOJANG_PROVIDER = new MojangDownloadProvider();
        DEFAULT_PROVIDER = createDownloadProvider(DownloadSource.DEFAULT, DownloadSource.DEFAULT);
        PROVIDER_WRAPPER = new DownloadProviderWrapper(DEFAULT_PROVIDER);
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
            PROVIDER_WRAPPER.setProvider(createDownloadProvider(
                    settings().versionListSourceProperty().get(),
                    settings().fileDownloadSourceProperty().get()));
        };
        settings().versionListSourceProperty().subscribe(change -> onChangeDownloadSource.run());
        settings().fileDownloadSourceProperty().subscribe(change -> onChangeDownloadSource.run());
        onChangeDownloadSource.run();
    }

    /// Creates a download provider with independent version-list and file download preferences.
    ///
    /// @param versionListSource preferred version-list source, or `null` to use the locale-aware default
    /// @param fileDownloadSource preferred artifact source, or `null` to use the locale-aware default
    /// @return provider with ordered fallbacks for both operations
    private static DownloadProvider createDownloadProvider(
            @Nullable DownloadSource versionListSource,
            @Nullable DownloadSource fileDownloadSource) {
        return new AutoDownloadProvider(
                getCandidates(versionListSource),
                getCandidates(fileDownloadSource));
    }

    /// Returns provider candidates ordered by the given source preference.
    ///
    /// @param source preferred source, or `null` to use the locale-aware default
    /// @return immutable provider candidates in attempt order
    private static @Unmodifiable List<DownloadProvider> getCandidates(@Nullable DownloadSource source) {
        DownloadSource normalized = source != null ? source : DownloadSource.DEFAULT;
        return switch (normalized) {
            case DEFAULT -> LocaleUtils.IS_CHINA_MAINLAND
                    ? List.of(BMCLAPI_PROVIDER, MOJANG_PROVIDER)
                    : List.of(MOJANG_PROVIDER, BMCLAPI_PROVIDER);
            case OFFICIAL -> List.of(MOJANG_PROVIDER);
            case MIRROR -> List.of(BMCLAPI_PROVIDER, MOJANG_PROVIDER);
        };
    }

    /// Returns the stable launcher-wide provider wrapper.
    ///
    /// @return provider wrapper delegating to the current preference
    public static DownloadProvider getDownloadProvider() {
        return PROVIDER_WRAPPER;
    }

    /// Converts a download failure into a localized user-facing message and diagnostic detail.
    ///
    /// @param exception failure to describe
    /// @return localized failure detail
    public static String localizeErrorMessage(Throwable exception) {
        if (exception instanceof DownloadException) {
            @Nullable URI uri = ((DownloadException) exception).getUri();
            if (exception.getCause() instanceof SocketTimeoutException) {
                return i18n("install.failed.downloading.timeout", uri);
            } else if (exception.getCause() instanceof ResponseCodeException) {
                ResponseCodeException responseCodeException = (ResponseCodeException) exception.getCause();
                if (I18n.hasKey("download.code." + responseCodeException.getResponseCode())) {
                    return i18n("download.code." + responseCodeException.getResponseCode(), uri);
                } else {
                    return i18n("install.failed.downloading.detail", uri) + "\n" + StringUtils.getStackTrace(exception.getCause());
                }
            } else if (exception.getCause() instanceof FileNotFoundException) {
                return i18n("download.code.404", uri);
            } else if (exception.getCause() instanceof AccessDeniedException) {
                return i18n("install.failed.downloading.detail", uri) + "\n" + i18n("exception.access_denied", ((AccessDeniedException) exception.getCause()).getFile());
            } else if (exception.getCause() instanceof ArtifactMalformedException) {
                return i18n("install.failed.downloading.detail", uri) + "\n" + i18n("exception.artifact_malformed");
            } else if (exception.getCause() instanceof SSLHandshakeException && !(exception.getCause().getMessage() != null && exception.getCause().getMessage().contains("Remote host terminated"))) {
                if (exception.getCause().getMessage() != null && (exception.getCause().getMessage().contains("No name matching") || exception.getCause().getMessage().contains("No subject alternative DNS name matching"))) {
                    return i18n("install.failed.downloading.detail", uri) + "\n" + i18n("exception.dns.pollution");
                }
                return i18n("install.failed.downloading.detail", uri) + "\n" + i18n("exception.ssl_handshake");
            } else {
                return i18n("install.failed.downloading.detail", uri) + "\n" + StringUtils.getStackTrace(exception.getCause());
            }
        } else if (exception instanceof ArtifactMalformedException) {
            return i18n("exception.artifact_malformed");
        } else if (exception instanceof CancellationException) {
            return i18n("message.cancelled");
        }
        return StringUtils.getStackTrace(exception);
    }
}
