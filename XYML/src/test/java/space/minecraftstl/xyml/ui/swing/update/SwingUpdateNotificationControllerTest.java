// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

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
        service.check(new UpdateCheckRequest(UpdateChannel.STABLE))
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

            service.check(new UpdateCheckRequest(UpdateChannel.STABLE))
                    .toCompletableFuture()
                    .join();
            flushEdt();
            assertEquals(1, interaction.confirmations());

            version.set("2.1");
            service.check(new UpdateCheckRequest(UpdateChannel.STABLE))
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
