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
package space.minecraftstl.xyml.ui.swing.startup;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JEditorPane;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.Timer;
import javax.swing.WindowConstants;
import javax.swing.event.HyperlinkEvent;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;
import java.util.function.Supplier;

/// Presents the complete startup prompt sequence with native Swing option dialogs.
///
/// Every presentation method must be called on the Swing event-dispatch thread. Dialog choices are
/// converted to already-completed stages, while dialog failures are returned as failed stages. Closing
/// the agreement is a decline; closing informational, suppression, or language dialogs preserves the
/// least destructive continuation for that prompt type.
@NotNullByDefault
public final class SwingStartupPromptPresenter implements StartupPromptPresenter {
    /// Supplies the current native owner, or null when startup has no visible owner yet.
    private final Supplier<@Nullable Component> ownerSupplier;

    /// Injectable boundary around modal Swing dialogs.
    private final DialogActions dialogActions;

    /// Injectable boundary around local operating-system browser integration.
    private final LinkActions linkActions;

    /// Creates a production presenter owned by one stable Swing component.
    ///
    /// @param owner native dialog owner, or null before the application window exists
    /// @return presenter resolving the supplied owner for every prompt
    public static SwingStartupPromptPresenter forOwner(@Nullable Component owner) {
        return new SwingStartupPromptPresenter(owner);
    }

    /// Creates a production presenter whose owner can follow the application window lifecycle.
    ///
    /// The supplier is resolved once per prompt on the event-dispatch thread. It may return null
    /// while no application window exists.
    ///
    /// @param ownerSupplier current native dialog owner supplier
    public SwingStartupPromptPresenter(Supplier<@Nullable Component> ownerSupplier) {
        this(ownerSupplier, new JOptionPaneDialogActions(), new AwtLinkActions());
    }

    /// Creates a production presenter owned by one stable Swing component.
    ///
    /// @param owner native dialog owner, or null before the application window exists
    private SwingStartupPromptPresenter(@Nullable Component owner) {
        this(() -> owner, new JOptionPaneDialogActions(), new AwtLinkActions());
    }

    /// Creates a presenter with deterministic owner, dialog, and browser boundaries.
    ///
    /// @param ownerSupplier current native dialog owner supplier
    /// @param dialogActions modal dialog boundary
    /// @param linkActions external-link boundary
    SwingStartupPromptPresenter(
            Supplier<@Nullable Component> ownerSupplier,
            DialogActions dialogActions,
            LinkActions linkActions) {
        this.ownerSupplier = Objects.requireNonNull(ownerSupplier, "ownerSupplier");
        this.dialogActions = Objects.requireNonNull(dialogActions, "dialogActions");
        this.linkActions = Objects.requireNonNull(linkActions, "linkActions");
    }

    /// Presents the mandatory agreement with its external protocol link.
    ///
    /// Closing the native dialog has the same result as choosing the explicit decline action.
    ///
    /// @param strings localized agreement presentation
    /// @return completed accept or decline decision, or a failed stage after a dialog failure
    @Override
    public CompletionStage<StartupPromptDecision.Agreement> presentAgreement(
            StartupPromptStrings.Agreement strings) {
        Objects.requireNonNull(strings, "strings");
        Object @Unmodifiable [] options = {
                strings.acceptLabel(),
                strings.declineLabel()
        };
        return present(
                strings.copy(),
                Optional.of(strings.agreementLink()),
                options,
                strings.acceptLabel(),
                JOptionPane.QUESTION_MESSAGE,
                selection -> selection == 0
                        ? StartupPromptDecision.Agreement.ACCEPT
                        : StartupPromptDecision.Agreement.DECLINE);
    }

    /// Presents the invalid-cache notification as an acknowledgement-only warning.
    ///
    /// @param copy localized prompt copy
    /// @param acknowledgeLabel localized acknowledgement label
    /// @return completed acknowledgement, including after native-window close
    @Override
    public CompletionStage<StartupPromptDecision.Acknowledgement> presentInvalidCacheDirectory(
            StartupPromptCopy copy,
            String acknowledgeLabel) {
        return presentAcknowledgement(copy, acknowledgeLabel, JOptionPane.WARNING_MESSAGE);
    }

    /// Presents one classified platform warning as an acknowledgement-only dialog.
    ///
    /// @param platformPrompt platform classification requiring presentation
    /// @param copy localized classification-specific copy
    /// @param acknowledgeLabel localized acknowledgement label
    /// @return completed acknowledgement, including after native-window close
    @Override
    public CompletionStage<StartupPromptDecision.Acknowledgement> presentPlatform(
            StartupPlatformPrompt platformPrompt,
            StartupPromptCopy copy,
            String acknowledgeLabel) {
        Objects.requireNonNull(platformPrompt, "platformPrompt");
        int messageType = switch (platformPrompt) {
            case WINDOWS_ARM64, LOONGARCH -> JOptionPane.INFORMATION_MESSAGE;
            case OTHER_UNSUPPORTED -> JOptionPane.WARNING_MESSAGE;
            case NONE, MARK_SUPPORTED -> throw new IllegalArgumentException(
                    "Platform classification does not require presentation: " + platformPrompt);
        };
        return presentAcknowledgement(copy, acknowledgeLabel, messageType);
    }

    /// Presents the deprecated launcher-Java warning with an optional external download link.
    ///
    /// @param currentJavaVersion current launcher Java feature version
    /// @param minimumJavaVersion minimum supported launcher Java feature version
    /// @param strings localized warning and optional official download link
    /// @param acknowledgeLabel localized acknowledgement label
    /// @return completed acknowledgement, including after native-window close
    @Override
    public CompletionStage<StartupPromptDecision.Acknowledgement> presentDeprecatedJava(
            int currentJavaVersion,
            int minimumJavaVersion,
            StartupPromptStrings.DeprecatedJava strings,
            String acknowledgeLabel) {
        Objects.requireNonNull(strings, "strings");
        Objects.requireNonNull(acknowledgeLabel, "acknowledgeLabel");
        Object @Unmodifiable [] options = {acknowledgeLabel};
        return present(
                strings.copy(),
                strings.downloadLink(),
                options,
                acknowledgeLabel,
                JOptionPane.WARNING_MESSAGE,
                selection -> StartupPromptDecision.Acknowledgement.ACKNOWLEDGE);
    }

    /// Presents the interpreted-mode warning with optional permanent suppression.
    ///
    /// @param strings localized suppressible-warning presentation
    /// @return completed continuation or suppression decision
    @Override
    public CompletionStage<StartupPromptDecision.Suppression> presentInterpretedJava(
            StartupPromptStrings.Suppression strings) {
        Objects.requireNonNull(strings, "strings");
        return presentSuppression(strings.interpretedJava(), strings);
    }

    /// Presents the software-rendering warning with optional permanent suppression.
    ///
    /// @param strings localized suppressible-warning presentation
    /// @return completed continuation or suppression decision
    @Override
    public CompletionStage<StartupPromptDecision.Suppression> presentSoftwareRendering(
            StartupPromptStrings.Suppression strings) {
        Objects.requireNonNull(strings, "strings");
        return presentSuppression(strings.softwareRendering(), strings);
    }

    /// Presents the countdown invitation followed by a definitive language-switch confirmation.
    ///
    /// The initial switch action remains disabled for the configured number of seconds. Closing either
    /// native window keeps the current language and allows startup to continue.
    ///
    /// @param targetLanguageId installed target language identifier
    /// @param strings localized invitation and outcome labels
    /// @return completed switch-or-keep decision
    @Override
    public CompletionStage<StartupPromptDecision.AprilFools> presentAprilFools(
            String targetLanguageId,
            StartupPromptStrings.AprilFools strings) {
        Objects.requireNonNull(targetLanguageId, "targetLanguageId");
        Objects.requireNonNull(strings, "strings");
        EdtDispatcher.requireEventDispatchThread();
        try {
            @Nullable Component owner = ownerSupplier.get();
            JPanel content = createContent(owner, strings.initialCopy(), Optional.empty());
            CompletionStage<Integer> invitation = Objects.requireNonNull(
                    dialogActions.showCountdownOptionDialog(
                            owner,
                            content,
                            strings.initialCopy().title(),
                            JOptionPane.QUESTION_MESSAGE,
                            strings.countdownLabelPattern(),
                            strings.initialSwitchLabel(),
                            strings.initialKeepLabel(),
                            strings.countdownSeconds()),
                    "dialogActions returned null countdown completion");
            return invitation.thenCompose(selection ->
                    completeAprilFoolsInvitation(selection, strings));
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(failure);
        }
    }

    /// Maps the initial invitation and presents final confirmation only after an unlocked switch choice.
    ///
    /// @param selection initial native option index
    /// @param strings localized two-step invitation presentation
    /// @return completed keep decision or final confirmation stage
    private CompletionStage<StartupPromptDecision.AprilFools> completeAprilFoolsInvitation(
            int selection,
            StartupPromptStrings.AprilFools strings) {
        EdtDispatcher.requireEventDispatchThread();
        if (selection != 0) {
            return CompletableFuture.completedFuture(
                    StartupPromptDecision.AprilFools.KEEP_LANGUAGE);
        }

        Object @Unmodifiable [] options = {
                strings.confirmationSwitchLabel(),
                strings.confirmationKeepLabel()
        };
        return present(
                strings.confirmationCopy(),
                Optional.empty(),
                options,
                strings.confirmationKeepLabel(),
                JOptionPane.QUESTION_MESSAGE,
                confirmation -> confirmation == 0
                        ? StartupPromptDecision.AprilFools.SWITCH_LANGUAGE
                        : StartupPromptDecision.AprilFools.KEEP_LANGUAGE);
    }

    /// Presents one acknowledgement-only prompt.
    ///
    /// @param copy localized prompt copy
    /// @param acknowledgeLabel localized acknowledgement label
    /// @param messageType native message severity
    /// @return completed acknowledgement or failed dialog stage
    private CompletionStage<StartupPromptDecision.Acknowledgement> presentAcknowledgement(
            StartupPromptCopy copy,
            String acknowledgeLabel,
            int messageType) {
        Objects.requireNonNull(copy, "copy");
        Objects.requireNonNull(acknowledgeLabel, "acknowledgeLabel");
        Object @Unmodifiable [] options = {acknowledgeLabel};
        return present(
                copy,
                Optional.empty(),
                options,
                acknowledgeLabel,
                messageType,
                selection -> StartupPromptDecision.Acknowledgement.ACKNOWLEDGE);
    }

    /// Presents one warning that can be suppressed permanently.
    ///
    /// @param copy localized warning copy
    /// @param strings localized action labels
    /// @return completed continuation or suppression decision
    private CompletionStage<StartupPromptDecision.Suppression> presentSuppression(
            StartupPromptCopy copy,
            StartupPromptStrings.Suppression strings) {
        Object @Unmodifiable [] options = {
                strings.continueLabel(),
                strings.suppressLabel()
        };
        return present(
                copy,
                Optional.empty(),
                options,
                strings.continueLabel(),
                JOptionPane.WARNING_MESSAGE,
                selection -> selection == 1
                        ? StartupPromptDecision.Suppression.DO_NOT_SHOW_AGAIN
                        : StartupPromptDecision.Suppression.CONTINUE);
    }

    /// Displays one modal option dialog and converts its selected index to a typed decision stage.
    ///
    /// @param copy localized prompt copy
    /// @param link optional visible external link
    /// @param options localized native dialog options
    /// @param initialValue initially focused option
    /// @param messageType native message severity
    /// @param decisionMapper selection-to-decision mapping
    /// @param <D> prompt-specific decision type
    /// @return completed decision or failed dialog stage
    private <D extends StartupPromptDecision> CompletionStage<D> present(
            StartupPromptCopy copy,
            Optional<StartupPromptStrings.Link> link,
            Object @Unmodifiable [] options,
            Object initialValue,
            int messageType,
            IntFunction<D> decisionMapper) {
        EdtDispatcher.requireEventDispatchThread();
        Objects.requireNonNull(copy, "copy");
        Objects.requireNonNull(link, "link");
        Objects.requireNonNull(options, "options");
        Objects.requireNonNull(initialValue, "initialValue");
        Objects.requireNonNull(decisionMapper, "decisionMapper");

        try {
            @Nullable Component owner = ownerSupplier.get();
            JPanel content = createContent(owner, copy, link);
            int selection = dialogActions.showOptionDialog(
                    owner,
                    content,
                    copy.title(),
                    JOptionPane.DEFAULT_OPTION,
                    messageType,
                    options,
                    initialValue);
            D decision = Objects.requireNonNull(
                    decisionMapper.apply(selection),
                    "decisionMapper returned null");
            return CompletableFuture.completedFuture(decision);
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(failure);
        }
    }

    /// Builds one compact, wrapping Swing body with an optional browser link action.
    ///
    /// @param owner current native dialog owner, or null
    /// @param copy localized prompt copy
    /// @param link optional visible external link
    /// @return configured prompt content
    private JPanel createContent(
            @Nullable Component owner,
            StartupPromptCopy copy,
            Optional<StartupPromptStrings.Link> link) {
        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));

        JEditorPane message = new JEditorPane();
        message.setContentType("text/html");
        message.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, true);
        message.setText(toHtmlDocument(copy.message()));
        message.setEditable(false);
        message.setFocusable(false);
        message.setOpaque(false);
        message.setBorder(null);
        message.setAlignmentX(Component.LEFT_ALIGNMENT);
        message.setSize(new Dimension(480, Short.MAX_VALUE));
        Dimension preferredMessageSize = message.getPreferredSize();
        message.setPreferredSize(new Dimension(
                480,
                Math.max(48, preferredMessageSize.height)));
        message.addHyperlinkListener(event -> {
            if (event.getEventType() == HyperlinkEvent.EventType.ACTIVATED) {
                openEmbeddedLink(owner, copy.title(), event);
            }
        });
        content.add(message);

        link.ifPresent(linkValue -> {
            content.add(Box.createRigidArea(new Dimension(0, 8)));
            content.add(createLinkButton(owner, copy.title(), linkValue));
        });
        return content;
    }

    /// Wraps trusted bundled localized HTML while preserving localized line breaks.
    ///
    /// Startup prompt text comes from the launcher's bundled locale resources rather than remote
    /// content. `JEditorPane` performs the HTML parsing and link discovery.
    ///
    /// @param message localized plain or limited-HTML prompt body
    /// @return complete HTML document for Swing rendering
    private static String toHtmlDocument(String message) {
        return "<html><body>" + message.replace("\n", "<br>") + "</body></html>";
    }

    /// Resolves one HTML hyperlink event to a URI and delegates it to the desktop boundary.
    ///
    /// Custom schemes such as the Microsoft Store URI may not have a `URL` representation, so the
    /// event description remains the canonical fallback.
    ///
    /// @param owner current native dialog owner, or null
    /// @param dialogTitle owning dialog title
    /// @param event activated hyperlink event
    private void openEmbeddedLink(
            @Nullable Component owner,
            String dialogTitle,
            HyperlinkEvent event) {
        @Nullable URL eventUrl = event.getURL();
        @Nullable String description = event.getDescription();
        try {
            URI destination = eventUrl == null
                    ? URI.create(Objects.requireNonNull(description, "hyperlink description"))
                    : eventUrl.toURI();
            openLink(owner, dialogTitle, destination);
        } catch (Exception failure) {
            reportLinkFailure(owner, dialogTitle, failure);
        }
    }

    /// Builds one borderless, keyboard-accessible external-link button.
    ///
    /// @param owner current native dialog owner, or null
    /// @param dialogTitle owning dialog title used for browser failures
    /// @param link visible label and destination
    /// @return configured link button
    private JButton createLinkButton(
            @Nullable Component owner,
            String dialogTitle,
            StartupPromptStrings.Link link) {
        JButton button = new JButton(link.label());
        button.putClientProperty("JButton.buttonType", "borderless");
        button.setBorderPainted(false);
        button.setContentAreaFilled(false);
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        button.setHorizontalAlignment(JButton.LEFT);
        button.setAlignmentX(Component.LEFT_ALIGNMENT);
        button.setToolTipText(link.destination().toString());
        button.getAccessibleContext().setAccessibleDescription(link.destination().toString());
        button.addActionListener(event -> openLink(owner, dialogTitle, link.destination()));
        return button;
    }

    /// Opens one external link and reports a local desktop failure in the owning Swing dialog.
    ///
    /// @param owner current native dialog owner, or null
    /// @param dialogTitle owning dialog title
    /// @param destination external destination
    private void openLink(
            @Nullable Component owner,
            String dialogTitle,
            URI destination) {
        EdtDispatcher.requireEventDispatchThread();
        try {
            linkActions.browse(destination);
        } catch (IOException | RuntimeException failure) {
            reportLinkFailure(owner, dialogTitle, failure);
        }
    }

    /// Reports one malformed or unavailable external link without closing the owning prompt.
    ///
    /// @param owner current native dialog owner, or null
    /// @param dialogTitle owning dialog title
    /// @param failure link resolution or desktop failure
    private void reportLinkFailure(
            @Nullable Component owner,
            String dialogTitle,
            Exception failure) {
        @Nullable String localizedMessage = failure.getLocalizedMessage();
        String detail = localizedMessage == null || localizedMessage.isBlank()
                ? failure.getClass().getSimpleName()
                : localizedMessage;
        dialogActions.showMessageDialog(
                owner,
                detail,
                dialogTitle,
                JOptionPane.ERROR_MESSAGE);
    }

    /// Applies one countdown state to the initial positive button.
    ///
    /// This package-visible state transition is independently testable without creating a native
    /// dialog in a headless test environment.
    ///
    /// @param positiveButton positive action button
    /// @param positiveLabel final localized positive label
    /// @param countdownLabelPattern localized format receiving remaining seconds
    /// @param remainingSeconds non-negative remaining delay
    static void updateCountdownButton(
            JButton positiveButton,
            String positiveLabel,
            String countdownLabelPattern,
            int remainingSeconds) {
        EdtDispatcher.requireEventDispatchThread();
        Objects.requireNonNull(positiveButton, "positiveButton");
        Objects.requireNonNull(positiveLabel, "positiveLabel");
        Objects.requireNonNull(countdownLabelPattern, "countdownLabelPattern");
        boolean locked = remainingSeconds > 0;
        positiveButton.setEnabled(!locked);
        positiveButton.setText(locked
                ? countdownLabelPattern.formatted(remainingSeconds)
                : positiveLabel);
    }

    /// Package-private modal dialog boundary for deterministic headless tests.
    @NotNullByDefault
    interface DialogActions {
        /// Shows one custom option dialog.
        ///
        /// @param owner native owner, or null
        /// @param message configured Swing content
        /// @param title native dialog title
        /// @param optionType one `JOptionPane` option-type constant
        /// @param messageType one `JOptionPane` message-type constant
        /// @param options localized option values
        /// @param initialValue initially focused option
        /// @return selected option index or `JOptionPane.CLOSED_OPTION`
        int showOptionDialog(
                @Nullable Component owner,
                Object message,
                String title,
                int optionType,
                int messageType,
                Object @Unmodifiable [] options,
                Object initialValue);

        /// Shows a modal option dialog whose positive action unlocks after a countdown.
        ///
        /// The returned stage must complete on the Swing event-dispatch thread.
        ///
        /// @param owner native owner, or null
        /// @param message configured Swing content
        /// @param title native dialog title
        /// @param messageType one `JOptionPane` message-type constant
        /// @param countdownLabelPattern localized format receiving remaining seconds
        /// @param positiveLabel positive option label restored after countdown
        /// @param negativeLabel immediately available negative option label
        /// @param countdownSeconds positive delay before the positive option becomes available
        /// @return eventual selected option index or `JOptionPane.CLOSED_OPTION`
        CompletionStage<Integer> showCountdownOptionDialog(
                @Nullable Component owner,
                Object message,
                String title,
                int messageType,
                String countdownLabelPattern,
                String positiveLabel,
                String negativeLabel,
                int countdownSeconds);

        /// Shows one link-opening failure.
        ///
        /// @param owner native owner, or null
        /// @param message failure detail
        /// @param title owning dialog title
        /// @param messageType one `JOptionPane` message-type constant
        void showMessageDialog(
                @Nullable Component owner,
                Object message,
                String title,
                int messageType);
    }

    /// Package-private browser boundary for deterministic headless tests.
    @FunctionalInterface
    @NotNullByDefault
    interface LinkActions {
        /// Opens one external link through the operating system.
        ///
        /// @param destination external destination
        /// @throws IOException when the platform browser cannot be opened
        void browse(URI destination) throws IOException;
    }

    /// Production `JOptionPane` adapter.
    @NotNullByDefault
    private static final class JOptionPaneDialogActions implements DialogActions {
        /// Creates a stateless native-dialog adapter.
        private JOptionPaneDialogActions() {
        }

        /// Shows one production option dialog.
        @Override
        public int showOptionDialog(
                @Nullable Component owner,
                Object message,
                String title,
                int optionType,
                int messageType,
                Object @Unmodifiable [] options,
                Object initialValue) {
            return JOptionPane.showOptionDialog(
                    owner,
                    message,
                    title,
                    optionType,
                    messageType,
                    null,
                    options,
                    initialValue);
        }

        /// Shows one application-modal production countdown dialog driven by a Swing timer.
        @Override
        public CompletionStage<Integer> showCountdownOptionDialog(
                @Nullable Component owner,
                Object message,
                String title,
                int messageType,
                String countdownLabelPattern,
                String positiveLabel,
                String negativeLabel,
                int countdownSeconds) {
            EdtDispatcher.requireEventDispatchThread();
            if (countdownSeconds <= 0) {
                return CompletableFuture.failedFuture(
                        new IllegalArgumentException("countdownSeconds must be positive"));
            }

            JButton positiveButton = new JButton();
            updateCountdownButton(
                    positiveButton,
                    positiveLabel,
                    countdownLabelPattern,
                    countdownSeconds);
            JButton negativeButton = new JButton(negativeLabel);
            Object @Unmodifiable [] options = {positiveButton, negativeButton};
            JOptionPane optionPane = new JOptionPane(
                    message,
                    messageType,
                    JOptionPane.DEFAULT_OPTION,
                    null,
                    options,
                    negativeButton);
            JDialog dialog = optionPane.createDialog(owner, title);
            dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);

            CompletableFuture<Integer> completion = new CompletableFuture<>();
            AtomicInteger remainingSeconds = new AtomicInteger(countdownSeconds);
            Timer timer = new Timer(1000, event -> {
                int remaining = remainingSeconds.decrementAndGet();
                updateCountdownButton(
                        positiveButton,
                        positiveLabel,
                        countdownLabelPattern,
                        remaining);
                if (remaining <= 0) {
                    ((Timer) event.getSource()).stop();
                }
            });
            timer.setRepeats(true);

            positiveButton.addActionListener(event -> completeCountdownDialog(
                    completion, dialog, timer, 0));
            negativeButton.addActionListener(event -> completeCountdownDialog(
                    completion, dialog, timer, 1));
            dialog.addWindowListener(new WindowAdapter() {
                /// Maps native close to the conservative negative result.
                @Override
                public void windowClosed(WindowEvent event) {
                    completeCountdownDialog(
                            completion, dialog, timer, JOptionPane.CLOSED_OPTION);
                }
            });

            try {
                timer.start();
                dialog.setVisible(true);
                completeCountdownDialog(
                        completion, dialog, timer, JOptionPane.CLOSED_OPTION);
            } catch (RuntimeException failure) {
                timer.stop();
                completion.completeExceptionally(failure);
                dialog.dispose();
            }
            return completion;
        }

        /// Shows one production browser-failure dialog.
        @Override
        public void showMessageDialog(
                @Nullable Component owner,
                Object message,
                String title,
                int messageType) {
            JOptionPane.showMessageDialog(owner, message, title, messageType);
        }

        /// Completes and disposes one countdown dialog exactly once.
        ///
        /// @param completion countdown result
        /// @param dialog native dialog
        /// @param timer countdown timer
        /// @param selection selected option index
        private static void completeCountdownDialog(
                CompletableFuture<Integer> completion,
                JDialog dialog,
                Timer timer,
                int selection) {
            EdtDispatcher.requireEventDispatchThread();
            if (completion.complete(selection)) {
                timer.stop();
                dialog.dispose();
            }
        }
    }

    /// Production adapter around `java.awt.Desktop#browse(URI)`.
    @NotNullByDefault
    private static final class AwtLinkActions implements LinkActions {
        /// Creates an adapter that resolves platform integration only after a link click.
        private AwtLinkActions() {
        }

        /// Opens one destination with the operating-system browser.
        @Override
        public void browse(URI destination) throws IOException {
            if (!Desktop.isDesktopSupported()) {
                throw new UnsupportedOperationException("Desktop integration is unavailable");
            }
            Desktop desktop = Desktop.getDesktop();
            if (!desktop.isSupported(Desktop.Action.BROWSE)) {
                throw new UnsupportedOperationException("Desktop browsing is unavailable");
            }
            desktop.browse(destination);
        }
    }
}
