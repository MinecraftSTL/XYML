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
