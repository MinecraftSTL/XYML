// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.launch;

import org.jetbrains.annotations.NotNullByDefault;

import java.util.concurrent.CompletionStage;

/// Presents launch-time choices without exposing a desktop UI toolkit to launch preparation.
///
/// Implementations must accept calls from worker threads. Closing a prompt must resolve to the
/// request's explicit close action instead of leaving launch preparation suspended.
@NotNullByDefault
public interface LaunchInteraction {
    /// Presents one launch decision and eventually returns the selected semantic action.
    ///
    /// @param prompt immutable localized prompt and action description
    /// @return non-null completion that resolves once the prompt has been answered or closed
    CompletionStage<LaunchInteractionPrompt.Action> present(LaunchInteractionPrompt prompt);
}
