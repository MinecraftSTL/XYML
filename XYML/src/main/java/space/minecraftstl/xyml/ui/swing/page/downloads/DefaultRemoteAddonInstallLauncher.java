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
package space.minecraftstl.xyml.ui.swing.page.downloads;

import org.jetbrains.annotations.NotNullByDefault;
import space.minecraftstl.xyml.download.DownloadProvider;
import space.minecraftstl.xyml.setting.DownloadProviders;
import space.minecraftstl.xyml.task.FileDownloadTask;
import space.minecraftstl.xyml.task.Schedulers;
import space.minecraftstl.xyml.task.Task;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/// Production verified-download task factory for remote add-ons and world archives.
///
/// Each task downloads into a private sibling temporary file, verifies the provider hash and ZIP/JAR
/// structure when applicable, then moves it to the selected directory without `REPLACE_EXISTING`.
/// This prevents stale or malicious provider file names from silently overwriting existing instance files.
@NotNullByDefault
public final class DefaultRemoteAddonInstallLauncher implements RemoteAddonInstallLauncher {
    /// Launcher-wide provider used to build candidate URLs for a selected artifact.
    private final DownloadProvider downloadProvider;

    /// Creates a launcher using the current launcher-wide download provider.
    public DefaultRemoteAddonInstallLauncher() {
        this(DownloadProviders.getDownloadProvider());
    }

    /// Creates a launcher with an explicit provider for deterministic task construction.
    ///
    /// @param downloadProvider provider used for candidate artifact URLs
    public DefaultRemoteAddonInstallLauncher(DownloadProvider downloadProvider) {
        this.downloadProvider = Objects.requireNonNull(downloadProvider, "downloadProvider");
    }

    /// Defers target-directory creation and collision validation until the user-started task executes.
    ///
    /// @param request selected artifact and resolved destination
    /// @return unstarted safe acquisition task graph
    @Override
    public Task<?> createInstallTask(RemoteAddonInstallRequest request) {
        RemoteAddonInstallRequest installRequest = Objects.requireNonNull(request, "request");
        return Task.composeAsync(Schedulers.io(), () -> createDeferredTask(installRequest))
                .setName(installRequest.version().file().filename());
    }

    /// Creates one safe temporary download and no-replace final publication chain on the I/O scheduler.
    ///
    /// @param request selected artifact and resolved target
    /// @return task graph that owns temporary-file cleanup
    /// @throws IOException when local target preparation fails
    private Task<?> createDeferredTask(RemoteAddonInstallRequest request) throws IOException {
        Path destination = request.target().resolveDestination(request.version());
        Path directory = Objects.requireNonNull(destination.getParent(), "destination parent");
        Files.createDirectories(directory);
        if (Files.exists(destination)) {
            throw new IOException("A file with the selected add-on name already exists: " + destination.getFileName());
        }

        Path temporary = Files.createTempFile(directory, ".xyml-addon-", temporarySuffix(destination));
        FileDownloadTask download = new FileDownloadTask(
                downloadProvider.injectURLWithCandidates(request.version().file().url()),
                temporary,
                request.version().file().getIntegrityCheck());
        download.setName(request.version().name().isBlank()
                ? request.version().file().filename()
                : request.version().name());
        download.addIntegrityCheckHandler(FileDownloadTask.ZIP_INTEGRITY_CHECK_HANDLER);
        return download
                .thenRunAsync(Schedulers.io(), () -> Files.move(temporary, destination))
                .whenComplete(Schedulers.io(), failure -> Files.deleteIfExists(temporary));
    }

    /// Keeps temporary-file suffixes compatible with ZIP/JAR structural validation.
    ///
    /// @param destination final managed or save-as file path
    /// @return safe non-empty temporary suffix
    private static String temporarySuffix(Path destination) {
        String name = Objects.requireNonNull(destination.getFileName(), "destination file name").toString();
        int extensionStart = name.lastIndexOf('.');
        return extensionStart <= 0 || extensionStart == name.length() - 1
                ? ".part"
                : name.substring(extensionStart);
    }
}
