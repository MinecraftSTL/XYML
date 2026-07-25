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
import space.minecraftstl.xyml.task.Schedulers;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;
import space.minecraftstl.xyml.ui.swing.SwingAnimator;
import space.minecraftstl.xyml.ui.swing.SwingThemeManager;
import space.minecraftstl.xyml.ui.swing.ThemeMode;

import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.Timer;
import javax.swing.WindowConstants;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static space.minecraftstl.xyml.util.logging.Logger.LOG;

/// Hosts {@link AppShellPanel} in a resizable, operating-system-decorated desktop window.
@NotNullByDefault
public final class AppShellFrame extends JFrame {
    /// Delay between foreground system-theme checks; native work runs only while system mode is active.
    private static final int SYSTEM_THEME_REFRESH_DELAY_MILLIS = 5_000;

    /// Theme manager refreshed when the native window returns to the foreground.
    private final SwingThemeManager themeManager;

    /// Foreground-only timer that requests non-overlapping background appearance reads.
    private final Timer systemThemeRefreshTimer;

    /// Prevents window activation and the periodic timer from starting overlapping native reads.
    private final AtomicBoolean systemThemeRefreshPending = new AtomicBoolean();

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

        this.themeManager = Objects.requireNonNull(themeManager, "themeManager");
        setUndecorated(false);
        setResizable(true);
        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        setIconImages(LauncherIconImages.windowIcons());
        shellPanel = new AppShellPanel(
                pageFactories, initialPage, pagePresentations, animator, pageTransitionDuration);
        setContentPane(shellPanel);
        setMinimumSize(shellPanel.getMinimumSize());
        pack();
        setLocationByPlatform(true);
        systemThemeRefreshTimer = new Timer(
                SYSTEM_THEME_REFRESH_DELAY_MILLIS,
                event -> requestSystemThemeRefresh());
        systemThemeRefreshTimer.setRepeats(true);
        addWindowListener(new SystemThemeRefreshListener());
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

    /// Makes the packed frame visible synchronously on the EDT.
    ///
    /// Synchronous dispatch lets production startup observe and clean up after a native visibility
    /// failure instead of leaving a running process with no visible application window.
    public void open() {
        EdtDispatcher.executeAndWait(() -> {
            setVisible(true);
            requestSystemThemeRefresh();
        });
    }

    /// Hides the packed frame synchronously on the EDT without disposing its shell or cached pages.
    public void hideWindow() {
        EdtDispatcher.executeAndWait(() -> {
            systemThemeRefreshTimer.stop();
            setVisible(false);
        });
    }

    /// Enables or disables page interaction synchronously without hiding the application.
    ///
    /// @param enabled whether the frame accepts user input
    public void setInteractionEnabled(boolean enabled) {
        EdtDispatcher.executeAndWait(() -> setEnabled(enabled));
    }

    /// Releases cached page resources before disposing the native window.
    @Override
    public void dispose() {
        systemThemeRefreshTimer.stop();
        disposeInOrder(shellPanel::close, super::dispose);
    }

    /// Runs shell cleanup before native disposal without allowing either failure to skip the other action.
    ///
    /// The first failure is rethrown after both actions have run. When both actions fail, the native-disposal
    /// failure is attached to the shell-cleanup failure as a suppressed exception.
    ///
    /// @param shellCleanup cached page and shell resource cleanup
    /// @param nativeDisposal native window disposal
    static void disposeInOrder(Runnable shellCleanup, Runnable nativeDisposal) {
        Objects.requireNonNull(shellCleanup, "shellCleanup");
        Objects.requireNonNull(nativeDisposal, "nativeDisposal");

        @Nullable Throwable firstFailure = null;
        try {
            shellCleanup.run();
        } catch (Throwable cleanupFailure) {
            firstFailure = cleanupFailure;
        }

        try {
            nativeDisposal.run();
        } catch (Throwable disposalFailure) {
            if (firstFailure == null) {
                firstFailure = disposalFailure;
            } else if (firstFailure != disposalFailure) {
                firstFailure.addSuppressed(disposalFailure);
            }
        }

        if (firstFailure instanceof RuntimeException runtimeException) {
            throw runtimeException;
        }
        if (firstFailure instanceof Error error) {
            throw error;
        }
        if (firstFailure != null) {
            throw new IllegalStateException("Failed to dispose Swing application window", firstFailure);
        }
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

    /// Requests one non-overlapping native appearance read away from the Swing event dispatch thread.
    private void requestSystemThemeRefresh() {
        EdtDispatcher.requireEventDispatchThread();
        if (themeManager.mode() != ThemeMode.SYSTEM
                || !systemThemeRefreshPending.compareAndSet(false, true)) {
            return;
        }
        try {
            Schedulers.io().execute(() -> {
                try {
                    themeManager.refreshSystemTheme();
                } catch (Throwable failure) {
                    LOG.warning("Failed to refresh the operating-system appearance", failure);
                } finally {
                    systemThemeRefreshPending.set(false);
                }
            });
        } catch (RuntimeException failure) {
            systemThemeRefreshPending.set(false);
            LOG.warning("Failed to schedule the operating-system appearance refresh", failure);
        }
    }

    /// Refreshes system theme mode whenever the application returns to the foreground.
    ///
    /// Window activation performs an immediate background read and starts a foreground-only Swing timer.
    /// The timer merely checks the current mode on the EDT; native work is non-overlapping, runs on the shared
    /// I/O executor only for system mode, and stops whenever this frame is hidden, deactivated, or disposed.
    @NotNullByDefault
    private final class SystemThemeRefreshListener extends WindowAdapter {
        /// Rechecks the operating-system appearance after native activation.
        ///
        /// @param event native activation event
        @Override
        public void windowActivated(WindowEvent event) {
            Objects.requireNonNull(event, "event");
            requestSystemThemeRefresh();
            systemThemeRefreshTimer.start();
        }

        /// Stops periodic reads while another native window owns the foreground.
        ///
        /// @param event native deactivation event
        @Override
        public void windowDeactivated(WindowEvent event) {
            Objects.requireNonNull(event, "event");
            systemThemeRefreshTimer.stop();
        }
    }
}
