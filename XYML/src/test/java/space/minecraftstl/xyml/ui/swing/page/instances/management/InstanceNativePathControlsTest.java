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
