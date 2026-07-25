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
package space.minecraftstl.xyml.ui.launch;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/// Immutable localized description of one launch-time decision.
///
/// The default action controls keyboard focus while the close action defines cancellation semantics
/// for the native window close control. Both actions must be present exactly once in [#options()].
///
/// @param title localized native-dialog title
/// @param message localized decision detail
/// @param severity visual severity independent from Swing or JavaFX constants
/// @param options immutable ordered choices displayed to the user
/// @param defaultAction option receiving initial native focus
/// @param closeAction option returned when the user closes the window without selecting a button
@NotNullByDefault
public record LaunchInteractionPrompt(
        String title,
        String message,
        Severity severity,
        @Unmodifiable List<Option> options,
        Action defaultAction,
        Action closeAction) {
    /// Validates the decision and snapshots its ordered options.
    public LaunchInteractionPrompt {
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(severity, "severity");
        options = List.copyOf(Objects.requireNonNull(options, "options"));
        Objects.requireNonNull(defaultAction, "defaultAction");
        Objects.requireNonNull(closeAction, "closeAction");
        if (options.isEmpty()) {
            throw new IllegalArgumentException("options must not be empty");
        }

        Set<Action> actions = new HashSet<>();
        for (Option option : options) {
            Objects.requireNonNull(option, "options must not contain null");
            if (!actions.add(option.action())) {
                throw new IllegalArgumentException("Duplicate launch action: " + option.action());
            }
        }
        if (!actions.contains(defaultAction)) {
            throw new IllegalArgumentException("defaultAction must be present in options");
        }
        if (!actions.contains(closeAction)) {
            throw new IllegalArgumentException("closeAction must be present in options");
        }
    }

    /// Creates an acknowledgement-only prompt.
    ///
    /// Window close and the explicit button both resolve to [Action#ACKNOWLEDGE]. The caller remains
    /// responsible for deciding whether acknowledgement continues or aborts launch preparation.
    ///
    /// @param title localized dialog title
    /// @param message localized notice detail
    /// @param severity visual notice severity
    /// @param acknowledgeLabel localized acknowledgement label
    /// @return immutable acknowledgement prompt
    public static LaunchInteractionPrompt acknowledgement(
            String title,
            String message,
            Severity severity,
            String acknowledgeLabel) {
        return new LaunchInteractionPrompt(
                title,
                message,
                severity,
                List.of(new Option(Action.ACKNOWLEDGE, acknowledgeLabel)),
                Action.ACKNOWLEDGE,
                Action.ACKNOWLEDGE);
    }

    /// Creates a two-action confirmation whose safest default and window-close result are cancellation.
    ///
    /// @param title localized dialog title
    /// @param message localized confirmation detail
    /// @param severity visual confirmation severity
    /// @param acceptedAction semantic action performed after explicit acceptance
    /// @param acceptedLabel localized acceptance label
    /// @param cancelLabel localized cancellation label
    /// @return immutable confirmation prompt
    public static LaunchInteractionPrompt confirmation(
            String title,
            String message,
            Severity severity,
            Action acceptedAction,
            String acceptedLabel,
            String cancelLabel) {
        Objects.requireNonNull(acceptedAction, "acceptedAction");
        if (acceptedAction == Action.CANCEL) {
            throw new IllegalArgumentException("acceptedAction must not be CANCEL");
        }
        return new LaunchInteractionPrompt(
                title,
                message,
                severity,
                List.of(
                        new Option(acceptedAction, acceptedLabel),
                        new Option(Action.CANCEL, cancelLabel)),
                Action.CANCEL,
                Action.CANCEL);
    }

    /// Creates the existing authentication-server recovery decision.
    ///
    /// Explicit actions preserve the current launch behavior: try the selected account offline, retry
    /// online authentication, or cancel. Closing the window is cancellation.
    ///
    /// @param title localized authentication-failure title
    /// @param message localized authentication-failure detail
    /// @param offlineLabel localized offline action label
    /// @param retryLabel localized retry action label
    /// @param cancelLabel localized cancellation label
    /// @return immutable authentication recovery prompt
    public static LaunchInteractionPrompt authenticationRecovery(
            String title,
            String message,
            String offlineLabel,
            String retryLabel,
            String cancelLabel) {
        return new LaunchInteractionPrompt(
                title,
                message,
                Severity.ERROR,
                List.of(
                        new Option(Action.PLAY_OFFLINE, offlineLabel),
                        new Option(Action.RETRY_AUTHENTICATION, retryLabel),
                        new Option(Action.CANCEL, cancelLabel)),
                Action.CANCEL,
                Action.CANCEL);
    }

    /// Creates the credential-expiry decision preceding an account-specific reauthentication flow.
    ///
    /// @param title localized expired-credential title
    /// @param message localized account-specific explanation
    /// @param refreshLabel localized reauthentication label
    /// @param cancelLabel localized cancellation label
    /// @return immutable credential refresh prompt
    public static LaunchInteractionPrompt credentialRefresh(
            String title,
            String message,
            String refreshLabel,
            String cancelLabel) {
        return confirmation(
                title,
                message,
                Severity.WARNING,
                Action.REFRESH_CREDENTIALS,
                refreshLabel,
                cancelLabel);
    }

    /// Semantic launch actions independent from localized labels and UI toolkit constants.
    @NotNullByDefault
    public enum Action {
        /// Acknowledges a notice without implying whether launch continues.
        ACKNOWLEDGE,

        /// Continues with the current settings or runtime.
        CONTINUE,

        /// Cancels the current launch preparation.
        CANCEL,

        /// Uses the launcher-recommended installed Java runtime.
        USE_RECOMMENDED_JAVA,

        /// Falls back to the launcher's own Java runtime.
        USE_DEFAULT_JAVA,

        /// Downloads the Java runtime required by the selected game version.
        DOWNLOAD_REQUIRED_JAVA,

        /// Enables a recommended compatibility setting before continuing.
        ENABLE_RECOMMENDED_SETTING,

        /// Starts the account-specific credential refresh flow.
        REFRESH_CREDENTIALS,

        /// Retries online authentication with the selected account.
        RETRY_AUTHENTICATION,

        /// Launches using the selected account's offline fallback data.
        PLAY_OFFLINE,

        /// Applies a local repair operation offered after launch preparation fails.
        APPLY_REPAIR
    }

    /// Toolkit-neutral visual severity.
    @NotNullByDefault
    public enum Severity {
        /// Informational prompt.
        INFO,

        /// Cautionary prompt that may still allow continuation.
        WARNING,

        /// Failure or dangerous recovery prompt.
        ERROR,

        /// Neutral question requiring an explicit choice.
        QUESTION
    }

    /// One ordered localized button and its semantic result.
    ///
    /// @param action toolkit-neutral result returned for this option
    /// @param label localized visible button label
    @NotNullByDefault
    public record Option(Action action, String label) {
        /// Validates the immutable option.
        public Option {
            Objects.requireNonNull(action, "action");
            Objects.requireNonNull(label, "label");
            if (label.isBlank()) {
                throw new IllegalArgumentException("label must not be blank");
            }
        }
    }
}
