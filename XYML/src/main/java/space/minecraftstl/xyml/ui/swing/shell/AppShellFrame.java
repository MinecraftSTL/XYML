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
package space.minecraftstl.xyml.ui.swing.shell;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;
import space.minecraftstl.xyml.ui.swing.SwingAnimator;
import space.minecraftstl.xyml.ui.swing.SwingThemeManager;

import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.WindowConstants;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/// Hosts {@link AppShellPanel} in a resizable, operating-system-decorated desktop window.
@NotNullByDefault
public final class AppShellFrame extends JFrame {
    /// The shell panel retained for navigation integrations.
    private final AppShellPanel shellPanel;

    /// Creates and packs an operating-system-decorated shell window on the EDT.
    ///
    /// @param title the native window title
    /// @param themeManager the FlatLaf manager initialized before components are created
    /// @param pageFactories one lazy Swing page factory for every destination
    /// @param initialPage the initially selected destination
    /// @param pagePresentations localized labels and mnemonics for every destination
    /// @param animator the shared Swing animator
    /// @param pageTransitionDuration the non-negative caller-selected page transition duration
    public AppShellFrame(
            String title,
            SwingThemeManager themeManager,
            Map<ShellPageId, ? extends ShellPageFactory<? extends JComponent>> pageFactories,
            ShellPageId initialPage,
            ShellPagePresentations pagePresentations,
            SwingAnimator animator,
            Duration pageTransitionDuration) {
        super(initializeTheme(title, themeManager));

        setUndecorated(false);
        setResizable(true);
        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        shellPanel = new AppShellPanel(
                pageFactories, initialPage, pagePresentations, animator, pageTransitionDuration);
        setContentPane(shellPanel);
        setMinimumSize(shellPanel.getMinimumSize());
        pack();
        setLocationByPlatform(true);
    }

    /// Creates a shell frame synchronously on the EDT from any caller thread.
    ///
    /// @param title the native window title
    /// @param themeManager the FlatLaf manager initialized before components are created
    /// @param pageFactories one lazy Swing page factory for every destination
    /// @param initialPage the initially selected destination
    /// @param pagePresentations localized labels and mnemonics for every destination
    /// @param animator the shared Swing animator
    /// @param pageTransitionDuration the non-negative caller-selected page transition duration
    /// @return the packed frame
    public static AppShellFrame create(
            String title,
            SwingThemeManager themeManager,
            Map<ShellPageId, ? extends ShellPageFactory<? extends JComponent>> pageFactories,
            ShellPageId initialPage,
            ShellPagePresentations pagePresentations,
            SwingAnimator animator,
            Duration pageTransitionDuration) {
        AtomicReference<@Nullable AppShellFrame> result = new AtomicReference<>();
        EdtDispatcher.executeAndWait(() -> result.set(new AppShellFrame(
                title,
                themeManager,
                pageFactories,
                initialPage,
                pagePresentations,
                animator,
                pageTransitionDuration)));
        return Objects.requireNonNull(result.get());
    }

    /// Returns the hosted application shell.
    ///
    /// @return the shell panel
    public AppShellPanel shellPanel() {
        return shellPanel;
    }

    /// Makes the packed frame visible on the EDT.
    public void open() {
        EdtDispatcher.execute(() -> setVisible(true));
    }

    /// Releases cached page resources before disposing the native window.
    @Override
    public void dispose() {
        shellPanel.close();
        super.dispose();
    }

    /// Initializes FlatLaf before the {@link JFrame} superclass creates any Swing components.
    ///
    /// @param title the native window title
    /// @param themeManager the theme manager to initialize
    /// @return the validated native window title
    private static String initializeTheme(String title, SwingThemeManager themeManager) {
        EdtDispatcher.requireEventDispatchThread();
        Objects.requireNonNull(themeManager).initialize();
        return Objects.requireNonNull(title);
    }
}
