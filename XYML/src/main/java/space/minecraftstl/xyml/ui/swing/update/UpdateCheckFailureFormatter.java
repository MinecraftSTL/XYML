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
