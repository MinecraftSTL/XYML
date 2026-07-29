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

import com.formdev.flatlaf.FlatClientProperties;
import com.formdev.flatlaf.FlatLaf;
import com.formdev.flatlaf.util.SystemInfo;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import space.minecraftstl.xyml.Metadata;
import space.minecraftstl.xyml.task.Schedulers;
import space.minecraftstl.xyml.theme.ThemeBrightnessPreference;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;
import space.minecraftstl.xyml.ui.swing.SwingAnimator;
import space.minecraftstl.xyml.ui.swing.SwingThemeManager;
import space.minecraftstl.xyml.ui.swing.page.home.HomeStrings;
import space.minecraftstl.xyml.ui.swing.task.TaskProgressStrings;

import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JRootPane;
import javax.swing.Timer;
import javax.swing.UIManager;
import javax.swing.WindowConstants;
import java.awt.Color;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static space.minecraftstl.xyml.util.logging.Logger.LOG;

/// Hosts {@link AppShellPanel} in a resizable desktop window with platform-appropriate FlatLaf chrome.
@NotNullByDefault
public final class AppShellFrame extends JFrame {
    /// Delay between foreground system-theme checks; native work runs only for system-dependent preferences.
    private static final int SYSTEM_THEME_REFRESH_DELAY_MILLIS = 5_000;

    /// Theme manager refreshed when the native window returns to the foreground.
    private final SwingThemeManager themeManager;

    /// Foreground-only timer that requests non-overlapping background appearance reads.
    private final Timer systemThemeRefreshTimer;

    /// Prevents window activation and the periodic timer from starting overlapping native reads.
    private final AtomicBoolean systemThemeRefreshPending = new AtomicBoolean();

    /// The shell panel retained for navigation integrations.
    private final AppShellPanel shellPanel;

    /// Background decoder and theme-window appearance subscription.
    private final SwingWindowBackgroundController backgroundController;

    /// Whether this platform and selected graphics device support per-pixel window transparency.
    private final boolean windowTransparencySupported;

    /// Whether per-pixel transparency is currently active on this native peer.
    private boolean windowTransparencyActive;

    /// Creates and packs a native- or client-decorated shell window on the EDT.
    ///
    /// @param title the native window title
    /// @param themeManager the FlatLaf manager initialized before components are created
    /// @param pageFactories one lazy Swing page factory for every destination
    /// @param pagePresentations localized labels and mnemonics for every destination
    /// @param toolbarModels non-owning launcher workflow models used by the title bar
    /// @param homeStrings localized title-bar launch controls
    /// @param taskProgressStrings localized launch progress controls
    /// @param animator the shared Swing animator
    /// @param pageTransitionDuration the non-negative caller-selected page transition duration
    /// @param progressAnimationDuration non-negative launch progress animation duration
    public AppShellFrame(
            String title,
            SwingThemeManager themeManager,
            Map<ShellPageId, ? extends ShellPageFactory<? extends JComponent>> pageFactories,
            ShellPagePresentations pagePresentations,
            ShellToolbarModels toolbarModels,
            HomeStrings homeStrings,
            TaskProgressStrings taskProgressStrings,
            SwingAnimator animator,
            Duration pageTransitionDuration,
            Duration progressAnimationDuration) {
        super(initializeTheme(title, themeManager));

        this.themeManager = Objects.requireNonNull(themeManager, "themeManager");
        windowTransparencySupported = detectWindowTransparencySupport();
        configureWindowChrome();
        setResizable(true);
        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        setIconImages(LauncherIconImages.windowIcons());
        shellPanel = new AppShellPanel(
                title,
                pageFactories,
                pagePresentations,
                toolbarModels,
                homeStrings,
                taskProgressStrings,
                animator,
                pageTransitionDuration,
                progressAnimationDuration);
        setContentPane(shellPanel);
        backgroundController = new SwingWindowBackgroundController(
                themeManager,
                this,
                shellPanel,
                Schedulers.io(),
                Metadata.XYML_LOCAL_HOME.resolve("cache").resolve("backgrounds"));
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
    /// @param pagePresentations localized labels and mnemonics for every destination
    /// @param toolbarModels non-owning launcher workflow models used by the title bar
    /// @param homeStrings localized title-bar launch controls
    /// @param taskProgressStrings localized launch progress controls
    /// @param animator the shared Swing animator
    /// @param pageTransitionDuration the non-negative caller-selected page transition duration
    /// @param progressAnimationDuration non-negative launch progress animation duration
    /// @return the packed frame
    public static AppShellFrame create(
            String title,
            SwingThemeManager themeManager,
            Map<ShellPageId, ? extends ShellPageFactory<? extends JComponent>> pageFactories,
            ShellPagePresentations pagePresentations,
            ShellToolbarModels toolbarModels,
            HomeStrings homeStrings,
            TaskProgressStrings taskProgressStrings,
            SwingAnimator animator,
            Duration pageTransitionDuration,
            Duration progressAnimationDuration) {
        AtomicReference<@Nullable AppShellFrame> result = new AtomicReference<>();
        EdtDispatcher.executeAndWait(() -> result.set(new AppShellFrame(
                title,
                themeManager,
                pageFactories,
                pagePresentations,
                toolbarModels,
                homeStrings,
                taskProgressStrings,
                animator,
                pageTransitionDuration,
                progressAnimationDuration)));
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
        disposeInOrder(
                () -> disposeInOrder(backgroundController::close, shellPanel::close),
                super::dispose);
    }

    /// Applies native transparency when supported and always synchronizes the shell paint mode.
    ///
    /// @param requested whether the resolved appearance requests transparency
    /// @return whether transparency is actually active
    boolean applyWindowTransparency(boolean requested) {
        EdtDispatcher.requireEventDispatchThread();
        boolean activate = requested && windowTransparencySupported;
        try {
            applyWindowTransparencyState(activate);
        } catch (RuntimeException | Error failure) {
            if (!activate) {
                throw failure;
            }
            LOG.warning("Failed to enable native window transparency; keeping an opaque window", failure);
            applyWindowTransparencyState(false);
        }
        return windowTransparencyActive;
    }

    /// Returns whether the current graphics environment can render a transparent shell window.
    ///
    /// @return platform transparency capability
    boolean windowTransparencySupported() {
        return windowTransparencySupported;
    }

    /// Returns the native transparency state last applied by the appearance controller.
    ///
    /// @return active transparency state
    boolean windowTransparencyActive() {
        return windowTransparencyActive;
    }

    /// Mutates root-pane opacity and native background as one EDT-confined operation.
    ///
    /// @param transparent target native transparency state
    private void applyWindowTransparencyState(boolean transparent) {
        Color opaqueColor = themeSurfaceColor();
        Color nativeColor = transparent ? new Color(0, 0, 0, 0) : opaqueColor;
        setBackground(nativeColor);
        getRootPane().setOpaque(!transparent);
        getLayeredPane().setOpaque(!transparent);
        getRootPane().setBackground(nativeColor);
        getLayeredPane().setBackground(nativeColor);
        shellPanel.setBackground(nativeColor);
        shellPanel.setWindowTransparency(transparent);
        windowTransparencyActive = transparent;
    }

    /// Returns the current FlatLaf surface with a stable light fallback.
    ///
    /// @return opaque surface color
    private static Color themeSurfaceColor() {
        @Nullable Color color = UIManager.getColor("Panel.background");
        return color != null ? new Color(color.getRed(), color.getGreen(), color.getBlue()) : Color.WHITE;
    }

    /// Detects whether a client-decorated frame can expose per-pixel alpha on this graphics device.
    ///
    /// macOS retains its native title-bar controls, so its decorated frame intentionally reports unsupported.
    ///
    /// @return whether native transparency can be enabled safely
    private static boolean detectWindowTransparencySupport() {
        if (GraphicsEnvironment.isHeadless() || SystemInfo.isMacOS) {
            return false;
        }
        try {
            GraphicsDevice device = GraphicsEnvironment
                    .getLocalGraphicsEnvironment()
                    .getDefaultScreenDevice();
            return device.isWindowTranslucencySupported(GraphicsDevice.WindowTranslucency.PERPIXEL_TRANSLUCENT);
        } catch (RuntimeException | Error failure) {
            LOG.warning("Failed to detect native window transparency support", failure);
            return false;
        }
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

    /// Extends Swing content into the title bar while retaining platform-appropriate window controls.
    ///
    /// Transparency-capable Windows and Linux devices use FlatLaf client decorations so the native peer may expose
    /// per-pixel alpha. Other supported desktops retain native decorations when FlatLaf can integrate with them.
    /// macOS always retains its native leading traffic-light controls and degrades window transparency to opaque.
    private void configureWindowChrome() {
        EdtDispatcher.requireEventDispatchThread();
        JRootPane rootPane = getRootPane();
        rootPane.putClientProperty(FlatClientProperties.USE_WINDOW_DECORATIONS, true);
        rootPane.putClientProperty(FlatClientProperties.FULL_WINDOW_CONTENT, true);
        rootPane.putClientProperty(FlatClientProperties.TITLE_BAR_SHOW_ICON, false);
        rootPane.putClientProperty(FlatClientProperties.TITLE_BAR_SHOW_TITLE, false);
        rootPane.putClientProperty(FlatClientProperties.TITLE_BAR_SHOW_ICONIFFY, true);
        rootPane.putClientProperty(FlatClientProperties.TITLE_BAR_SHOW_MAXIMIZE, true);
        rootPane.putClientProperty(FlatClientProperties.TITLE_BAR_SHOW_CLOSE, true);
        rootPane.putClientProperty(FlatClientProperties.TITLE_BAR_HEIGHT, AppShellPanel.HEADER_HEIGHT);

        if (SystemInfo.isMacOS) {
            setUndecorated(false);
            rootPane.putClientProperty("apple.awt.fullWindowContent", true);
            rootPane.putClientProperty("apple.awt.transparentTitleBar", true);
            rootPane.putClientProperty("apple.awt.windowTitleVisible", false);
        } else if (windowTransparencySupported) {
            setUndecorated(true);
            rootPane.setWindowDecorationStyle(JRootPane.FRAME);
        } else if (FlatLaf.supportsNativeWindowDecorations()) {
            setUndecorated(false);
        } else {
            setUndecorated(true);
            rootPane.setWindowDecorationStyle(JRootPane.FRAME);
        }
    }

    /// Requests one non-overlapping native appearance read away from the Swing event dispatch thread.
    private void requestSystemThemeRefresh() {
        EdtDispatcher.requireEventDispatchThread();
        ThemeBrightnessPreference preference = themeManager.brightnessPreference();
        if ((preference != ThemeBrightnessPreference.THEME
                && preference != ThemeBrightnessPreference.SYSTEM)
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

    /// Refreshes system-dependent theme state whenever the application returns to the foreground.
    ///
    /// Window activation performs an immediate background read and starts a foreground-only Swing timer.
    /// The timer merely checks the current mode on the EDT; native work is non-overlapping, runs on the shared
    /// I/O executor only for system-dependent preferences, and stops whenever this frame is hidden, deactivated,
    /// or disposed.
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
