// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.startup;

import org.jetbrains.annotations.NotNullByDefault;

/// Reads and mutates the persisted state used by startup prompt policy.
///
/// The coordinator invokes every method on its worker executor and never from the Swing event thread.
@NotNullByDefault
public interface StartupPromptStateGateway {
    /// Captures one immutable decision snapshot.
    ///
    /// @return current prompt-related state
    StartupPromptSnapshot readSnapshot();

    /// Persists acceptance of the required agreement version.
    ///
    /// @param agreementVersion accepted agreement version
    void acceptAgreement(int agreementVersion);

    /// Restores the default cache-directory configuration after custom-directory validation failed.
    void restoreDefaultCacheDirectory();

    /// Persists acknowledgement of the current platform prompt policy.
    ///
    /// @param promptVersion acknowledged platform prompt version
    void markPlatformPromptShown(int promptVersion);

    /// Persists the minimum Java version covered by the deprecated-runtime warning.
    ///
    /// @param minimumJavaVersion acknowledged minimum Java feature version
    void markDeprecatedJavaPromptShown(int minimumJavaVersion);

    /// Permanently suppresses the interpreted-mode warning.
    void suppressInterpretedJavaWarning();

    /// Permanently suppresses the software-rendering warning.
    void suppressSoftwareRenderingWarning();

    /// Marks the April Fools invitation as resolved for one year.
    ///
    /// @param year resolved local year
    void markAprilFoolsShown(int year);

    /// Persists the selected launcher language identifier.
    ///
    /// @param languageId stable target language identifier
    void selectLanguage(String languageId);
}
