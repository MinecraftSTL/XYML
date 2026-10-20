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
