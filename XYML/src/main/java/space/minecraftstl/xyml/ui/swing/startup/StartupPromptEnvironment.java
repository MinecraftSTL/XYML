// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.startup;

import org.jetbrains.annotations.NotNullByDefault;

import java.util.Objects;
import java.util.Optional;

/// Immutable runtime signals used by the startup prompt policy.
///
/// Detection remains outside the prompt layer. In particular, software-rendering status, platform
/// classification, and April Fools language eligibility must be supplied explicitly by the caller.
///
/// @param requiredAgreementVersion agreement version required by this launcher build
/// @param requiredPlatformPromptVersion platform prompt policy version required by this build
/// @param platformPrompt explicitly detected platform behavior
/// @param minimumSupportedJavaVersion minimum supported launcher Java feature version
/// @param currentJavaVersion current launcher Java feature version
/// @param interpretedJava whether the launcher JVM is running without JIT compilation
/// @param softwareRendering whether the active rendering pipeline is software-only
/// @param aprilFoolsEnabled whether the dated feature is enabled for this launch
/// @param currentYear local year used for once-per-year state
/// @param aprilFoolsLanguageEligible whether the current language may receive the invitation
/// @param aprilFoolsTargetLanguageId target language identifier when that locale is installed
@NotNullByDefault
public record StartupPromptEnvironment(
        int requiredAgreementVersion,
        int requiredPlatformPromptVersion,
        StartupPlatformPrompt platformPrompt,
        int minimumSupportedJavaVersion,
        int currentJavaVersion,
        boolean interpretedJava,
        boolean softwareRendering,
        boolean aprilFoolsEnabled,
        int currentYear,
        boolean aprilFoolsLanguageEligible,
        Optional<String> aprilFoolsTargetLanguageId) {
    /// Validates all explicit policy values and preserves an absent target locale as absent.
    public StartupPromptEnvironment {
        Objects.requireNonNull(platformPrompt, "platformPrompt");
        Objects.requireNonNull(aprilFoolsTargetLanguageId, "aprilFoolsTargetLanguageId");
        if (requiredAgreementVersion <= 0) {
            throw new IllegalArgumentException("requiredAgreementVersion must be positive");
        }
        if (requiredPlatformPromptVersion <= 0) {
            throw new IllegalArgumentException("requiredPlatformPromptVersion must be positive");
        }
        if (minimumSupportedJavaVersion <= 0) {
            throw new IllegalArgumentException("minimumSupportedJavaVersion must be positive");
        }
        if (currentJavaVersion <= 0) {
            throw new IllegalArgumentException("currentJavaVersion must be positive");
        }
        if (currentYear <= 0) {
            throw new IllegalArgumentException("currentYear must be positive");
        }
        aprilFoolsTargetLanguageId.ifPresent(languageId -> {
            if (languageId.isBlank()) {
                throw new IllegalArgumentException("aprilFoolsTargetLanguageId must not be blank");
            }
        });
    }
}
