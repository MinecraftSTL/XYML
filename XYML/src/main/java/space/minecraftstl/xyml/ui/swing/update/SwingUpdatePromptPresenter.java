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
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;

import javax.swing.JOptionPane;
import java.awt.Component;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.net.URI;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.function.Supplier;

import static space.minecraftstl.xyml.util.i18n.I18n.i18n;

/// Presents an available launcher release through native Swing and opens its manual release page.
///
/// This presenter deliberately owns no automatic-download action. Acceptance opens the manual release page on a
/// caller-owned background executor, leaving update installation under explicit user control.
@NotNullByDefault
public final class SwingUpdatePromptPresenter {
    /// Supplies the current native dialog owner, or null before a window is available.
    private final Supplier<@Nullable Component> ownerSupplier;

    /// Absolute manual release page opened after explicit acceptance.
    private final URI releasePage;

    /// Cross-platform browser action.
    private final ReleasePageLauncher releasePageLauncher;

    /// Background executor used so browser startup never blocks the Swing EDT.
    private final Executor actionExecutor;

    /// Localized prompt strings captured for one presenter lifetime.
    private final Strings strings;

    /// UI interaction boundary implemented by native Swing in production.
    private final DialogInteraction interaction;

    /// Creates an injectable update prompt presenter.
    ///
    /// @param ownerSupplier native dialog owner supplier
    /// @param releasePage absolute HTTP(S) manual release page
    /// @param releasePageLauncher browser launch action
    /// @param actionExecutor background browser-action executor
    /// @param strings localized prompt strings
    /// @param interaction prompt interaction boundary
    public SwingUpdatePromptPresenter(
            Supplier<@Nullable Component> ownerSupplier,
            URI releasePage,
            ReleasePageLauncher releasePageLauncher,
            Executor actionExecutor,
            Strings strings,
            DialogInteraction interaction) {
        this.ownerSupplier = Objects.requireNonNull(ownerSupplier, "ownerSupplier");
        this.releasePage = requireReleasePage(releasePage);
        this.releasePageLauncher = Objects.requireNonNull(releasePageLauncher, "releasePageLauncher");
        this.actionExecutor = Objects.requireNonNull(actionExecutor, "actionExecutor");
        this.strings = Objects.requireNonNull(strings, "strings");
        this.interaction = Objects.requireNonNull(interaction, "interaction");
    }

    /// Creates a native Swing presenter backed by system browser integration.
    ///
    /// Browser startup runs on the JDK common worker pool and never blocks the Swing EDT.
    ///
    /// @param ownerSupplier current native window owner supplier
    /// @param releasePage absolute HTTP(S) manual release page
    /// @return production Swing update presenter
    public static SwingUpdatePromptPresenter production(
            Supplier<@Nullable Component> ownerSupplier,
            URI releasePage) {
        return production(ownerSupplier, releasePage, ForkJoinPool.commonPool());
    }

    /// Creates a native Swing presenter backed by system browser integration and an explicit worker.
    ///
    /// @param ownerSupplier current native window owner supplier
    /// @param releasePage absolute HTTP(S) manual release page
    /// @param actionExecutor background executor for system-browser startup
    /// @return production Swing update presenter
    public static SwingUpdatePromptPresenter production(
            Supplier<@Nullable Component> ownerSupplier,
            URI releasePage,
            Executor actionExecutor) {
        return new SwingUpdatePromptPresenter(
                ownerSupplier,
                releasePage,
                ReleasePageLauncher.desktop(),
                actionExecutor,
                Strings.localized(),
                new NativeDialogInteraction());
    }

    /// Offers one available release and opens the manual page only after explicit acceptance.
    ///
    /// @param result successful update check result
    /// @return asynchronous prompt and browser outcome
    public CompletionStage<Outcome> present(UpdateCheckResult result) {
        Objects.requireNonNull(result, "result");
        if (!result.updateAvailable()) {
            return CompletableFuture.completedFuture(Outcome.NOT_APPLICABLE);
        }

        return requestDecision(result).thenCompose(decision -> decision.accepted()
                ? openReleasePage(decision.owner())
                : CompletableFuture.completedFuture(Outcome.DISMISSED));
    }

    /// Resolves the native owner and starts the prompt exclusively on the Swing EDT.
    ///
    /// @param result available update result
    /// @return stage containing the explicit decision and its stable owner reference
    private CompletionStage<PromptDecision> requestDecision(UpdateCheckResult result) {
        CompletableFuture<PromptDecision> completion = new CompletableFuture<>();
        EdtDispatcher.execute(() -> {
            try {
                @Nullable Component owner = ownerSupplier.get();
                CompletionStage<Boolean> decision = Objects.requireNonNull(
                        interaction.confirm(owner, strings, result),
                        "dialog interaction returned null");
                decision.whenComplete((@Nullable Boolean accepted, @Nullable Throwable failure) -> {
                    if (failure == null) {
                        completion.complete(new PromptDecision(owner, Boolean.TRUE.equals(accepted)));
                    } else {
                        completion.completeExceptionally(failure);
                    }
                });
            } catch (Throwable interactionFailure) {
                completion.completeExceptionally(interactionFailure);
                if (interactionFailure instanceof Error error) {
                    throw error;
                }
            }
        });
        return completion;
    }

    /// Opens the release page on the background action executor.
    ///
    /// @param owner native dialog owner retained for a possible fallback message
    /// @return browser outcome stage
    private CompletionStage<Outcome> openReleasePage(@Nullable Component owner) {
        CompletableFuture<Outcome> completion = new CompletableFuture<>();
        try {
            actionExecutor.execute(() -> {
                try {
                    releasePageLauncher.open(releasePage);
                    completion.complete(Outcome.OPENED_RELEASE_PAGE);
                } catch (RuntimeException | java.io.IOException browserFailure) {
                    showBrowserFallback(owner, browserFailure, completion);
                } catch (Error browserFailure) {
                    completion.completeExceptionally(browserFailure);
                    throw browserFailure;
                }
            });
        } catch (RuntimeException schedulingFailure) {
            showBrowserFallback(owner, schedulingFailure, completion);
        } catch (Error schedulingFailure) {
            completion.completeExceptionally(schedulingFailure);
            throw schedulingFailure;
        }
        return completion;
    }

    /// Reports a browser failure on Swing and completes the prompt outcome after the fallback is visible.
    ///
    /// @param owner native dialog owner
    /// @param browserFailure original browser failure
    /// @param completion presenter outcome future
    private void showBrowserFallback(
            @Nullable Component owner,
            Exception browserFailure,
            CompletableFuture<Outcome> completion) {
        CompletionStage<Void> fallback;
        try {
            fallback = Objects.requireNonNull(
                    interaction.reportBrowserFailure(owner, strings, releasePage),
                    "dialog interaction returned null");
        } catch (Throwable reportingFailure) {
            browserFailure.addSuppressed(reportingFailure);
            completion.completeExceptionally(browserFailure);
            if (reportingFailure instanceof Error error) {
                throw error;
            }
            return;
        }
        fallback.whenComplete((@Nullable Void ignored, @Nullable Throwable reportingFailure) -> {
            if (reportingFailure == null) {
                completion.complete(Outcome.RELEASE_PAGE_FALLBACK_SHOWN);
            } else {
                browserFailure.addSuppressed(reportingFailure);
                completion.completeExceptionally(browserFailure);
            }
        });
    }

    /// Validates a manual release page before any UI is shown.
    ///
    /// @param releasePage candidate release page
    /// @return validated release page
    private static URI requireReleasePage(URI releasePage) {
        Objects.requireNonNull(releasePage, "releasePage");
        @Nullable String scheme = releasePage.getScheme();
        if (!releasePage.isAbsolute()
                || scheme == null
                || !(scheme.toLowerCase(Locale.ROOT).equals("http")
                || scheme.toLowerCase(Locale.ROOT).equals("https"))) {
            throw new IllegalArgumentException("releasePage must be an absolute HTTP(S) URI");
        }
        return releasePage;
    }

    /// One EDT-resolved prompt owner and explicit decision.
    ///
    /// @param owner native owner resolved on the EDT, or null
    /// @param accepted whether the user explicitly accepted the offer
    @NotNullByDefault
    private record PromptDecision(@Nullable Component owner, boolean accepted) {
    }

    /// Terminal user-visible outcome of one update offer.
    @NotNullByDefault
    public enum Outcome {
        /// The supplied successful result did not represent an available update.
        NOT_APPLICABLE,

        /// The user dismissed the update offer.
        DISMISSED,

        /// The system browser accepted the manual release page.
        OPENED_RELEASE_PAGE,

        /// Browser startup failed and the native link fallback was shown.
        RELEASE_PAGE_FALLBACK_SHOWN
    }

    /// Immutable localized copy used by the native prompt.
    ///
    /// @param title update-available dialog title
    /// @param versionPattern message pattern receiving the remote version
    /// @param acceptLabel release-page action label
    /// @param cancelLabel dismiss action label
    /// @param browserFailureMessage explanation shown after browser startup fails
    @NotNullByDefault
    public record Strings(
            String title,
            String versionPattern,
            String acceptLabel,
            String cancelLabel,
            String browserFailureMessage) {
        /// Validates one complete localized copy set.
        public Strings {
            Objects.requireNonNull(title, "title");
            Objects.requireNonNull(versionPattern, "versionPattern");
            Objects.requireNonNull(acceptLabel, "acceptLabel");
            Objects.requireNonNull(cancelLabel, "cancelLabel");
            Objects.requireNonNull(browserFailureMessage, "browserFailureMessage");
        }

        /// Captures the current launcher locale through existing resource keys.
        ///
        /// @return localized prompt strings
        public static Strings localized() {
            return new Strings(
                    i18n("update.found"),
                    i18n("update.newest_version"),
                    i18n("update.accept"),
                    i18n("button.cancel"),
                    i18n("update.no_browser"));
        }

        /// Formats the remote-version message without another resource lookup.
        ///
        /// @param version remote launcher version
        /// @return localized remote-version message
        public String versionMessage(String version) {
            return String.format(versionPattern, Objects.requireNonNull(version, "version"));
        }
    }

    /// Asynchronous update prompt and browser-failure interaction boundary.
    ///
    /// The presenter invokes both methods on the Swing EDT. Implementations may complete their returned stages
    /// later, but must keep all component access on that EDT.
    @NotNullByDefault
    public interface DialogInteraction {
        /// Requests an explicit release-page decision.
        ///
        /// @param owner current native dialog owner, or null
        /// @param strings localized prompt copy
        /// @param result available update result
        /// @return stage completed with true only for explicit acceptance
        CompletionStage<Boolean> confirm(
                @Nullable Component owner,
                Strings strings,
                UpdateCheckResult result);

        /// Copies or exposes the release link and reports browser failure.
        ///
        /// @param owner current native dialog owner, or null
        /// @param strings localized prompt copy
        /// @param releasePage manual release page
        /// @return stage completed after the fallback is visible
        CompletionStage<Void> reportBrowserFailure(
                @Nullable Component owner,
                Strings strings,
                URI releasePage);
    }

    /// Native `JOptionPane` interaction running exclusively on the Swing EDT.
    @NotNullByDefault
    private static final class NativeDialogInteraction implements DialogInteraction {
        /// Shows the native update offer on the EDT.
        ///
        /// @param owner current native dialog owner, or null
        /// @param strings localized prompt copy
        /// @param result available update result
        /// @return stage completed with the explicit decision
        @Override
        public CompletionStage<Boolean> confirm(
                @Nullable Component owner,
                Strings strings,
                UpdateCheckResult result) {
            CompletableFuture<Boolean> completion = new CompletableFuture<>();
            EdtDispatcher.execute(() -> {
                try {
                    int selection = JOptionPane.showOptionDialog(
                            owner,
                            strings.versionMessage(result.remoteVersion().version()),
                            strings.title(),
                            JOptionPane.YES_NO_OPTION,
                            JOptionPane.INFORMATION_MESSAGE,
                            null,
                            new Object[]{strings.acceptLabel(), strings.cancelLabel()},
                            strings.cancelLabel());
                    completion.complete(selection == JOptionPane.YES_OPTION);
                } catch (Throwable interactionFailure) {
                    completion.completeExceptionally(interactionFailure);
                    if (interactionFailure instanceof Error error) {
                        throw error;
                    }
                }
            });
            return completion;
        }

        /// Copies the manual link when possible and shows a native fallback message on the EDT.
        ///
        /// @param owner current native dialog owner, or null
        /// @param strings localized prompt copy
        /// @param releasePage manual release page
        /// @return stage completed after fallback presentation
        @Override
        public CompletionStage<Void> reportBrowserFailure(
                @Nullable Component owner,
                Strings strings,
                URI releasePage) {
            CompletableFuture<Void> completion = new CompletableFuture<>();
            EdtDispatcher.execute(() -> {
                try {
                    try {
                        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(
                                new StringSelection(releasePage.toString()),
                                null);
                    } catch (RuntimeException ignored) {
                        // The visible URI still provides a manual fallback in headless or restricted environments.
                    }
                    JOptionPane.showMessageDialog(
                            owner,
                            strings.browserFailureMessage() + "\n" + releasePage,
                            strings.title(),
                            JOptionPane.ERROR_MESSAGE);
                    completion.complete(null);
                } catch (Throwable interactionFailure) {
                    completion.completeExceptionally(interactionFailure);
                    if (interactionFailure instanceof Error error) {
                        throw error;
                    }
                }
            });
            return completion;
        }
    }
}
