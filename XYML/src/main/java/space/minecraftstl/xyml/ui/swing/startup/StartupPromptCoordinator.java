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
import space.minecraftstl.xyml.ui.UiDispatcher;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;

/// Serializes the startup prompt policy across a worker executor and the Swing UI dispatcher.
///
/// State reads, state writes, process effects, and decision handling run on the worker executor. Only
/// presenter calls run on the UI dispatcher. The coordinator never blocks either side: each presentation
/// result schedules the next worker continuation. The fixed order is agreement, invalid cache, platform,
/// deprecated Java, interpreted Java, software rendering, and April Fools.
@NotNullByDefault
public final class StartupPromptCoordinator implements AutoCloseable {
    /// Immutable runtime signals for this prompt pass.
    private final StartupPromptEnvironment environment;

    /// Immutable localized presentation content.
    private final StartupPromptStrings strings;

    /// Persisted prompt state boundary invoked only on the worker executor.
    private final StartupPromptStateGateway stateGateway;

    /// Swing presentation boundary invoked only through the UI dispatcher.
    private final StartupPromptPresenter presenter;

    /// Process-level side effects invoked only on the worker executor.
    private final StartupPromptEffects effects;

    /// Dispatcher owning all presenter invocations.
    private final UiDispatcher uiDispatcher;

    /// Executor owning all policy, state, and effect work.
    private final Executor workerExecutor;

    /// Prevents more than one prompt pass from starting.
    private final AtomicBoolean started = new AtomicBoolean();

    /// Gates queued continuations after natural completion, decline, restart, or explicit close.
    private final AtomicBoolean closed = new AtomicBoolean();

    /// Distinguishes mandatory-gate scheduling failures from failures in later optional prompts.
    private final AtomicBoolean agreementSatisfied = new AtomicBoolean();

    /// Shared completion returned by every start invocation.
    private final CompletableFuture<@Nullable Void> completion = new CompletableFuture<>();

    /// Resolves as soon as the mandatory agreement is satisfied or rejected.
    private final CompletableFuture<Boolean> agreementGate = new CompletableFuture<>();

    /// Creates one idle startup prompt coordinator.
    ///
    /// The worker executor must execute submitted actions away from the Swing event dispatch thread.
    /// Neither injected scheduler may wait synchronously for the other scheduler.
    ///
    /// @param environment explicit runtime signals and policy versions
    /// @param strings localized prompt presentation
    /// @param stateGateway persisted state boundary
    /// @param presenter Swing presentation boundary
    /// @param effects process-level effect boundary
    /// @param uiDispatcher Swing event dispatch scheduler
    /// @param workerExecutor non-EDT policy and state executor
    public StartupPromptCoordinator(
            StartupPromptEnvironment environment,
            StartupPromptStrings strings,
            StartupPromptStateGateway stateGateway,
            StartupPromptPresenter presenter,
            StartupPromptEffects effects,
            UiDispatcher uiDispatcher,
            Executor workerExecutor) {
        this.environment = Objects.requireNonNull(environment, "environment");
        this.strings = Objects.requireNonNull(strings, "strings");
        this.stateGateway = Objects.requireNonNull(stateGateway, "stateGateway");
        this.presenter = Objects.requireNonNull(presenter, "presenter");
        this.effects = Objects.requireNonNull(effects, "effects");
        this.uiDispatcher = Objects.requireNonNull(uiDispatcher, "uiDispatcher");
        this.workerExecutor = Objects.requireNonNull(workerExecutor, "workerExecutor");
    }

    /// Starts the prompt sequence at most once and returns its shared asynchronous completion.
    ///
    /// Starting an explicitly closed coordinator is a no-op whose completion is already resolved.
    ///
    /// @return completion of the prompt pass, application decline, or restart path
    public CompletionStage<@Nullable Void> start() {
        if (closed.get()) {
            completion.complete(null);
            return completion;
        }
        if (started.compareAndSet(false, true)) {
            executeInitialWorker();
        }
        return completion;
    }

    /// Returns the mandatory agreement-gate result independently from later optional prompts.
    ///
    /// A `true` result enables application interaction. Decline or explicit coordinator close resolves
    /// `false`; snapshot, presentation, persistence, or initial scheduling failure completes exceptionally.
    ///
    /// @return shared mandatory-gate completion
    public CompletionStage<Boolean> agreementGate() {
        return agreementGate;
    }

    /// Returns whether the first start request has won.
    ///
    /// @return whether prompt processing was requested
    public boolean isStarted() {
        return started.get();
    }

    /// Returns whether processing has completed or explicit close has gated future continuations.
    ///
    /// @return whether this coordinator is closed
    public boolean isClosed() {
        return closed.get();
    }

    /// Stops future prompt progression exactly once without closing the application.
    ///
    /// An already-visible presenter may finish independently, but its result is discarded. This method
    /// never waits for the UI dispatcher or worker executor.
    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            agreementGate.complete(false);
            completion.complete(null);
        }
    }

    /// Reads one decision snapshot on the worker executor and begins with the agreement gate.
    private void readSnapshotAndBegin() {
        if (closed.get()) {
            return;
        }
        try {
            StartupPromptSnapshot snapshot = Objects.requireNonNull(
                    stateGateway.readSnapshot(),
                    "stateGateway returned null snapshot");
            processAgreement(snapshot);
        } catch (RuntimeException failure) {
            abortAgreementGate(failure);
        }
    }

    /// Processes the mandatory agreement gate before any other prompt or mutation.
    ///
    /// @param snapshot immutable state captured for this pass
    private void processAgreement(StartupPromptSnapshot snapshot) {
        if (closed.get()) {
            return;
        }
        if (snapshot.agreementVersion() >= environment.requiredAgreementVersion()) {
            completeAgreementGateAccepted();
            processInvalidCache(snapshot);
            return;
        }
        present(
                StartupPromptKind.AGREEMENT,
                () -> presenter.presentAgreement(strings.agreement()),
                decision -> handleAgreementDecision(snapshot, decision),
                this::abortAgreementGate);
    }

    /// Applies one agreement decision on the worker executor.
    ///
    /// @param snapshot immutable state captured for this pass
    /// @param decision strongly typed agreement decision
    private void handleAgreementDecision(
            StartupPromptSnapshot snapshot,
            StartupPromptDecision.Agreement decision) {
        if (decision == StartupPromptDecision.Agreement.DECLINE) {
            agreementGate.complete(false);
            terminateAfterDecline();
            return;
        }
        try {
            stateGateway.acceptAgreement(environment.requiredAgreementVersion());
        } catch (RuntimeException failure) {
            abortAgreementGate(failure);
            return;
        }
        completeAgreementGateAccepted();
        processInvalidCache(snapshot);
    }

    /// Restores an invalid cache setting before presenting its notification.
    ///
    /// @param snapshot immutable state captured for this pass
    private void processInvalidCache(StartupPromptSnapshot snapshot) {
        if (closed.get()) {
            return;
        }
        if (!snapshot.invalidCacheDirectory()) {
            processPlatform(snapshot);
            return;
        }
        try {
            stateGateway.restoreDefaultCacheDirectory();
        } catch (RuntimeException failure) {
            reportNonFatal(StartupPromptKind.INVALID_CACHE_DIRECTORY, failure);
            processPlatform(snapshot);
            return;
        }
        present(
                StartupPromptKind.INVALID_CACHE_DIRECTORY,
                () -> presenter.presentInvalidCacheDirectory(
                        strings.invalidCacheDirectory(),
                        strings.acknowledgeLabel()),
                decision -> processPlatform(snapshot),
                failure -> {
                    reportNonFatal(StartupPromptKind.INVALID_CACHE_DIRECTORY, failure);
                    processPlatform(snapshot);
                });
    }

    /// Processes silent or visible platform policy before Java warnings.
    ///
    /// @param snapshot immutable state captured for this pass
    private void processPlatform(StartupPromptSnapshot snapshot) {
        if (closed.get()) {
            return;
        }
        if (snapshot.platformPromptVersion() >= environment.requiredPlatformPromptVersion()
                || environment.platformPrompt() == StartupPlatformPrompt.NONE) {
            processDeprecatedJava(snapshot);
            return;
        }
        if (environment.platformPrompt() == StartupPlatformPrompt.MARK_SUPPORTED) {
            markPlatformAndContinue(snapshot);
            return;
        }
        StartupPromptCopy copy = strings.platform().copyFor(environment.platformPrompt());
        present(
                StartupPromptKind.PLATFORM,
                () -> presenter.presentPlatform(
                        environment.platformPrompt(),
                        copy,
                        strings.acknowledgeLabel()),
                decision -> markPlatformAndContinue(snapshot),
                failure -> {
                    reportNonFatal(StartupPromptKind.PLATFORM, failure);
                    processDeprecatedJava(snapshot);
                });
    }

    /// Persists platform acknowledgement and advances regardless of write failure.
    ///
    /// @param snapshot immutable state captured for this pass
    private void markPlatformAndContinue(StartupPromptSnapshot snapshot) {
        runNonFatalStateMutation(
                StartupPromptKind.PLATFORM,
                () -> stateGateway.markPlatformPromptShown(environment.requiredPlatformPromptVersion()));
        processDeprecatedJava(snapshot);
    }

    /// Processes the deprecated launcher-Java warning when its minimum version has not been acknowledged.
    ///
    /// @param snapshot immutable state captured for this pass
    private void processDeprecatedJava(StartupPromptSnapshot snapshot) {
        if (closed.get()) {
            return;
        }
        int minimumJava = environment.minimumSupportedJavaVersion();
        boolean alreadyShown = snapshot.deprecatedJavaTipVersion().isPresent()
                && snapshot.deprecatedJavaTipVersion().getAsInt() >= minimumJava;
        if (environment.currentJavaVersion() >= minimumJava || alreadyShown) {
            processInterpretedJava(snapshot);
            return;
        }
        present(
                StartupPromptKind.DEPRECATED_JAVA,
                () -> presenter.presentDeprecatedJava(
                        environment.currentJavaVersion(),
                        minimumJava,
                        strings.deprecatedJava(),
                        strings.acknowledgeLabel()),
                decision -> {
                    runNonFatalStateMutation(
                            StartupPromptKind.DEPRECATED_JAVA,
                            () -> stateGateway.markDeprecatedJavaPromptShown(minimumJava));
                    processInterpretedJava(snapshot);
                },
                failure -> {
                    reportNonFatal(StartupPromptKind.DEPRECATED_JAVA, failure);
                    processInterpretedJava(snapshot);
                });
    }

    /// Processes the interpreted-mode warning and optional suppression decision.
    ///
    /// @param snapshot immutable state captured for this pass
    private void processInterpretedJava(StartupPromptSnapshot snapshot) {
        if (closed.get()) {
            return;
        }
        if (!environment.interpretedJava() || snapshot.interpretedModeSuppressed()) {
            processSoftwareRendering(snapshot);
            return;
        }
        present(
                StartupPromptKind.INTERPRETED_JAVA,
                () -> presenter.presentInterpretedJava(strings.suppression()),
                decision -> {
                    if (decision == StartupPromptDecision.Suppression.DO_NOT_SHOW_AGAIN) {
                        runNonFatalStateMutation(
                                StartupPromptKind.INTERPRETED_JAVA,
                                stateGateway::suppressInterpretedJavaWarning);
                    }
                    processSoftwareRendering(snapshot);
                },
                failure -> {
                    reportNonFatal(StartupPromptKind.INTERPRETED_JAVA, failure);
                    processSoftwareRendering(snapshot);
                });
    }

    /// Processes the explicitly supplied software-rendering warning signal.
    ///
    /// @param snapshot immutable state captured for this pass
    private void processSoftwareRendering(StartupPromptSnapshot snapshot) {
        if (closed.get()) {
            return;
        }
        if (!environment.softwareRendering() || snapshot.softwareRenderingSuppressed()) {
            processAprilFools(snapshot);
            return;
        }
        present(
                StartupPromptKind.SOFTWARE_RENDERING,
                () -> presenter.presentSoftwareRendering(strings.suppression()),
                decision -> {
                    if (decision == StartupPromptDecision.Suppression.DO_NOT_SHOW_AGAIN) {
                        runNonFatalStateMutation(
                                StartupPromptKind.SOFTWARE_RENDERING,
                                stateGateway::suppressSoftwareRenderingWarning);
                    }
                    processAprilFools(snapshot);
                },
                failure -> {
                    reportNonFatal(StartupPromptKind.SOFTWARE_RENDERING, failure);
                    processAprilFools(snapshot);
                });
    }

    /// Processes the once-per-year language invitation after every compatibility warning.
    ///
    /// @param snapshot immutable state captured for this pass
    private void processAprilFools(StartupPromptSnapshot snapshot) {
        if (closed.get()) {
            return;
        }
        Optional<String> targetLanguageId = environment.aprilFoolsTargetLanguageId();
        boolean alreadyShown = snapshot.aprilFoolsShownYear().isPresent()
                && snapshot.aprilFoolsShownYear().getAsInt() >= environment.currentYear();
        if (!environment.aprilFoolsEnabled()
                || !environment.aprilFoolsLanguageEligible()
                || targetLanguageId.isEmpty()
                || alreadyShown) {
            completeNormally();
            return;
        }
        String target = targetLanguageId.orElseThrow();
        present(
                StartupPromptKind.APRIL_FOOLS,
                () -> presenter.presentAprilFools(target, strings.aprilFools()),
                decision -> handleAprilFoolsDecision(target, decision),
                failure -> {
                    reportNonFatal(StartupPromptKind.APRIL_FOOLS, failure);
                    completeNormally();
                });
    }

    /// Applies the April Fools result and executes the terminal restart chain after final acceptance.
    ///
    /// @param targetLanguageId stable installed target language identifier
    /// @param decision strongly typed final invitation decision
    private void handleAprilFoolsDecision(
            String targetLanguageId,
            StartupPromptDecision.AprilFools decision) {
        if (decision == StartupPromptDecision.AprilFools.KEEP_LANGUAGE) {
            runNonFatalStateMutation(
                    StartupPromptKind.APRIL_FOOLS,
                    () -> stateGateway.markAprilFoolsShown(environment.currentYear()));
            completeNormally();
            return;
        }

        if (!claimTerminalTransition()) {
            return;
        }

        @Nullable Throwable failure = runTerminalStep(
                StartupPromptKind.APRIL_FOOLS,
                () -> stateGateway.selectLanguage(targetLanguageId),
                null);
        if (failure == null) {
            failure = runTerminalStep(
                    StartupPromptKind.APRIL_FOOLS,
                    () -> stateGateway.markAprilFoolsShown(environment.currentYear()),
                    null);
        }
        if (failure == null) {
            failure = runTerminalStep(
                    StartupPromptKind.APRIL_FOOLS,
                    effects::saveBeforeRestart,
                    null);
        }
        if (failure == null) {
            failure = runTerminalStep(
                    StartupPromptKind.APRIL_FOOLS,
                    effects::waitForPendingSaves,
                    null);
        }
        if (failure == null) {
            failure = runTerminalStep(
                    StartupPromptKind.APRIL_FOOLS,
                    effects::restartApplication,
                    null);
        }
        if (failure == null) {
            failure = runTerminalStep(
                    StartupPromptKind.APRIL_FOOLS,
                    effects::closeApplication,
                    null);
        }
        finishClaimedTerminal(failure);
    }

    /// Dispatches one strongly typed presenter operation and resumes only through the worker executor.
    ///
    /// @param promptKind prompt being presented
    /// @param presentation presenter operation invoked on the UI dispatcher
    /// @param decisionConsumer worker-thread decision continuation
    /// @param failureConsumer worker-thread presentation failure continuation
    /// @param <D> prompt-specific decision type
    private <D extends StartupPromptDecision> void present(
            StartupPromptKind promptKind,
            Supplier<CompletionStage<D>> presentation,
            Consumer<D> decisionConsumer,
            Consumer<Throwable> failureConsumer) {
        if (closed.get()) {
            return;
        }
        try {
            uiDispatcher.dispatch(() -> invokePresenter(
                    promptKind,
                    presentation,
                    decisionConsumer,
                    failureConsumer));
        } catch (RuntimeException failure) {
            failureConsumer.accept(failure);
        }
    }

    /// Invokes one presentation on the UI thread and attaches a non-blocking completion callback.
    ///
    /// @param promptKind prompt being presented
    /// @param presentation presenter operation
    /// @param decisionConsumer worker-thread decision continuation
    /// @param failureConsumer worker-thread failure continuation
    /// @param <D> prompt-specific decision type
    private <D extends StartupPromptDecision> void invokePresenter(
            StartupPromptKind promptKind,
            Supplier<CompletionStage<D>> presentation,
            Consumer<D> decisionConsumer,
            Consumer<Throwable> failureConsumer) {
        if (closed.get()) {
            return;
        }
        try {
            CompletionStage<D> decisionStage = Objects.requireNonNull(
                    presentation.get(),
                    "presenter returned null completion for " + promptKind);
            decisionStage.whenComplete((@Nullable D decision, @Nullable Throwable failure) ->
                    executeWorker(() -> handlePresentationCompletion(
                            promptKind,
                            decision,
                            failure,
                            decisionConsumer,
                            failureConsumer)));
        } catch (RuntimeException failure) {
            executeWorker(() -> failureConsumer.accept(failure));
        }
    }

    /// Validates an asynchronous presenter result before invoking its worker continuation.
    ///
    /// @param promptKind prompt being presented
    /// @param decision returned decision, or null for an invalid presenter result
    /// @param failure asynchronous presenter failure, or null
    /// @param decisionConsumer valid decision continuation
    /// @param failureConsumer failure continuation
    /// @param <D> prompt-specific decision type
    private <D extends StartupPromptDecision> void handlePresentationCompletion(
            StartupPromptKind promptKind,
            @Nullable D decision,
            @Nullable Throwable failure,
            Consumer<D> decisionConsumer,
            Consumer<Throwable> failureConsumer) {
        if (closed.get()) {
            return;
        }
        if (failure != null) {
            failureConsumer.accept(unwrapCompletionFailure(failure));
        } else if (decision == null) {
            failureConsumer.accept(new NullPointerException(
                    "presenter returned null decision for " + promptKind));
        } else {
            decisionConsumer.accept(decision);
        }
    }

    /// Executes one non-gating state mutation and reports failure without stopping later prompts.
    ///
    /// @param promptKind owning prompt
    /// @param mutation state mutation
    private void runNonFatalStateMutation(StartupPromptKind promptKind, Runnable mutation) {
        try {
            mutation.run();
        } catch (RuntimeException failure) {
            reportNonFatal(promptKind, failure);
        }
    }

    /// Reports a non-gating failure while isolating a failure raised by the reporter itself.
    ///
    /// @param promptKind owning prompt
    /// @param failure operation failure
    private void reportNonFatal(StartupPromptKind promptKind, Throwable failure) {
        try {
            effects.reportFailure(promptKind, failure);
        } catch (RuntimeException reportingFailure) {
            addSuppressedDistinct(failure, reportingFailure);
        }
    }

    /// Declines the agreement, requests application close, and prevents every later prompt.
    private void terminateAfterDecline() {
        if (!claimTerminalTransition()) {
            return;
        }
        @Nullable Throwable failure = runTerminalStep(
                StartupPromptKind.AGREEMENT,
                effects::closeApplication,
                null);
        finishClaimedTerminal(failure);
    }

    /// Reports a mandatory-gate failure, still requests application close, and fails completion.
    ///
    /// @param gateFailure agreement presentation, snapshot, or persistence failure
    private void abortAgreementGate(Throwable gateFailure) {
        agreementGate.completeExceptionally(gateFailure);
        if (!claimTerminalTransition()) {
            return;
        }
        reportNonFatal(StartupPromptKind.AGREEMENT, gateFailure);
        @Nullable Throwable failure = runTerminalStep(
                StartupPromptKind.AGREEMENT,
                effects::closeApplication,
                gateFailure);
        finishClaimedTerminal(failure);
    }

    /// Runs one terminal restart or close step and aggregates its failure without skipping later steps.
    ///
    /// @param promptKind owning prompt
    /// @param step terminal action
    /// @param previousFailure prior failure, or null
    /// @return first failure with later failures suppressed, or null
    private @Nullable Throwable runTerminalStep(
            StartupPromptKind promptKind,
            CheckedAction step,
            @Nullable Throwable previousFailure) {
        try {
            step.run();
            return previousFailure;
        } catch (Exception failure) {
            reportNonFatal(promptKind, failure);
            if (previousFailure == null) {
                return failure;
            }
            addSuppressedDistinct(previousFailure, failure);
            return previousFailure;
        }
    }

    /// Marks normal queue completion exactly once.
    private void completeNormally() {
        if (claimTerminalTransition()) {
            finishClaimedTerminal(null);
        }
    }

    /// Atomically gates external close and every late continuation before terminal effects run.
    ///
    /// @return whether this caller owns terminal completion
    private boolean claimTerminalTransition() {
        return closed.compareAndSet(false, true);
    }

    /// Completes this coordinator after the caller has already claimed its terminal transition.
    ///
    /// @param failure terminal failure, or null after success
    private void finishClaimedTerminal(@Nullable Throwable failure) {
        if (failure == null) {
            completion.complete(null);
        } else {
            completion.completeExceptionally(failure);
        }
    }

    /// Schedules one worker continuation without waiting for its completion.
    ///
    /// @param operation worker operation
    private void executeWorker(Runnable operation) {
        if (closed.get()) {
            return;
        }
        try {
            workerExecutor.execute(() -> {
                if (!closed.get()) {
                    operation.run();
                }
            });
        } catch (RuntimeException schedulingFailure) {
            if (!agreementSatisfied.get()) {
                failAgreementScheduling(schedulingFailure);
            } else if (closed.compareAndSet(false, true)) {
                completion.completeExceptionally(schedulingFailure);
            }
        }
    }

    /// Submits the mandatory gate and fails closed when the worker rejects initial execution.
    ///
    /// Rejection is handled on the caller thread because no worker is available. Production effects only
    /// log and asynchronously dispatch close, so this exceptional path never blocks either UI toolkit.
    private void executeInitialWorker() {
        if (closed.get()) {
            return;
        }
        try {
            workerExecutor.execute(() -> {
                if (!closed.get()) {
                    readSnapshotAndBegin();
                }
            });
        } catch (RuntimeException schedulingFailure) {
            failAgreementScheduling(schedulingFailure);
        }
    }

    /// Marks the mandatory gate satisfied before exposing its successful completion to window owners.
    private void completeAgreementGateAccepted() {
        agreementSatisfied.set(true);
        agreementGate.complete(true);
    }

    /// Fails both completions when no worker remains available to finish mandatory agreement policy.
    ///
    /// The application owner observes [#agreementGate()] and performs fail-closed window cleanup. This
    /// method deliberately avoids invoking worker-confined effects on the thread whose submission failed.
    ///
    /// @param schedulingFailure exact executor rejection
    private void failAgreementScheduling(RuntimeException schedulingFailure) {
        agreementGate.completeExceptionally(schedulingFailure);
        if (closed.compareAndSet(false, true)) {
            completion.completeExceptionally(schedulingFailure);
        }
    }

    /// Removes one completion wrapper while preserving the presenter's original failure identity.
    ///
    /// @param failure completion failure
    /// @return underlying cause when wrapped, otherwise the supplied failure
    private static Throwable unwrapCompletionFailure(Throwable failure) {
        if (failure instanceof CompletionException && failure.getCause() != null) {
            return failure.getCause();
        }
        return failure;
    }

    /// Suppresses a distinct later failure without attempting self-suppression.
    ///
    /// @param firstFailure first failure
    /// @param laterFailure later failure
    private static void addSuppressedDistinct(Throwable firstFailure, Throwable laterFailure) {
        if (firstFailure != laterFailure) {
            firstFailure.addSuppressed(laterFailure);
        }
    }

    /// Checked terminal action used to aggregate save, wait, restart, and close failures.
    @FunctionalInterface
    @NotNullByDefault
    private interface CheckedAction {
        /// Executes one terminal action.
        ///
        /// @throws Exception when the action fails
        void run() throws Exception;
    }
}
