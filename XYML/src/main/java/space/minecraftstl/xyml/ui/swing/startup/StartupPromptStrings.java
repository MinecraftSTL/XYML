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

import java.net.URI;
import java.util.IllegalFormatException;
import java.util.Objects;
import java.util.Optional;

/// Immutable localized copy and links for the complete startup prompt sequence.
///
/// @param agreement mandatory agreement presentation
/// @param invalidCacheDirectory invalid-cache notification copy
/// @param platform platform-specific presentation copy
/// @param deprecatedJava deprecated launcher-Java presentation
/// @param suppression optionally suppressible warning presentation
/// @param aprilFools optional language-switch presentation
/// @param acknowledgeLabel label for acknowledgement-only prompts
@NotNullByDefault
public record StartupPromptStrings(
        Agreement agreement,
        StartupPromptCopy invalidCacheDirectory,
        Platform platform,
        DeprecatedJava deprecatedJava,
        Suppression suppression,
        AprilFools aprilFools,
        String acknowledgeLabel) {
    /// Rejects absent collaborators without inventing fallback text.
    public StartupPromptStrings {
        Objects.requireNonNull(agreement, "agreement");
        Objects.requireNonNull(invalidCacheDirectory, "invalidCacheDirectory");
        Objects.requireNonNull(platform, "platform");
        Objects.requireNonNull(deprecatedJava, "deprecatedJava");
        Objects.requireNonNull(suppression, "suppression");
        Objects.requireNonNull(aprilFools, "aprilFools");
        Objects.requireNonNull(acknowledgeLabel, "acknowledgeLabel");
    }

    /// Immutable label and destination for a prompt hyperlink.
    ///
    /// @param label localized visible link text
    /// @param destination external link destination
    @NotNullByDefault
    public record Link(String label, URI destination) {
        /// Rejects absent link content.
        public Link {
            Objects.requireNonNull(label, "label");
            Objects.requireNonNull(destination, "destination");
        }
    }

    /// Immutable agreement copy, labels, and external agreement link.
    ///
    /// @param copy localized heading and explanation
    /// @param agreementLink link to the complete agreement
    /// @param acceptLabel localized acceptance label
    /// @param declineLabel localized decline label
    @NotNullByDefault
    public record Agreement(
            StartupPromptCopy copy,
            Link agreementLink,
            String acceptLabel,
            String declineLabel) {
        /// Rejects absent agreement presentation values.
        public Agreement {
            Objects.requireNonNull(copy, "copy");
            Objects.requireNonNull(agreementLink, "agreementLink");
            Objects.requireNonNull(acceptLabel, "acceptLabel");
            Objects.requireNonNull(declineLabel, "declineLabel");
        }
    }

    /// Immutable copies for each platform warning classification.
    ///
    /// @param windowsArm64 Windows ARM64 informational copy
    /// @param loongArch LoongArch and related Linux informational copy
    /// @param otherUnsupported general non-x86 compatibility warning copy
    @NotNullByDefault
    public record Platform(
            StartupPromptCopy windowsArm64,
            StartupPromptCopy loongArch,
            StartupPromptCopy otherUnsupported) {
        /// Rejects missing platform-specific copy.
        public Platform {
            Objects.requireNonNull(windowsArm64, "windowsArm64");
            Objects.requireNonNull(loongArch, "loongArch");
            Objects.requireNonNull(otherUnsupported, "otherUnsupported");
        }

        /// Returns the copy for a platform classification that requires presentation.
        ///
        /// @param prompt explicit platform classification
        /// @return matching localized copy
        /// @throws IllegalArgumentException when the classification does not require a prompt
        public StartupPromptCopy copyFor(StartupPlatformPrompt prompt) {
            return switch (Objects.requireNonNull(prompt, "prompt")) {
                case WINDOWS_ARM64 -> windowsArm64;
                case LOONGARCH -> loongArch;
                case OTHER_UNSUPPORTED -> otherUnsupported;
                case NONE, MARK_SUPPORTED -> throw new IllegalArgumentException(
                        "Platform classification does not require presentation: " + prompt);
            };
        }
    }

    /// Immutable deprecated-Java copy and optional official download link.
    ///
    /// @param copy localized warning copy
    /// @param downloadLink official suggested-runtime link when one is available
    @NotNullByDefault
    public record DeprecatedJava(StartupPromptCopy copy, Optional<Link> downloadLink) {
        /// Rejects absent values while preserving a genuinely unavailable link.
        public DeprecatedJava {
            Objects.requireNonNull(copy, "copy");
            Objects.requireNonNull(downloadLink, "downloadLink");
        }
    }

    /// Immutable copy and labels shared by warnings with optional permanent suppression.
    ///
    /// @param interpretedJava interpreted-mode warning copy
    /// @param softwareRendering software-rendering warning copy
    /// @param continueLabel localized one-launch continuation label
    /// @param suppressLabel localized permanent suppression label
    @NotNullByDefault
    public record Suppression(
            StartupPromptCopy interpretedJava,
            StartupPromptCopy softwareRendering,
            String continueLabel,
            String suppressLabel) {
        /// Rejects absent suppressible-warning presentation values.
        public Suppression {
            Objects.requireNonNull(interpretedJava, "interpretedJava");
            Objects.requireNonNull(softwareRendering, "softwareRendering");
            Objects.requireNonNull(continueLabel, "continueLabel");
            Objects.requireNonNull(suppressLabel, "suppressLabel");
        }
    }

    /// Immutable two-step April Fools presentation and outcome labels.
    ///
    /// @param initialCopy localized countdown invitation copy
    /// @param confirmationCopy localized final confirmation copy
    /// @param countdownSeconds positive delay before the initial switch action becomes available
    /// @param countdownLabelPattern localized `String.format` pattern receiving remaining seconds
    /// @param initialSwitchLabel localized initial positive label restored after the countdown
    /// @param initialKeepLabel localized initial keep-current-language label
    /// @param confirmationSwitchLabel localized final language-switch confirmation label
    /// @param confirmationKeepLabel localized final keep-current-language label
    @NotNullByDefault
    public record AprilFools(
            StartupPromptCopy initialCopy,
            StartupPromptCopy confirmationCopy,
            int countdownSeconds,
            String countdownLabelPattern,
            String initialSwitchLabel,
            String initialKeepLabel,
            String confirmationSwitchLabel,
            String confirmationKeepLabel) {
        /// Rejects absent April Fools presentation values.
        public AprilFools {
            Objects.requireNonNull(initialCopy, "initialCopy");
            Objects.requireNonNull(confirmationCopy, "confirmationCopy");
            if (countdownSeconds <= 0) {
                throw new IllegalArgumentException("countdownSeconds must be positive");
            }
            Objects.requireNonNull(countdownLabelPattern, "countdownLabelPattern");
            Objects.requireNonNull(initialSwitchLabel, "initialSwitchLabel");
            Objects.requireNonNull(initialKeepLabel, "initialKeepLabel");
            Objects.requireNonNull(confirmationSwitchLabel, "confirmationSwitchLabel");
            Objects.requireNonNull(confirmationKeepLabel, "confirmationKeepLabel");
            try {
                countdownLabelPattern.formatted(countdownSeconds);
            } catch (IllegalFormatException failure) {
                throw new IllegalArgumentException(
                        "countdownLabelPattern must accept remaining seconds",
                        failure);
            }
        }
    }
}
