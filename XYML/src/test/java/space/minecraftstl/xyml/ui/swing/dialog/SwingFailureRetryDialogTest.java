// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.dialog;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
import java.awt.Component;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/// Verifies the explicit Retry/Cancel mapping used by local file write failures.
@NotNullByDefault
public final class SwingFailureRetryDialogTest {
    /// Verifies Retry runs exactly once and Cancel or closure never runs the captured action.
    @Test
    public void mapsRetryAndCancelChoices() throws Exception {
        AtomicInteger retryCalls = new AtomicInteger();
        AtomicReference<String> retryLabel = new AtomicReference<>();
        AtomicReference<String> cancelLabel = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            SwingFailureRetryDialog.show(
                    nullOwner(),
                    "Failed",
                    "locked",
                    retryCalls::incrementAndGet,
                    (owner, message, title, retry, cancel) -> {
                        retryLabel.set(retry);
                        cancelLabel.set(cancel);
                        return 0;
                    });
            assertEquals(1, retryCalls.get());
            assertFalse(retryLabel.get().isBlank());
            assertFalse(cancelLabel.get().isBlank());
            assertNotEquals(retryLabel.get(), cancelLabel.get());

            SwingFailureRetryDialog.show(
                    nullOwner(),
                    "Failed",
                    "locked",
                    retryCalls::incrementAndGet,
                    (owner, message, title, retry, cancel) -> 1);
            assertEquals(1, retryCalls.get());

            SwingFailureRetryDialog.show(
                    nullOwner(),
                    "Failed",
                    "locked",
                    retryCalls::incrementAndGet,
                    (owner, message, title, retry, cancel) -> -1);
            assertEquals(1, retryCalls.get());
        });
    }

    /// Returns a non-null component placeholder accepted by the injected dialog boundary.
    ///
    /// @return headless component placeholder
    private static Component nullOwner() {
        return new Component() {
        };
    }
}
