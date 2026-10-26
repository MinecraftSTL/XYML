// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.downloads.loaders;

import org.jetbrains.annotations.NotNullByDefault;
import space.minecraftstl.xyml.task.Task;

import java.util.concurrent.CompletionStage;

/// Starts one Core refresh task and exposes only its terminal completion.
///
/// This package-local boundary keeps DownloadProvider catalog tests deterministic without replacing
/// the production Core task implementation or making Swing responsible for task scheduling.
@FunctionalInterface
@NotNullByDefault
interface LoaderTaskCompletionRunner {
    /// Starts or observes one Core task until it succeeds or fails.
    ///
    /// @param task task returned by a VersionList refresh call
    /// @return terminal completion stage
    CompletionStage<Void> run(Task<?> task);
}
