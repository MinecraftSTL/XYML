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
package space.minecraftstl.xyml.ui.swing.page.nbt;

import net.miginfocom.swing.MigLayout;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import space.minecraftstl.xyml.task.Schedulers;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.Font;
import java.awt.Frame;
import java.awt.Window;
import java.util.Objects;
import java.util.concurrent.Executor;

/// Embeddable settings page that owns the sole user-facing NBT file entry point.
///
/// The containing frame is resolved at click time so this panel can be constructed and cached
/// before it is attached to the application window. The owned launcher reuses one modeless editor
/// and is closed together with the settings center.
@NotNullByDefault
public final class NBTSettingsPanel extends JPanel implements AutoCloseable {
    /// Stable localized page strings.
    private final NBTEditorStrings strings;

    /// File-selection and editor-window lifecycle owned by this page.
    private final SwingNBTEditorLauncher launcher;

    /// Sole command that opens a local NBT document.
    private final JButton openButton;

    /// Whether this page has released its editor lifecycle.
    private boolean closed;

    /// Creates the production settings tool with the shared launcher I/O executor.
    ///
    /// @return configured NBT settings page
    public static NBTSettingsPanel createForCurrentLauncher() {
        EdtDispatcher.requireEventDispatchThread();
        return new NBTSettingsPanel(Schedulers.io());
    }

    /// Creates a settings tool with caller-owned asynchronous execution.
    ///
    /// @param ioExecutor executor used for NBT document and bundled-icon I/O
    public NBTSettingsPanel(Executor ioExecutor) {
        super(new MigLayout("insets 20, fillx, wrap 1", "[grow,fill]", "[]16[]"));
        EdtDispatcher.requireEventDispatchThread();
        strings = NBTEditorStrings.localized();
        launcher = SwingNBTEditorLauncher.create(
                this,
                this::resolveOwnerFrame,
                Objects.requireNonNull(ioExecutor, "ioExecutor"));
        openButton = new JButton(strings.openTooltip());
        configureComponents();
    }

    /// Creates a deterministic page for headless component tests.
    ///
    /// @param strings stable localized text
    /// @param launcher injected editor lifecycle
    NBTSettingsPanel(
            NBTEditorStrings strings,
            SwingNBTEditorLauncher launcher) {
        super(new MigLayout("insets 20, fillx, wrap 1", "[grow,fill]", "[]16[]"));
        EdtDispatcher.requireEventDispatchThread();
        this.strings = Objects.requireNonNull(strings, "strings");
        this.launcher = Objects.requireNonNull(launcher, "launcher");
        openButton = new JButton(this.strings.openTooltip());
        configureComponents();
    }

    /// Returns the localized settings tab title.
    ///
    /// @return stable NBT editor title
    public String tabTitle() {
        EdtDispatcher.requireEventDispatchThread();
        return strings.title();
    }

    /// Returns the stable file-open action for focused settings tests.
    ///
    /// @return settings NBT open button
    public JButton openButton() {
        EdtDispatcher.requireEventDispatchThread();
        return openButton;
    }

    /// Closes the current editor, disables the entry point, and rejects future actions.
    @Override
    public void close() {
        EdtDispatcher.executeAndWait(() -> {
            if (closed) {
                return;
            }
            closed = true;
            openButton.setEnabled(false);
            launcher.close();
        });
    }

    /// Builds the compact settings-page content and wires its sole command.
    private void configureComponents() {
        setOpaque(false);
        JLabel heading = new JLabel(strings.title());
        heading.setFont(heading.getFont().deriveFont(Font.BOLD, 20.0F));
        openButton.setName("settingsOpenNbtFile");
        openButton.addActionListener(event -> openEditor());
        add(heading, "growx");
        add(openButton, "alignx left");
    }

    /// Opens the chooser only while this page belongs to a live launcher frame.
    private void openEditor() {
        EdtDispatcher.requireEventDispatchThread();
        if (!closed && resolveOwnerFrame() != null) {
            launcher.chooseAndOpen();
        }
    }

    /// Resolves the current containing frame without retaining a native window reference.
    ///
    /// @return current containing frame, or null while this page is detached
    private @Nullable Frame resolveOwnerFrame() {
        @Nullable Window owner = SwingUtilities.getWindowAncestor(this);
        return owner instanceof Frame frame ? frame : null;
    }
}
