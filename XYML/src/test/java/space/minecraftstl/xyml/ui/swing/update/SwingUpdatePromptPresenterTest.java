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
import space.minecraftstl.xyml.upgrade.RemoteVersion;
import space.minecraftstl.xyml.upgrade.UpdateChannel;

import javax.swing.SwingUtilities;
import java.awt.Component;
import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies update prompt decisions and the explicit manual-release action boundary without opening Swing windows.
@NotNullByDefault
class SwingUpdatePromptPresenterTest {
    /// Opens the configured release page after explicit acceptance.
    @Test
    void acceptedOfferOpensReleasePage() {
        RecordingInteraction interaction = new RecordingInteraction(true);
        AtomicReference<@Nullable URI> openedPage = new AtomicReference<>();
        URI releasePage = URI.create("https://example.test/releases");
        SwingUpdatePromptPresenter presenter = presenter(
                interaction,
                openedPage::set,
                releasePage);

        SwingUpdatePromptPresenter.Outcome outcome = presenter.present(result(true, "2.0"))
                .toCompletableFuture()
                .join();

        assertEquals(SwingUpdatePromptPresenter.Outcome.OPENED_RELEASE_PAGE, outcome);
        assertEquals(releasePage, openedPage.get());
        assertEquals(1, interaction.confirmations());
        assertEquals(0, interaction.browserFailures());
    }

    /// Dismisses an offer without invoking any release action.
    @Test
    void dismissedOfferDoesNotOpenReleasePage() {
        RecordingInteraction interaction = new RecordingInteraction(false);
        AtomicInteger launches = new AtomicInteger();
        SwingUpdatePromptPresenter presenter = presenter(
                interaction,
                releasePage -> launches.incrementAndGet(),
                URI.create("https://example.test/releases"));

        SwingUpdatePromptPresenter.Outcome outcome = presenter.present(result(true, "2.0"))
                .toCompletableFuture()
                .join();

        assertEquals(SwingUpdatePromptPresenter.Outcome.DISMISSED, outcome);
        assertEquals(0, launches.get());
    }

    /// Reports browser failure through the interaction fallback and exposes the release URI.
    @Test
    void browserFailureShowsManualLinkFallback() {
        RecordingInteraction interaction = new RecordingInteraction(true);
        IOException expectedFailure = new IOException("no browser");
        URI releasePage = URI.create("https://example.test/releases");
        SwingUpdatePromptPresenter presenter = presenter(
                interaction,
                page -> {
                    throw expectedFailure;
                },
                releasePage);

        SwingUpdatePromptPresenter.Outcome outcome = presenter.present(result(true, "2.0"))
                .toCompletableFuture()
                .join();

        assertEquals(SwingUpdatePromptPresenter.Outcome.RELEASE_PAGE_FALLBACK_SHOWN, outcome);
        assertEquals(1, interaction.browserFailures());
        assertEquals(releasePage, interaction.reportedPage());
    }

    /// Ignores successful checks whose version policy found no update.
    @Test
    void currentVersionDoesNotShowPrompt() {
        RecordingInteraction interaction = new RecordingInteraction(true);
        AtomicInteger launches = new AtomicInteger();
        SwingUpdatePromptPresenter presenter = presenter(
                interaction,
                releasePage -> launches.incrementAndGet(),
                URI.create("https://example.test/releases"));

        SwingUpdatePromptPresenter.Outcome outcome = presenter.present(result(false, "1.0"))
                .toCompletableFuture()
                .join();

        assertEquals(SwingUpdatePromptPresenter.Outcome.NOT_APPLICABLE, outcome);
        assertEquals(0, interaction.confirmations());
        assertEquals(0, launches.get());
    }

    /// Resolves the native dialog owner only on the EDT even when presentation starts on a worker.
    @Test
    void resolvesOwnerOnEdtFromBackgroundPresentation() {
        AtomicBoolean ownerResolved = new AtomicBoolean();
        RecordingInteraction interaction = new RecordingInteraction(false);
        SwingUpdatePromptPresenter presenter = new SwingUpdatePromptPresenter(
                () -> {
                    assertTrue(SwingUtilities.isEventDispatchThread());
                    ownerResolved.set(true);
                    return null;
                },
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

        SwingUpdatePromptPresenter.Outcome outcome = CompletableFuture
                .supplyAsync(() -> presenter.present(result(true, "2.0")))
                .thenCompose(stage -> stage)
                .join();

        assertEquals(SwingUpdatePromptPresenter.Outcome.DISMISSED, outcome);
        assertTrue(ownerResolved.get());
    }

    /// Rejects local files and non-HTTP schemes before creating a presenter.
    @Test
    void rejectsNonWebReleasePage() {
        RecordingInteraction interaction = new RecordingInteraction(true);

        assertThrows(
                IllegalArgumentException.class,
                () -> presenter(interaction, page -> { }, URI.create("file:///tmp/release")));
    }

    /// Creates one deterministic presenter using direct background-action execution.
    ///
    /// @param interaction recording dialog interaction
    /// @param launcher release-page action
    /// @param releasePage configured manual page
    /// @return injectable presenter
    private static SwingUpdatePromptPresenter presenter(
            RecordingInteraction interaction,
            ReleasePageLauncher launcher,
            URI releasePage) {
        return new SwingUpdatePromptPresenter(
                () -> null,
                releasePage,
                launcher,
                Runnable::run,
                new SwingUpdatePromptPresenter.Strings(
                        "Update available",
                        "Latest version: %s",
                        "Open",
                        "Cancel",
                        "Browser unavailable"),
                interaction);
    }

    /// Creates one successful result fixture.
    ///
    /// @param available whether policy found an update
    /// @param version remote version string
    /// @return successful result fixture
    private static UpdateCheckResult result(boolean available, String version) {
        RemoteVersion remoteVersion = new RemoteVersion(
                UpdateChannel.STABLE,
                version,
                "https://example.test/xyml.jar",
                RemoteVersion.Type.JAR,
                new IntegrityCheck("SHA-1", "0123456789abcdef"),
                false,
                false);
        return new UpdateCheckResult(
                new UpdateCheckRequest(UpdateChannel.STABLE, false),
                remoteVersion,
                available,
                Instant.parse("2026-07-24T08:00:00Z"));
    }

    /// Headless interaction fixture returning one configured decision.
    @NotNullByDefault
    private static final class RecordingInteraction implements SwingUpdatePromptPresenter.DialogInteraction {
        /// Decision returned by every confirmation.
        private final boolean accepted;

        /// Number of confirmation requests.
        private final AtomicInteger confirmations = new AtomicInteger();

        /// Number of browser-failure reports.
        private final AtomicInteger browserFailures = new AtomicInteger();

        /// Last release page exposed through fallback, or null before failure.
        private final AtomicReference<@Nullable URI> reportedPage = new AtomicReference<>();

        /// Creates one interaction fixture.
        ///
        /// @param accepted decision to return
        private RecordingInteraction(boolean accepted) {
            this.accepted = accepted;
        }

        /// Records and returns the configured decision.
        ///
        /// @param owner ignored owner
        /// @param strings prompt strings
        /// @param result update result
        /// @return completed configured decision
        @Override
        public CompletionStage<Boolean> confirm(
                @Nullable Component owner,
                SwingUpdatePromptPresenter.Strings strings,
                UpdateCheckResult result) {
            confirmations.incrementAndGet();
            assertEquals("Latest version: " + result.remoteVersion().version(),
                    strings.versionMessage(result.remoteVersion().version()));
            return CompletableFuture.completedFuture(accepted);
        }

        /// Records the manual release fallback.
        ///
        /// @param owner ignored owner
        /// @param strings prompt strings
        /// @param releasePage manual release page
        /// @return completed fallback stage
        @Override
        public CompletionStage<Void> reportBrowserFailure(
                @Nullable Component owner,
                SwingUpdatePromptPresenter.Strings strings,
                URI releasePage) {
            browserFailures.incrementAndGet();
            reportedPage.set(releasePage);
            return CompletableFuture.completedFuture(null);
        }

        /// Returns the confirmation count.
        ///
        /// @return confirmation count
        private int confirmations() {
            return confirmations.get();
        }

        /// Returns the browser-failure count.
        ///
        /// @return browser-failure count
        private int browserFailures() {
            return browserFailures.get();
        }

        /// Returns the last fallback page.
        ///
        /// @return recorded release page, or null
        private @Nullable URI reportedPage() {
            return reportedPage.get();
        }
    }
}
