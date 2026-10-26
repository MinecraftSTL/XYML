// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.instances.management;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import javax.swing.JButton;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/// Checks the composed chooser's user-visible result and stale-context isolation on the EDT.
@NotNullByDefault
public final class InstanceNativePathControlsTest {
    /// Confirmed directory selection writes one absolute path while cancellation preserves manual text.
    @Test
    void choosesAndCancels() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JTextField field = new JTextField("manual");
            Object context = new Object();
            InstanceNativePathControls controls = new InstanceNativePathControls(field, () -> context,
                    () -> Path.of("native-libraries"));
            ((JButton) controls.component().getComponent(1)).doClick();
            assertEquals(Path.of("native-libraries").toAbsolutePath().normalize().toString(), field.getText());
            controls.close();
            InstanceNativePathControls cancelled = new InstanceNativePathControls(field, () -> context, () -> null);
            String previous = field.getText();
            ((JButton) cancelled.component().getComponent(1)).doClick();
            assertEquals(previous, field.getText());
            cancelled.close();
        });
    }

    /// A context changed while the modal chooser is open cannot receive its stale result.
    @Test
    void rejectsChangedContextAndDisabledEditing() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JTextField field = new JTextField("original");
            AtomicReference<Object> context = new AtomicReference<>(new Object());
            InstanceNativePathControls controls = new InstanceNativePathControls(field, context::get, () -> {
                context.set(new Object());
                return Path.of("stale");
            });
            ((JButton) controls.component().getComponent(1)).doClick();
            assertEquals("original", field.getText());
            controls.updateAvailability(false);
            assertFalse(controls.component().getComponent(1).isEnabled());
            controls.close();
        });
    }
}
