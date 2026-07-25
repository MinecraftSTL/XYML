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
import org.junit.jupiter.api.Test;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;

import javax.swing.JButton;
import javax.swing.JEditorPane;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.event.HyperlinkEvent;
import javax.swing.event.HyperlinkListener;
import java.awt.Component;
import java.awt.Container;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Headless coverage for Swing startup decisions, countdown state, links, and EDT confinement.
@NotNullByDefault
public final class SwingStartupPromptPresenterTest {
    /// Agreement and acknowledgement close results retain their prompt-specific semantics.
    @Test
    public void mapsAgreementAndAcknowledgementSelections() {
        FakeDialogActions dialogs = new FakeDialogActions();
        dialogs.optionResults.add(0);
        dialogs.optionResults.add(1);
        dialogs.optionResults.add(JOptionPane.CLOSED_OPTION);
        dialogs.optionResults.add(JOptionPane.CLOSED_OPTION);
        SwingStartupPromptPresenter presenter = presenter(dialogs, new FakeLinkActions());

        StartupPromptDecision.Agreement accepted = decisionOnEventDispatchThread(
                () -> presenter.presentAgreement(agreement()));
        StartupPromptDecision.Agreement declined = decisionOnEventDispatchThread(
                () -> presenter.presentAgreement(agreement()));
        StartupPromptDecision.Agreement closed = decisionOnEventDispatchThread(
                () -> presenter.presentAgreement(agreement()));
        StartupPromptDecision.Acknowledgement acknowledged = decisionOnEventDispatchThread(
                () -> presenter.presentInvalidCacheDirectory(
                        new StartupPromptCopy("Cache", "Restored"), "OK"));

        assertAll(
                () -> assertEquals(StartupPromptDecision.Agreement.ACCEPT, accepted),
                () -> assertEquals(StartupPromptDecision.Agreement.DECLINE, declined),
                () -> assertEquals(StartupPromptDecision.Agreement.DECLINE, closed),
                () -> assertEquals(
                        StartupPromptDecision.Acknowledgement.ACKNOWLEDGE,
                        acknowledged),
                () -> assertTrue(dialogs.optionCalls.stream()
                        .allMatch(OptionCall::onEventDispatchThread)));
    }

    /// Closing a suppressible warning continues, while its explicit second option persists suppression.
    @Test
    public void mapsSuppressionSelectionsConservatively() {
        FakeDialogActions dialogs = new FakeDialogActions();
        dialogs.optionResults.add(1);
        dialogs.optionResults.add(JOptionPane.CLOSED_OPTION);
        SwingStartupPromptPresenter presenter = presenter(dialogs, new FakeLinkActions());

        StartupPromptDecision.Suppression suppressed = decisionOnEventDispatchThread(
                () -> presenter.presentInterpretedJava(suppression()));
        StartupPromptDecision.Suppression closed = decisionOnEventDispatchThread(
                () -> presenter.presentSoftwareRendering(suppression()));

        assertAll(
                () -> assertEquals(
                        StartupPromptDecision.Suppression.DO_NOT_SHOW_AGAIN,
                        suppressed),
                () -> assertEquals(StartupPromptDecision.Suppression.CONTINUE, closed));
    }

    /// Informational platform classes retain their old severity while unsupported platforms warn.
    @Test
    public void preservesPlatformSeverities() {
        FakeDialogActions dialogs = new FakeDialogActions();
        dialogs.optionResults.add(0);
        dialogs.optionResults.add(0);
        dialogs.optionResults.add(0);
        SwingStartupPromptPresenter presenter = presenter(dialogs, new FakeLinkActions());
        StartupPromptCopy copy = new StartupPromptCopy("Platform", "Platform body");

        decisionOnEventDispatchThread(() -> presenter.presentPlatform(
                StartupPlatformPrompt.WINDOWS_ARM64, copy, "OK"));
        decisionOnEventDispatchThread(() -> presenter.presentPlatform(
                StartupPlatformPrompt.LOONGARCH, copy, "OK"));
        decisionOnEventDispatchThread(() -> presenter.presentPlatform(
                StartupPlatformPrompt.OTHER_UNSUPPORTED, copy, "OK"));

        assertAll(
                () -> assertEquals(
                        JOptionPane.INFORMATION_MESSAGE,
                        dialogs.optionCalls.get(0).messageType()),
                () -> assertEquals(
                        JOptionPane.INFORMATION_MESSAGE,
                        dialogs.optionCalls.get(1).messageType()),
                () -> assertEquals(
                        JOptionPane.WARNING_MESSAGE,
                        dialogs.optionCalls.get(2).messageType()),
                () -> assertThrows(IllegalArgumentException.class, () ->
                        presenter.presentPlatform(StartupPlatformPrompt.NONE, copy, "OK")));
    }

    /// Agreement, Java-download, and embedded platform links all delegate locally on the EDT.
    @Test
    public void opensStructuredAndEmbeddedLinksOnEventDispatchThread() {
        FakeDialogActions dialogs = new FakeDialogActions();
        FakeLinkActions links = new FakeLinkActions();
        SwingStartupPromptPresenter presenter = presenter(dialogs, links);
        dialogs.optionResults.add(0);
        dialogs.optionResults.add(0);
        dialogs.optionResults.add(0);

        dialogs.contentAction = SwingStartupPromptPresenterTest::clickFirstButton;
        decisionOnEventDispatchThread(() -> presenter.presentAgreement(agreement()));
        decisionOnEventDispatchThread(() -> presenter.presentDeprecatedJava(
                16, 17, deprecatedJava(), "OK"));

        URI storeUri = URI.create("ms-windows-store://pdp/?productid=example");
        dialogs.contentAction = content -> activateFirstEmbeddedLink(content, storeUri);
        decisionOnEventDispatchThread(() -> presenter.presentPlatform(
                StartupPlatformPrompt.WINDOWS_ARM64,
                new StartupPromptCopy(
                        "Windows on Arm",
                        "Install the <b>compatibility</b> <a href=\"" + storeUri +
                                "\">package</a>."),
                "OK"));

        assertAll(
                () -> assertEquals(
                        List.of(
                                agreement().agreementLink().destination(),
                                deprecatedJava().downloadLink().orElseThrow().destination(),
                                storeUri),
                        links.destinations),
                () -> assertEquals(List.of(true, true, true), links.onEventDispatchThread),
                () -> assertTrue(dialogs.optionCalls.stream()
                        .map(OptionCall::message)
                        .map(JPanel.class::cast)
                        .flatMap(panel -> descendants(panel, JEditorPane.class).stream())
                        .anyMatch(editor -> editor.getText().contains("compatibility"))));
    }

    /// A desktop failure remains inside Swing and is shown without changing the prompt decision.
    @Test
    public void reportsLinkFailureInsideOwningDialog() {
        FakeDialogActions dialogs = new FakeDialogActions();
        FakeLinkActions links = new FakeLinkActions();
        links.failure = new IOException("browser unavailable");
        dialogs.optionResults.add(0);
        dialogs.contentAction = SwingStartupPromptPresenterTest::clickFirstButton;
        SwingStartupPromptPresenter presenter = presenter(dialogs, links);

        StartupPromptDecision.Agreement decision = decisionOnEventDispatchThread(
                () -> presenter.presentAgreement(agreement()));

        MessageCall failure = Objects.requireNonNull(dialogs.messageCall);
        assertAll(
                () -> assertEquals(StartupPromptDecision.Agreement.ACCEPT, decision),
                () -> assertEquals("browser unavailable", failure.message()),
                () -> assertEquals("Agreement", failure.title()),
                () -> assertEquals(JOptionPane.ERROR_MESSAGE, failure.messageType()),
                () -> assertTrue(failure.onEventDispatchThread()));
    }

    /// The April Fools switch result requires both the countdown invitation and final confirmation.
    @Test
    public void requiresTwoAprilFoolsConfirmations() {
        FakeDialogActions dialogs = new FakeDialogActions();
        dialogs.countdownResult = 0;
        dialogs.optionResults.add(0);
        SwingStartupPromptPresenter presenter = presenter(dialogs, new FakeLinkActions());

        StartupPromptDecision.AprilFools decision = decisionOnEventDispatchThread(
                () -> presenter.presentAprilFools("lzh", aprilFools()));

        CountdownCall invitation = Objects.requireNonNull(dialogs.countdownCall);
        OptionCall confirmation = dialogs.optionCalls.get(0);
        assertAll(
                () -> assertEquals(StartupPromptDecision.AprilFools.SWITCH_LANGUAGE, decision),
                () -> assertEquals("Invitation", invitation.title()),
                () -> assertEquals(10, invitation.countdownSeconds()),
                () -> assertEquals("Switch in %d", invitation.countdownLabelPattern()),
                () -> assertEquals("Start switch", invitation.positiveLabel()),
                () -> assertEquals("Keep initially", invitation.negativeLabel()),
                () -> assertTrue(invitation.onEventDispatchThread()),
                () -> assertEquals("Confirmation", confirmation.title()),
                () -> assertEquals(
                        List.of("Confirm switch", "Keep finally"),
                        confirmation.options()));
    }

    /// Closing either April Fools step keeps the current language and skips later dialogs as needed.
    @Test
    public void mapsEitherAprilFoolsCloseToKeepLanguage() {
        FakeDialogActions initialCloseDialogs = new FakeDialogActions();
        initialCloseDialogs.countdownResult = JOptionPane.CLOSED_OPTION;
        SwingStartupPromptPresenter initialClosePresenter = presenter(
                initialCloseDialogs, new FakeLinkActions());

        StartupPromptDecision.AprilFools initialClose = decisionOnEventDispatchThread(
                () -> initialClosePresenter.presentAprilFools("lzh", aprilFools()));

        FakeDialogActions finalCloseDialogs = new FakeDialogActions();
        finalCloseDialogs.countdownResult = 0;
        finalCloseDialogs.optionResults.add(JOptionPane.CLOSED_OPTION);
        SwingStartupPromptPresenter finalClosePresenter = presenter(
                finalCloseDialogs, new FakeLinkActions());
        StartupPromptDecision.AprilFools finalClose = decisionOnEventDispatchThread(
                () -> finalClosePresenter.presentAprilFools("lzh", aprilFools()));

        assertAll(
                () -> assertEquals(StartupPromptDecision.AprilFools.KEEP_LANGUAGE, initialClose),
                () -> assertEquals(List.of(), initialCloseDialogs.optionCalls),
                () -> assertEquals(StartupPromptDecision.AprilFools.KEEP_LANGUAGE, finalClose),
                () -> assertEquals(1, finalCloseDialogs.optionCalls.size()));
    }

    /// Countdown state disables and labels the switch action until the final zero tick.
    @Test
    public void locksPositiveActionUntilCountdownExpires() {
        JButton button = valueOnEventDispatchThread(JButton::new);

        onEventDispatchThread(() -> SwingStartupPromptPresenter.updateCountdownButton(
                button, "Start switch", "Switch in %d", 10));
        ButtonState locked = valueOnEventDispatchThread(
                () -> new ButtonState(button.getText(), button.isEnabled()));
        onEventDispatchThread(() -> SwingStartupPromptPresenter.updateCountdownButton(
                button, "Start switch", "Switch in %d", 0));
        ButtonState unlocked = valueOnEventDispatchThread(
                () -> new ButtonState(button.getText(), button.isEnabled()));

        assertAll(
                () -> assertEquals(new ButtonState("Switch in 10", false), locked),
                () -> assertEquals(new ButtonState("Start switch", true), unlocked),
                () -> assertThrows(IllegalStateException.class, () ->
                        SwingStartupPromptPresenter.updateCountdownButton(
                                button, "Start switch", "Switch in %d", 1)));
    }

    /// Every public presentation rejects direct calls outside the Swing event-dispatch thread.
    @Test
    public void rejectsPresentationOutsideEventDispatchThread() {
        SwingStartupPromptPresenter presenter = presenter(
                new FakeDialogActions(), new FakeLinkActions());
        StartupPromptCopy copy = new StartupPromptCopy("Title", "Body");

        assertAll(
                () -> assertThrows(IllegalStateException.class, () ->
                        presenter.presentAgreement(agreement())),
                () -> assertThrows(IllegalStateException.class, () ->
                        presenter.presentInvalidCacheDirectory(copy, "OK")),
                () -> assertThrows(IllegalStateException.class, () ->
                        presenter.presentPlatform(
                                StartupPlatformPrompt.WINDOWS_ARM64, copy, "OK")),
                () -> assertThrows(IllegalStateException.class, () ->
                        presenter.presentDeprecatedJava(16, 17, deprecatedJava(), "OK")),
                () -> assertThrows(IllegalStateException.class, () ->
                        presenter.presentInterpretedJava(suppression())),
                () -> assertThrows(IllegalStateException.class, () ->
                        presenter.presentSoftwareRendering(suppression())),
                () -> assertThrows(IllegalStateException.class, () ->
                        presenter.presentAprilFools("lzh", aprilFools())));
    }

    /// A native dialog failure is represented by the returned stage with its original identity.
    @Test
    public void preservesDialogFailureIdentityInReturnedStage() {
        IllegalStateException expected = new IllegalStateException("dialog failed");
        FakeDialogActions dialogs = new FakeDialogActions();
        dialogs.optionFailure = expected;
        SwingStartupPromptPresenter presenter = presenter(dialogs, new FakeLinkActions());

        CompletionStage<StartupPromptDecision.Agreement> stage = valueOnEventDispatchThread(
                () -> presenter.presentAgreement(agreement()));
        CompletionException failure = assertThrows(
                CompletionException.class,
                () -> stage.toCompletableFuture().join());

        assertSame(expected, failure.getCause());
    }

    /// April Fools presentation rejects a non-positive countdown before any UI integration.
    @Test
    public void rejectsNonPositiveAprilFoolsCountdown() {
        StartupPromptCopy copy = new StartupPromptCopy("Title", "Body");
        assertThrows(IllegalArgumentException.class, () -> new StartupPromptStrings.AprilFools(
                copy,
                copy,
                0,
                "Switch in %d",
                "Start switch",
                "Keep initially",
                "Confirm switch",
                "Keep finally"));
    }

    /// April Fools presentation rejects a malformed localized countdown pattern at construction.
    @Test
    public void rejectsMalformedAprilFoolsCountdownPattern() {
        StartupPromptCopy copy = new StartupPromptCopy("Title", "Body");
        assertThrows(IllegalArgumentException.class, () -> new StartupPromptStrings.AprilFools(
                copy,
                copy,
                10,
                "Switch %q",
                "Start switch",
                "Keep initially",
                "Confirm switch",
                "Keep finally"));
    }

    /// Creates one presenter with a real Swing owner and deterministic boundaries.
    ///
    /// @param dialogs dialog fake
    /// @param links browser fake
    /// @return configured presenter
    private static SwingStartupPromptPresenter presenter(
            FakeDialogActions dialogs,
            FakeLinkActions links) {
        JPanel owner = valueOnEventDispatchThread(JPanel::new);
        return new SwingStartupPromptPresenter(() -> owner, dialogs, links);
    }

    /// Returns deterministic agreement presentation.
    ///
    /// @return agreement strings
    private static StartupPromptStrings.Agreement agreement() {
        return new StartupPromptStrings.Agreement(
                new StartupPromptCopy("Agreement", "Read the agreement"),
                new StartupPromptStrings.Link(
                        "Open agreement",
                        URI.create("https://example.invalid/agreement")),
                "Accept",
                "Decline");
    }

    /// Returns deterministic deprecated-Java presentation.
    ///
    /// @return deprecated-Java strings
    private static StartupPromptStrings.DeprecatedJava deprecatedJava() {
        return new StartupPromptStrings.DeprecatedJava(
                new StartupPromptCopy("Java", "Update Java"),
                Optional.of(new StartupPromptStrings.Link(
                        "Download Java",
                        URI.create("https://example.invalid/java"))));
    }

    /// Returns deterministic suppressible-warning presentation.
    ///
    /// @return suppression strings
    private static StartupPromptStrings.Suppression suppression() {
        return new StartupPromptStrings.Suppression(
                new StartupPromptCopy("Interpreted", "No JIT"),
                new StartupPromptCopy("Software", "No GPU"),
                "Continue",
                "Suppress");
    }

    /// Returns deterministic two-step April Fools presentation.
    ///
    /// @return April Fools strings
    private static StartupPromptStrings.AprilFools aprilFools() {
        return new StartupPromptStrings.AprilFools(
                new StartupPromptCopy("Invitation", "Try another language"),
                new StartupPromptCopy("Confirmation", "Restart and switch?"),
                10,
                "Switch in %d",
                "Start switch",
                "Keep initially",
                "Confirm switch",
                "Keep finally");
    }

    /// Clicks the first explicit link button in configured prompt content.
    ///
    /// @param content configured prompt content
    private static void clickFirstButton(Object content) {
        JPanel panel = (JPanel) content;
        descendants(panel, JButton.class).get(0).doClick();
    }

    /// Activates the first embedded HTML hyperlink with a custom-scheme destination.
    ///
    /// @param content configured prompt content
    /// @param destination custom-scheme destination
    private static void activateFirstEmbeddedLink(Object content, URI destination) {
        JPanel panel = (JPanel) content;
        JEditorPane editor = descendants(panel, JEditorPane.class).get(0);
        HyperlinkEvent event = new HyperlinkEvent(
                editor,
                HyperlinkEvent.EventType.ACTIVATED,
                null,
                destination.toString());
        for (HyperlinkListener listener : editor.getHyperlinkListeners()) {
            listener.hyperlinkUpdate(event);
        }
    }

    /// Returns all descendants assignable to one Swing component type.
    ///
    /// @param root traversal root
    /// @param type required component type
    /// @param <T> component type
    /// @return immutable descendants in tree order
    private static <T extends Component> @Unmodifiable List<T> descendants(
            Component root,
            Class<T> type) {
        List<T> result = new ArrayList<>();
        if (type.isInstance(root)) {
            result.add(type.cast(root));
        }
        if (root instanceof Container container) {
            for (Component child : container.getComponents()) {
                result.addAll(descendants(child, type));
            }
        }
        return List.copyOf(result);
    }

    /// Presents and resolves one already-completed fake dialog stage on the EDT.
    ///
    /// @param stageSupplier stage-producing presentation
    /// @param <D> decision type
    /// @return resolved decision
    private static <D extends StartupPromptDecision> D decisionOnEventDispatchThread(
            Supplier<CompletionStage<D>> stageSupplier) {
        return valueOnEventDispatchThread(
                () -> stageSupplier.get().toCompletableFuture().join());
    }

    /// Runs one non-null value-producing operation synchronously on the EDT.
    ///
    /// @param operation operation to run
    /// @param <T> non-null result type
    /// @return operation result
    private static <T extends Object> T valueOnEventDispatchThread(Supplier<T> operation) {
        AtomicReference<@Nullable T> result = new AtomicReference<>();
        EdtDispatcher.executeAndWait(() -> result.set(operation.get()));
        return Objects.requireNonNull(result.get(), "EDT operation returned null");
    }

    /// Runs one operation synchronously on the EDT.
    ///
    /// @param operation operation to run
    private static void onEventDispatchThread(Runnable operation) {
        EdtDispatcher.executeAndWait(operation);
    }

    /// Immutable positive-button snapshot.
    ///
    /// @param text visible button text
    /// @param enabled whether the button accepts input
    @NotNullByDefault
    private record ButtonState(String text, boolean enabled) {
    }

    /// One captured synchronous option dialog.
    ///
    /// @param owner native owner, or null
    /// @param message configured Swing content
    /// @param title native title
    /// @param optionType native option type
    /// @param messageType native message severity
    /// @param options immutable localized options
    /// @param initialValue initially focused option
    /// @param onEventDispatchThread whether invocation occurred on the EDT
    @NotNullByDefault
    private record OptionCall(
            @Nullable Component owner,
            Object message,
            String title,
            int optionType,
            int messageType,
            @Unmodifiable List<Object> options,
            Object initialValue,
            boolean onEventDispatchThread) {
    }

    /// One captured countdown invitation.
    ///
    /// @param owner native owner, or null
    /// @param message configured Swing content
    /// @param title native title
    /// @param messageType native message severity
    /// @param countdownLabelPattern localized countdown format
    /// @param positiveLabel delayed positive label
    /// @param negativeLabel immediate negative label
    /// @param countdownSeconds configured countdown
    /// @param onEventDispatchThread whether invocation occurred on the EDT
    @NotNullByDefault
    private record CountdownCall(
            @Nullable Component owner,
            Object message,
            String title,
            int messageType,
            String countdownLabelPattern,
            String positiveLabel,
            String negativeLabel,
            int countdownSeconds,
            boolean onEventDispatchThread) {
    }

    /// One captured link-opening error dialog.
    ///
    /// @param owner native owner, or null
    /// @param message visible failure detail
    /// @param title owning dialog title
    /// @param messageType native error severity
    /// @param onEventDispatchThread whether invocation occurred on the EDT
    @NotNullByDefault
    private record MessageCall(
            @Nullable Component owner,
            Object message,
            String title,
            int messageType,
            boolean onEventDispatchThread) {
    }

    /// Deterministic modal and countdown dialog boundary.
    @NotNullByDefault
    private static final class FakeDialogActions implements SwingStartupPromptPresenter.DialogActions {
        /// Synchronous option results returned in invocation order.
        private final Deque<Integer> optionResults = new ArrayDeque<>();

        /// Captured synchronous option dialogs.
        private final List<OptionCall> optionCalls = new ArrayList<>();

        /// Configured initial countdown result.
        private int countdownResult = JOptionPane.CLOSED_OPTION;

        /// Most recent countdown call.
        private @Nullable CountdownCall countdownCall;

        /// Most recent link failure call.
        private @Nullable MessageCall messageCall;

        /// Optional action invoked with content before a synchronous dialog returns.
        private @Nullable Consumer<Object> contentAction;

        /// Optional synchronous option failure.
        private @Nullable RuntimeException optionFailure;

        /// Creates an idle dialog fake.
        private FakeDialogActions() {
        }

        /// Captures content, optionally activates it, and returns the next configured result.
        @Override
        public int showOptionDialog(
                @Nullable Component owner,
                Object message,
                String title,
                int optionType,
                int messageType,
                Object @Unmodifiable [] options,
                Object initialValue) {
            optionCalls.add(new OptionCall(
                    owner,
                    message,
                    title,
                    optionType,
                    messageType,
                    List.copyOf(Arrays.asList(options)),
                    initialValue,
                    SwingUtilities.isEventDispatchThread()));
            if (contentAction != null) {
                contentAction.accept(message);
            }
            if (optionFailure != null) {
                throw optionFailure;
            }
            return Objects.requireNonNull(
                    optionResults.pollFirst(), "No configured option result");
        }

        /// Captures and immediately completes one countdown invitation on the EDT.
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
            countdownCall = new CountdownCall(
                    owner,
                    message,
                    title,
                    messageType,
                    countdownLabelPattern,
                    positiveLabel,
                    negativeLabel,
                    countdownSeconds,
                    SwingUtilities.isEventDispatchThread());
            return CompletableFuture.completedFuture(countdownResult);
        }

        /// Captures one link-opening failure.
        @Override
        public void showMessageDialog(
                @Nullable Component owner,
                Object message,
                String title,
                int messageType) {
            messageCall = new MessageCall(
                    owner,
                    message,
                    title,
                    messageType,
                    SwingUtilities.isEventDispatchThread());
        }
    }

    /// Deterministic external-link boundary.
    @NotNullByDefault
    private static final class FakeLinkActions implements SwingStartupPromptPresenter.LinkActions {
        /// Destinations opened in invocation order.
        private final List<URI> destinations = new ArrayList<>();

        /// EDT state for every invocation.
        private final List<Boolean> onEventDispatchThread = new ArrayList<>();

        /// Optional browser failure.
        private @Nullable IOException failure;

        /// Creates an available browser fake.
        private FakeLinkActions() {
        }

        /// Captures or fails one external destination.
        @Override
        public void browse(URI destination) throws IOException {
            destinations.add(destination);
            onEventDispatchThread.add(SwingUtilities.isEventDispatchThread());
            if (failure != null) {
                throw failure;
            }
        }
    }
}
