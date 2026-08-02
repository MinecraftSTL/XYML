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

import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import space.minecraftstl.xyml.task.FileDownloadTask.IntegrityCheck;
import space.minecraftstl.xyml.ui.swing.update.SwingUpdateCheckService;
import space.minecraftstl.xyml.ui.swing.update.SwingUpdatePromptPresenter;
import space.minecraftstl.xyml.ui.swing.update.UpdateCheckRequest;
import space.minecraftstl.xyml.ui.swing.update.UpdateCheckResult;
import space.minecraftstl.xyml.upgrade.RemoteVersion;
import space.minecraftstl.xyml.upgrade.UpdateChannel;

import java.awt.Component;
import java.io.IOException;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies settings-owned manual update presentation and exact cache-directory cleanup boundaries.
@NotNullByDefault
public final class LauncherSettingsMaintenanceActionsTest {
    /// A manual check preserves the selected channel and preview flag before presenting an available release.
    @Test
    public void checksExactRequestAndPresentsAvailableUpdate() {
        AtomicReference<@Nullable UpdateCheckRequest> fetchedRequest = new AtomicReference<>();
        RecordingDialogInteraction interaction = new RecordingDialogInteraction();
        SwingUpdateCheckService service = new SwingUpdateCheckService(
                request -> {
                    fetchedRequest.set(request);
                    return remoteVersion(request.channel());
                },
                remoteVersion -> true,
                Runnable::run);
        LauncherSettingsMaintenanceActions actions = new LauncherSettingsMaintenanceActions(
                service,
                presenter(interaction),
                Runnable::run);
        try {
            UpdateCheckRequest request = new UpdateCheckRequest(UpdateChannel.BETA, true);
            UpdateCheckResult result = actions.checkForUpdates(request).toCompletableFuture().join();

            assertSame(request, fetchedRequest.get());
            assertSame(request, result.request());
            assertSame(result, interaction.presentedResult);
        } finally {
            actions.close();
        }
    }

    /// Cache cleanup removes only descendants of the `cache` child and retains sibling common-directory data.
    ///
    /// @throws IOException if the in-memory filesystem cannot prepare or inspect the fixture
    @Test
    public void clearsOnlyCacheChildOfCommonDirectory() throws IOException {
        try (FileSystem fileSystem = Jimfs.newFileSystem(Configuration.unix())) {
            Path commonDirectory = Files.createDirectories(fileSystem.getPath("/common"));
            Path cacheDirectory = Files.createDirectories(commonDirectory.resolve("cache/nested"));
            Files.writeString(cacheDirectory.resolve("cached.bin"), "cache");
            Path retainedFile = Files.writeString(commonDirectory.resolve("settings.json"), "settings");

            LauncherSettingsMaintenanceActions actions = actionsWithoutAvailableUpdate();
            try {
                assertTrue(actions.clearCache(commonDirectory).toCompletableFuture().join());
                assertTrue(Files.isDirectory(commonDirectory.resolve("cache")));
                try (Stream<Path> entries = Files.list(commonDirectory.resolve("cache"))) {
                    assertTrue(entries.findAny().isEmpty());
                }
                assertTrue(Files.exists(retainedFile));
                assertEquals("settings", Files.readString(retainedFile));
            } finally {
                actions.close();
            }
        }
    }

    /// Creates maintenance actions whose update path is unused by cache-focused tests.
    ///
    /// @return deterministic synchronous actions
    private static LauncherSettingsMaintenanceActions actionsWithoutAvailableUpdate() {
        SwingUpdateCheckService service = new SwingUpdateCheckService(
                request -> remoteVersion(request.channel()),
                remoteVersion -> false,
                Runnable::run);
        return new LauncherSettingsMaintenanceActions(
                service,
                presenter(new RecordingDialogInteraction()),
                Runnable::run);
    }

    /// Creates a deterministic presenter that never opens an external browser.
    ///
    /// @param interaction recording prompt boundary
    /// @return injectable presenter
    private static SwingUpdatePromptPresenter presenter(RecordingDialogInteraction interaction) {
        return new SwingUpdatePromptPresenter(
                () -> null,
                URI.create("https://example.test/releases"),
                releasePage -> {
                    throw new AssertionError("Dismissed test prompts must not open " + releasePage);
                },
                Runnable::run,
                new SwingUpdatePromptPresenter.Strings(
                        "Update available",
                        "Latest version: %s",
                        "Update",
                        "Cancel",
                        "Browser failed"),
                interaction);
    }

    /// Builds one deterministic remote launcher version.
    ///
    /// @param channel release channel returned by the source
    /// @return remote version fixture
    private static RemoteVersion remoteVersion(UpdateChannel channel) {
        return new RemoteVersion(
                channel,
                "4.0",
                "https://example.test/xyml.jar",
                RemoteVersion.Type.JAR,
                new IntegrityCheck("SHA-1", "0123456789abcdef"),
                false,
                false);
    }

    /// Records the available release shown through the existing Swing update presenter.
    @NotNullByDefault
    private static final class RecordingDialogInteraction implements SwingUpdatePromptPresenter.DialogInteraction {
        /// Exact update result supplied to the confirmation prompt, or null before presentation.
        private @Nullable UpdateCheckResult presentedResult;

        /// Records the presented result and dismisses the release-page action.
        ///
        /// @param owner current dialog owner, or null
        /// @param strings localized prompt strings
        /// @param result available update result
        /// @return completed dismissal decision
        @Override
        public CompletionStage<Boolean> confirm(
                @Nullable Component owner,
                SwingUpdatePromptPresenter.Strings strings,
                UpdateCheckResult result) {
            presentedResult = result;
            return CompletableFuture.completedFuture(false);
        }

        /// Rejects browser-fallback presentation because the test dismisses the prompt.
        ///
        /// @param owner current dialog owner, or null
        /// @param strings localized prompt strings
        /// @param releasePage manual release page
        /// @return never returned because this path is unexpected
        @Override
        public CompletionStage<Void> reportBrowserFailure(
                @Nullable Component owner,
                SwingUpdatePromptPresenter.Strings strings,
                URI releasePage) {
            return CompletableFuture.failedFuture(new AssertionError(
                    "Unexpected browser fallback for " + releasePage));
        }
    }
}
