// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.startup;

import org.jetbrains.annotations.NotNullByDefault;

import java.util.concurrent.CompletionStage;

/// Presents startup prompts on the Swing event dispatch thread without performing persisted writes.
///
/// Every returned stage may complete later on any thread. Implementations must not block the event dispatch
/// thread while waiting for another toolkit or for the coordinator's worker executor.
@NotNullByDefault
public interface StartupPromptPresenter {
    /// Presents the mandatory agreement gate.
    ///
    /// @param strings localized agreement presentation
    /// @return eventual agreement decision
    CompletionStage<StartupPromptDecision.Agreement> presentAgreement(
            StartupPromptStrings.Agreement strings);

    /// Presents notification that an invalid cache directory was restored to default.
    ///
    /// @param copy localized prompt copy
    /// @param acknowledgeLabel localized acknowledgement label
    /// @return eventual acknowledgement
    CompletionStage<StartupPromptDecision.Acknowledgement> presentInvalidCacheDirectory(
            StartupPromptCopy copy,
            String acknowledgeLabel);

    /// Presents one explicitly classified platform notice.
    ///
    /// @param platformPrompt platform classification requiring presentation
    /// @param copy localized classification-specific copy
    /// @param acknowledgeLabel localized acknowledgement label
    /// @return eventual acknowledgement
    CompletionStage<StartupPromptDecision.Acknowledgement> presentPlatform(
            StartupPlatformPrompt platformPrompt,
            StartupPromptCopy copy,
            String acknowledgeLabel);

    /// Presents the deprecated launcher-Java warning.
    ///
    /// @param currentJavaVersion current launcher Java feature version
    /// @param minimumJavaVersion minimum supported launcher Java feature version
    /// @param strings localized warning and optional official download link
    /// @param acknowledgeLabel localized acknowledgement label
    /// @return eventual acknowledgement
    CompletionStage<StartupPromptDecision.Acknowledgement> presentDeprecatedJava(
            int currentJavaVersion,
            int minimumJavaVersion,
            StartupPromptStrings.DeprecatedJava strings,
            String acknowledgeLabel);

    /// Presents the interpreted-mode warning.
    ///
    /// @param strings localized suppressible-warning presentation
    /// @return eventual continuation or suppression decision
    CompletionStage<StartupPromptDecision.Suppression> presentInterpretedJava(
            StartupPromptStrings.Suppression strings);

    /// Presents the software-rendering warning.
    ///
    /// @param strings localized suppressible-warning presentation
    /// @return eventual continuation or suppression decision
    CompletionStage<StartupPromptDecision.Suppression> presentSoftwareRendering(
            StartupPromptStrings.Suppression strings);

    /// Presents the complete April Fools countdown invitation and final confirmation flow.
    ///
    /// The initial switch action remains unavailable for the configured positive countdown. A
    /// `SWITCH_LANGUAGE` result means the presenter has obtained the second, final confirmation, not
    /// merely the first invitation acknowledgement. Closing either step keeps the current language.
    ///
    /// @param targetLanguageId installed target language identifier
    /// @param strings localized invitation and outcome labels
    /// @return eventual switch-or-keep decision
    CompletionStage<StartupPromptDecision.AprilFools> presentAprilFools(
            String targetLanguageId,
            StartupPromptStrings.AprilFools strings);
}
