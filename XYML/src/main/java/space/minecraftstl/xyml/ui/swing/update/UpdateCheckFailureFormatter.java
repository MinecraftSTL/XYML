// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.update;

import com.google.gson.JsonParseException;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import space.minecraftstl.xyml.upgrade.SelfVerificationException;
import space.minecraftstl.xyml.util.io.ResponseCodeException;

import java.io.IOException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

import static space.minecraftstl.xyml.util.i18n.I18n.i18n;

/// Formats fixed localized categories without exposing exception messages or response payloads.
@NotNullByDefault
public final class UpdateCheckFailureFormatter {
    /// Prevents construction of this stateless formatter.
    private UpdateCheckFailureFormatter() {
    }

    /// Unwraps bounded asynchronous wrappers and returns a non-sensitive category.
    public static String format(Throwable failure) {
        Throwable cause = failure;
        for (int depth = 0; depth < 16 && (cause instanceof CompletionException
                || cause instanceof ExecutionException); depth++) {
            @Nullable Throwable next = cause.getCause();
            if (next == null || next == cause) break;
            cause = next;
        }
        if (cause instanceof SelfVerificationException) return i18n("update.unverified");
        if (cause instanceof JsonParseException || cause instanceof ResponseCodeException) {
            return i18n("update.check_failed.response");
        }
        if (cause instanceof IOException) return i18n("update.check_failed.network");
        return i18n("update.failed");
    }
}
