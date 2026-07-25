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
package space.minecraftstl.xyml.ui.swing.update;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import space.minecraftstl.xyml.task.FileDownloadTask.IntegrityCheck;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;
import space.minecraftstl.xyml.upgrade.RemoteVersion;
import space.minecraftstl.xyml.upgrade.UpdateChannel;

import java.awt.Component;
import java.net.URI;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

/// Verifies automatic offer reconciliation and release-identity deduplication.
@NotNullByDefault
class SwingUpdateNotificationControllerTest {
    /// Offers an already committed result and suppresses the same release on later successful checks.
    @Test
    void offersEachReleaseIdentityOnceIncludingPreexistingSuccess() {
        AtomicReference<String> version = new AtomicReference<>("2.0");
        SwingUpdateCheckService service = new SwingUpdateCheckService(
                request -> remote(version.get()),
                remoteVersion -> true,
                Runnable::run);
        service.check(new UpdateCheckRequest(UpdateChannel.STABLE, false))
                .toCompletableFuture()
                .join();

        CountingInteraction interaction = new CountingInteraction();
        SwingUpdatePromptPresenter presenter = new SwingUpdatePromptPresenter(
                () -> null,
                URI.create("https://example.test/releases"),
                releasePage -> { },
                Runnable::run,
                new SwingUpdatePromptPresenter.Strings(
                        "Update available",
                        "Latest version: %s",
                        "Open",
                        "Cancel",
                        "Browser unavailable"),
                interaction);
        try (SwingUpdateNotificationController controller =
                     new SwingUpdateNotificationController(service, presenter)) {
            flushEdt();
            assertEquals(1, interaction.confirmations());

            service.check(new UpdateCheckRequest(UpdateChannel.STABLE, false))
                    .toCompletableFuture()
                    .join();
            flushEdt();
            assertEquals(1, interaction.confirmations());

            version.set("2.1");
            service.check(new UpdateCheckRequest(UpdateChannel.STABLE, true))
                    .toCompletableFuture()
                    .join();
            flushEdt();
            assertEquals(2, interaction.confirmations());
        } finally {
            service.close();
        }
    }

    /// Waits for prompt work already queued on the Swing EDT.
    private static void flushEdt() {
        EdtDispatcher.executeAndWait(() -> { });
    }

    /// Creates one remote-version fixture for the selected version.
    ///
    /// @param version remote version string
    /// @return remote-version fixture
    private static RemoteVersion remote(String version) {
        return new RemoteVersion(
                UpdateChannel.STABLE,
                version,
                "https://example.test/" + version + "/xyml.jar",
                RemoteVersion.Type.JAR,
                new IntegrityCheck("SHA-1", "0123456789abcdef"),
                false,
                false);
    }

    /// Headless prompt fixture that always dismisses and counts offers.
    @NotNullByDefault
    private static final class CountingInteraction implements SwingUpdatePromptPresenter.DialogInteraction {
        /// Number of confirmation requests.
        private final AtomicInteger confirmations = new AtomicInteger();

        /// Records and dismisses one offer.
        ///
        /// @param owner ignored owner
        /// @param strings prompt strings
        /// @param result update result
        /// @return completed rejection
        @Override
        public CompletionStage<Boolean> confirm(
                @Nullable Component owner,
                SwingUpdatePromptPresenter.Strings strings,
                UpdateCheckResult result) {
            confirmations.incrementAndGet();
            return CompletableFuture.completedFuture(false);
        }

        /// Rejects an unexpected browser fallback in this dismissal-only fixture.
        ///
        /// @param owner ignored owner
        /// @param strings prompt strings
        /// @param releasePage release page
        /// @return failed unexpected-call stage
        @Override
        public CompletionStage<Void> reportBrowserFailure(
                @Nullable Component owner,
                SwingUpdatePromptPresenter.Strings strings,
                URI releasePage) {
            return CompletableFuture.failedFuture(
                    new AssertionError("Browser fallback was not expected"));
        }

        /// Returns the offer count.
        ///
        /// @return confirmation count
        private int confirmations() {
            return confirmations.get();
        }
    }
}
