// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.launch;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Verifies immutable launch decision semantics before any desktop toolkit is involved.
@NotNullByDefault
class LaunchInteractionPromptTest {
    /// Snapshots caller-owned options and preserves their visible order.
    @Test
    void snapshotsOrderedOptions() {
        List<LaunchInteractionPrompt.Option> source = new ArrayList<>(List.of(
                new LaunchInteractionPrompt.Option(
                        LaunchInteractionPrompt.Action.CONTINUE,
                        "Continue"),
                new LaunchInteractionPrompt.Option(
                        LaunchInteractionPrompt.Action.CANCEL,
                        "Cancel")));

        LaunchInteractionPrompt prompt = new LaunchInteractionPrompt(
                "Warning",
                "Compatibility guidance",
                LaunchInteractionPrompt.Severity.WARNING,
                source,
                LaunchInteractionPrompt.Action.CANCEL,
                LaunchInteractionPrompt.Action.CANCEL);
        source.clear();

        @Unmodifiable List<LaunchInteractionPrompt.Option> snapshot = prompt.options();
        assertEquals(List.of(
                LaunchInteractionPrompt.Action.CONTINUE,
                LaunchInteractionPrompt.Action.CANCEL), snapshot.stream()
                .map(LaunchInteractionPrompt.Option::action)
                .toList());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.add(
                new LaunchInteractionPrompt.Option(
                        LaunchInteractionPrompt.Action.ACKNOWLEDGE,
                        "OK")));
    }

    /// Rejects ambiguous duplicate actions before presentation.
    @Test
    void rejectsDuplicateActions() {
        @Unmodifiable List<LaunchInteractionPrompt.Option> duplicateOptions = List.of(
                new LaunchInteractionPrompt.Option(
                        LaunchInteractionPrompt.Action.CONTINUE,
                        "Continue"),
                new LaunchInteractionPrompt.Option(
                        LaunchInteractionPrompt.Action.CONTINUE,
                        "Proceed"));

        assertThrows(IllegalArgumentException.class, () -> new LaunchInteractionPrompt(
                "Question",
                "Choose",
                LaunchInteractionPrompt.Severity.QUESTION,
                duplicateOptions,
                LaunchInteractionPrompt.Action.CONTINUE,
                LaunchInteractionPrompt.Action.CONTINUE));
    }

    /// Requires both default and window-close actions to be visible options.
    @Test
    void rejectsAbsentControlActions() {
        @Unmodifiable List<LaunchInteractionPrompt.Option> options = List.of(
                new LaunchInteractionPrompt.Option(
                        LaunchInteractionPrompt.Action.CONTINUE,
                        "Continue"));

        assertThrows(IllegalArgumentException.class, () -> new LaunchInteractionPrompt(
                "Question",
                "Choose",
                LaunchInteractionPrompt.Severity.QUESTION,
                options,
                LaunchInteractionPrompt.Action.CANCEL,
                LaunchInteractionPrompt.Action.CONTINUE));
        assertThrows(IllegalArgumentException.class, () -> new LaunchInteractionPrompt(
                "Question",
                "Choose",
                LaunchInteractionPrompt.Severity.QUESTION,
                options,
                LaunchInteractionPrompt.Action.CONTINUE,
                LaunchInteractionPrompt.Action.CANCEL));
    }

    /// Defines non-destructive defaults for Java confirmation and credential refresh.
    @Test
    void confirmationFactoriesDefaultToCancellation() {
        LaunchInteractionPrompt javaPrompt = LaunchInteractionPrompt.confirmation(
                "Java",
                "Use the recommended runtime?",
                LaunchInteractionPrompt.Severity.WARNING,
                LaunchInteractionPrompt.Action.USE_RECOMMENDED_JAVA,
                "Use recommended",
                "Cancel");
        LaunchInteractionPrompt credentialPrompt = LaunchInteractionPrompt.credentialRefresh(
                "Account",
                "Authorization expired",
                "Log in again",
                "Cancel");

        assertEquals(LaunchInteractionPrompt.Action.CANCEL, javaPrompt.defaultAction());
        assertEquals(LaunchInteractionPrompt.Action.CANCEL, javaPrompt.closeAction());
        assertEquals(
                LaunchInteractionPrompt.Action.USE_RECOMMENDED_JAVA,
                javaPrompt.options().get(0).action());
        assertEquals(
                LaunchInteractionPrompt.Action.REFRESH_CREDENTIALS,
                credentialPrompt.options().get(0).action());
    }

    /// Preserves the complete offline, retry, and cancellation authentication recovery choice.
    @Test
    void authenticationRecoveryContainsEveryExistingBranch() {
        LaunchInteractionPrompt prompt = LaunchInteractionPrompt.authenticationRecovery(
                "Login failed",
                "Server disconnected",
                "Play offline",
                "Retry",
                "Cancel");

        assertEquals(List.of(
                LaunchInteractionPrompt.Action.PLAY_OFFLINE,
                LaunchInteractionPrompt.Action.RETRY_AUTHENTICATION,
                LaunchInteractionPrompt.Action.CANCEL), prompt.options().stream()
                .map(LaunchInteractionPrompt.Option::action)
                .toList());
        assertEquals(LaunchInteractionPrompt.Action.CANCEL, prompt.defaultAction());
        assertEquals(LaunchInteractionPrompt.Action.CANCEL, prompt.closeAction());
    }
}
