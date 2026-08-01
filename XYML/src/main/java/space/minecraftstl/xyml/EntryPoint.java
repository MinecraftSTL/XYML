/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2020  huangyuhui <huanghongxun2008@126.com> and contributors
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
package space.minecraftstl.xyml;

import space.minecraftstl.xyml.util.SwingUtils;
import space.minecraftstl.xyml.java.JavaRuntime;
import space.minecraftstl.xyml.setting.SambaException;
import space.minecraftstl.xyml.setting.SettingsManager;
import space.minecraftstl.xyml.ui.swing.SwingFontAntialiasing;
import space.minecraftstl.xyml.ui.swing.page.settings.FontAntialiasingMode;
import space.minecraftstl.xyml.ui.swing.startup.SwingStartupSafetyDialogs;
import space.minecraftstl.xyml.util.io.FileUtils;
import space.minecraftstl.xyml.util.io.JarUtils;
import space.minecraftstl.xyml.util.platform.CommandBuilder;
import space.minecraftstl.xyml.util.platform.OperatingSystem;
import space.minecraftstl.xyml.ui.swing.shell.LauncherIconImages;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import javax.swing.JOptionPane;
import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static space.minecraftstl.xyml.util.logging.Logger.LOG;
import static space.minecraftstl.xyml.util.i18n.I18n.i18n;

/// Prepares process-wide directories and AWT settings before starting the Swing launcher.
@NotNullByDefault
public final class EntryPoint {
    /// Prevents utility instantiation.
    private EntryPoint() {
    }

    /// Starts the launcher after configuring process-wide networking, logging, and native UI behavior.
    ///
    /// @param args launcher and updater arguments
    public static void main(String @Unmodifiable [] args) {
        System.getProperties().putIfAbsent("java.net.useSystemProxies", "true");
        System.getProperties().putIfAbsent("http.agent", "XYML/" + Metadata.VERSION);

        createXYMLDirectories();
        LOG.start(Metadata.XYML_LOCAL_HOME.resolve("logs"));

        setupAwtVmOptions();
        enableUnsafeMemoryAccess();

        try {
            SettingsManager.init();
        } catch (SambaException e) {
            showWarning(i18n("fatal.samba"));
        } catch (IOException e) {
            LOG.error("Failed to load config", e);
            checkConfigOwner();
            SwingUtils.showErrorDialog(i18n(
                    "fatal.config_loading_failure",
                    SettingsManager.localConfigDirectory()));
            exit(1);
        }

        SwingFontAntialiasing.applyAtStartup(FontAntialiasingMode.fromPersistedValue(
                SettingsManager.userSettings().fontAntiAliasingProperty().get()));
        checkWine();

        if (OperatingSystem.CURRENT_OS == OperatingSystem.MACOS) {
            System.getProperties().putIfAbsent("apple.awt.application.appearance", "system");
            if (!isInsideMacAppBundle())
                initIcon();
        }

        Launcher.main(args);
    }

    /// Flushes pending saves and logs before terminating the process.
    ///
    /// @param exitCode process exit status
    public static void exit(int exitCode) {
        SettingsManager.shutdown();
        LOG.shutdown();
        System.exit(exitCode);
    }

    /// Maps supported launcher environment overrides to the Java 2D and Swing runtime.
    private static void setupAwtVmOptions() {
        if ("true".equalsIgnoreCase(System.getenv("XYML_FORCE_GPU"))) {
            LOG.info("XYML_FORCE_GPU: true");
            if (OperatingSystem.CURRENT_OS == OperatingSystem.MACOS) {
                System.getProperties().putIfAbsent("sun.java2d.metal", "true");
            } else {
                System.getProperties().putIfAbsent("sun.java2d.opengl", "true");
            }
        }

        @Nullable String uiScale = System.getProperty("xyml.uiScale", System.getenv("XYML_UI_SCALE"));
        if (uiScale != null) {
            uiScale = uiScale.trim();

            LOG.info("XYML_UI_SCALE: " + uiScale);

            try {
                float scaleValue;
                if (uiScale.endsWith("%")) {
                    scaleValue = Integer.parseInt(uiScale.substring(0, uiScale.length() - 1)) / 100.0f;
                } else if (uiScale.endsWith("dpi") || uiScale.endsWith("DPI")) {
                    scaleValue = Integer.parseInt(uiScale.substring(0, uiScale.length() - 3)) / 96.0f;
                } else {
                    scaleValue = Float.parseFloat(uiScale);
                }

                float lowerBound = OperatingSystem.CURRENT_OS == OperatingSystem.WINDOWS ? 0.25f : 0.01f;
                float upperBound = OperatingSystem.CURRENT_OS == OperatingSystem.WINDOWS ? 4f : 10f;

                if (scaleValue >= lowerBound && scaleValue <= upperBound) {
                    System.getProperties().putIfAbsent("sun.java2d.uiScale", Float.toString(scaleValue));
                } else {
                    LOG.warning("UI scale out of range: " + uiScale);
                }
            } catch (Throwable e) {
                LOG.warning("Invalid UI scale: " + uiScale);
            }
        }
    }

    /// Creates launcher data directories before logging and settings initialization.
    private static void createXYMLDirectories() {
        if (!Files.isDirectory(Metadata.XYML_LOCAL_HOME)) {
            try {
                Files.createDirectories(Metadata.XYML_LOCAL_HOME);
                if (OperatingSystem.CURRENT_OS == OperatingSystem.WINDOWS && !Metadata.PACKAGED) {
                    try {
                        Files.setAttribute(Metadata.XYML_LOCAL_HOME, "dos:hidden", true);
                    } catch (IOException e) {
                        LOG.warning("Failed to set hidden attribute of " + Metadata.XYML_LOCAL_HOME, e);
                    }
                }
            } catch (IOException e) {
                // Logger has not been started yet, so print directly to System.err
                System.err.println("Failed to create XYML directory: " + Metadata.XYML_LOCAL_HOME);
                e.printStackTrace(System.err);
                showErrorAndExit(i18n("fatal.create_xyml_current_directory_failure", Metadata.XYML_LOCAL_HOME));
            }
        }

        if (!Files.isDirectory(Metadata.XYML_USER_HOME)) {
            try {
                Files.createDirectories(Metadata.XYML_USER_HOME);
            } catch (IOException e) {
                LOG.warning("Failed to create XYML user home " + Metadata.XYML_USER_HOME, e);
            }
        }
    }

    /// Returns whether the running launcher JAR is located inside a macOS application bundle.
    ///
    /// @return true when a parent `Contents` directory belongs to an application bundle
    private static boolean isInsideMacAppBundle() {
        @Nullable Path thisJar = JarUtils.thisJarPath();
        if (thisJar == null)
            return false;

        for (Path current = thisJar.getParent();
             current != null && current.getParent() != null;
             current = current.getParent()
        ) {
            if ("Contents".equals(FileUtils.getName(current))
                    && FileUtils.getName(current.getParent()).endsWith(".app")
                    && Files.exists(current.resolve("Info.plist"))
            ) {
                return true;
            }
        }
        return false;
    }

    /// Installs the launcher icon when macOS is not already managing it through an application bundle.
    private static void initIcon() {
        try {
            List<java.awt.Image> iconImages = LauncherIconImages.windowIcons();
            if (java.awt.Taskbar.isTaskbarSupported() && !iconImages.isEmpty()) {
                java.awt.Taskbar.getTaskbar().setIconImage(iconImages.get(iconImages.size() - 1));
            }
        } catch (Throwable e) {
            LOG.warning("Failed to set application icon", e);
        }
    }

    /// Warns before running under Wine because native integrations may behave differently.
    private static void checkWine() {
        if (OperatingSystem.isRunningUnderWine()) {
            LOG.warning("XYML is running under Wine or its distributions!");
            showWarning(i18n("fatal.wine_warning"));
        }
    }

    /// Offers a Unix ownership repair command when the configuration directory is not writable.
    private static void checkConfigOwner() {
        if (OperatingSystem.CURRENT_OS == OperatingSystem.WINDOWS)
            return;

        String userName = Objects.requireNonNullElse(System.getProperty("user.name"), "");
        Path configDirectory = SettingsManager.localConfigDirectory();
        if (!Files.exists(configDirectory))
            return;

        String owner;
        try {
            owner = Files.getOwner(configDirectory).getName();
        } catch (IOException e) {
            LOG.warning("Failed to get file owner", e);
            return;
        }

        if (Files.isWritable(configDirectory) || userName.equals("root") || userName.equals(owner))
            return;

        ArrayList<String> files = new ArrayList<>();
        files.add(configDirectory.toString());
        if (Files.exists(Metadata.XYML_USER_HOME))
            files.add(Metadata.XYML_USER_HOME.toString());

        Path gameDirectory = Paths.get(".minecraft").toAbsolutePath().normalize();
        if (Files.exists(gameDirectory))
            files.add(gameDirectory.toString());

        String command = new CommandBuilder()
                .addAll("sudo", "chown", "-R", userName)
                .addAll(files)
                .toString();
        SwingStartupSafetyDialogs.offerCopyAndExit(
                i18n("fatal.config_loading_failure.unix", owner, command),
                command);
        exit(1);
    }

    /// Displays a cancellable warning before the primary Swing runtime is initialized.
    ///
    /// @param message localized warning text
    private static void showWarning(String message) {
        SwingUtils.initLookAndFeel();
        int result = JOptionPane.showOptionDialog(
                null,
                message,
                i18n("message.warning"),
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.WARNING_MESSAGE,
                null,
                null,
                null);
        if (result == JOptionPane.CANCEL_OPTION || result == JOptionPane.CLOSED_OPTION)
            exit(1);
    }

    /// Enables the JDK compatibility switch required by Java 24 and 25 memory-access warnings.
    private static void enableUnsafeMemoryAccess() {
        // https://openjdk.org/jeps/498
        if (JavaRuntime.CURRENT_VERSION == 24 || JavaRuntime.CURRENT_VERSION == 25) {
            try {
                Class<?> clazz = Class.forName("sun.misc.Unsafe");
                boolean ignored = (boolean) MethodHandles.privateLookupIn(clazz, MethodHandles.lookup())
                        .findStatic(clazz, "trySetMemoryAccessWarned", MethodType.methodType(boolean.class))
                        .invokeExact();
            } catch (Throwable e) {
                LOG.warning("Failed to enable unsafe memory access", e);
            }
        }
    }

    /// Displays a fatal startup error through Swing and terminates the process.
    ///
    /// @param message localized error text
    private static void showErrorAndExit(String message) {
        SwingUtils.showErrorDialog(message);
        exit(1);
    }
}
