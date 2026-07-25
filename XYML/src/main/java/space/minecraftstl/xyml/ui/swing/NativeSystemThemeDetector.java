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
package space.minecraftstl.xyml.ui.swing;

import com.sun.jna.Pointer;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import space.minecraftstl.xyml.util.platform.OperatingSystem;
import space.minecraftstl.xyml.util.platform.SystemUtils;
import space.minecraftstl.xyml.util.platform.macos.ObjectiveCRuntime;
import space.minecraftstl.xyml.util.platform.windows.WinReg;

import javax.swing.SwingUtilities;
import java.awt.GraphicsEnvironment;
import java.awt.Toolkit;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static space.minecraftstl.xyml.util.logging.Logger.LOG;

/// Detects the current operating-system light or dark appearance without JavaFX or network access.
///
/// Windows reads the per-user `AppsUseLightTheme` registry value through the launcher's existing JNA
/// wrapper. macOS reads `AppleInterfaceStyle` from `NSUserDefaults` through the existing Objective-C
/// runtime binding. Linux and FreeBSD honor explicit GTK, KDE, and Qt environment preferences before
/// consulting AWT desktop properties. Missing, malformed, or inaccessible values always resolve to light.
@NotNullByDefault
public final class NativeSystemThemeDetector implements SystemThemeDetector {
    /// Windows personalization key containing the application color-mode preference.
    private static final String WINDOWS_PERSONALIZE_KEY =
            "Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize";

    /// Windows personalization value where zero requests dark application surfaces.
    private static final String WINDOWS_APPS_USE_LIGHT_THEME = "AppsUseLightTheme";

    /// macOS global user-default key containing `Dark` while dark appearance is active.
    private static final String MACOS_INTERFACE_STYLE_KEY = "AppleInterfaceStyle";

    /// Matches the terminal XDG portal color-scheme value in `dbus-send` output.
    private static final Pattern PORTAL_COLOR_SCHEME = Pattern.compile("\\b([012])\\s*$");

    /// Platform-specific appearance reader used by the non-throwing public boundary.
    private final ThemeReader themeReader;

    /// Last explicit platform result retained across transient detection failures.
    private final AtomicReference<@Nullable Boolean> lastDetectedTheme = new AtomicReference<>();

    /// Prevents an unavailable native integration from filling the log on every window activation.
    private final AtomicBoolean failureLogged = new AtomicBoolean();

    /// Creates a detector around an explicit reader for platform selection and focused tests.
    ///
    /// @param themeReader reader returning an explicit appearance when one is available
    NativeSystemThemeDetector(ThemeReader themeReader) {
        this.themeReader = Objects.requireNonNull(themeReader, "themeReader");
    }

    /// Creates the detector for the current operating system.
    ///
    /// @return non-throwing current-platform detector
    public static NativeSystemThemeDetector create() {
        ThemeReader reader = switch (OperatingSystem.CURRENT_OS) {
            case WINDOWS -> NativeSystemThemeDetector::readWindowsTheme;
            case MACOS -> NativeSystemThemeDetector::readMacOsTheme;
            case LINUX, FREEBSD -> NativeSystemThemeDetector::readLinuxTheme;
            case UNKNOWN -> Optional::empty;
        };
        return new NativeSystemThemeDetector(reader);
    }

    /// Returns the current platform appearance, falling back to light after every detection failure.
    ///
    /// @return `true` only when a supported platform signal explicitly requests dark appearance
    @Override
    public boolean isDarkTheme() {
        try {
            Optional<Boolean> detected = Objects.requireNonNull(
                    themeReader.read(),
                    "themeReader returned null");
            if (detected.isPresent()) {
                boolean dark = detected.get();
                lastDetectedTheme.set(dark);
                return dark;
            }
        } catch (Throwable failure) {
            if (failureLogged.compareAndSet(false, true)) {
                LOG.warning("Failed to detect the operating-system appearance; using light mode", failure);
            }
        }
        return Boolean.TRUE.equals(lastDetectedTheme.get());
    }

    /// Reads the Windows per-user application theme.
    ///
    /// @return explicit Windows application appearance, or empty when the registry is unavailable
    private static Optional<Boolean> readWindowsTheme() {
        @Nullable WinReg registry = WinReg.INSTANCE;
        if (registry == null) {
            return Optional.empty();
        }
        @Nullable Object value = registry.queryValue(
                WinReg.HKEY.HKEY_CURRENT_USER,
                WINDOWS_PERSONALIZE_KEY,
                WINDOWS_APPS_USE_LIGHT_THEME);
        return value instanceof Number
                ? Optional.of(isWindowsThemeDark(value))
                : Optional.empty();
    }

    /// Interprets one `AppsUseLightTheme` value.
    ///
    /// @param value registry value, or `null` when unavailable
    /// @return `true` only for numeric zero
    static boolean isWindowsThemeDark(@Nullable Object value) {
        return value instanceof Number number && number.intValue() == 0;
    }

    /// Reads `AppleInterfaceStyle` from the macOS global user defaults.
    ///
    /// @return explicit macOS application appearance, or empty when native defaults are unavailable
    private static Optional<Boolean> readMacOsTheme() {
        @Nullable ObjectiveCRuntime objectiveC = ObjectiveCRuntime.INSTANCE;
        if (objectiveC == null) {
            return Optional.empty();
        }

        @Nullable Pointer userDefaultsClass = objectiveC.objc_getClass("NSUserDefaults");
        @Nullable Pointer stringClass = objectiveC.objc_getClass("NSString");
        if (isNull(userDefaultsClass) || isNull(stringClass)) {
            return Optional.empty();
        }

        @Nullable Pointer defaults = objectiveC.objc_msgSend(
                userDefaultsClass,
                objectiveC.sel_registerName("standardUserDefaults"));
        @Nullable Pointer key = objectiveC.objc_msgSend(
                stringClass,
                objectiveC.sel_registerName("stringWithUTF8String:"),
                MACOS_INTERFACE_STYLE_KEY);
        if (isNull(defaults) || isNull(key)) {
            return Optional.empty();
        }

        @Nullable Pointer style = objectiveC.objc_msgSend(
                defaults,
                objectiveC.sel_registerName("stringForKey:"),
                key);
        if (isNull(style)) {
            // Absence is the documented representation of the default light appearance.
            return Optional.of(false);
        }

        @Nullable Pointer utf8 = objectiveC.objc_msgSend(
                style,
                objectiveC.sel_registerName("UTF8String"));
        @Nullable String value = isNull(utf8)
                ? null
                : utf8.getString(0L, StandardCharsets.UTF_8.name());
        return value == null
                ? Optional.empty()
                : Optional.of(isMacOsThemeDark(value));
    }

    /// Interprets the macOS interface-style value.
    ///
    /// @param value global `AppleInterfaceStyle` value, or `null` for the default light style
    /// @return whether the value requests dark appearance
    static boolean isMacOsThemeDark(@Nullable String value) {
        return value != null && "dark".equalsIgnoreCase(value.trim());
    }

    /// Reads Linux and FreeBSD desktop appearance hints without launching external processes.
    ///
    /// XDG portal access is attempted only away from the EDT because the helper has a bounded process wait.
    /// Initialization therefore remains instant, while activation and periodic background refreshes can resolve
    /// the desktop-wide preference used by modern GNOME and KDE sessions.
    ///
    /// @return explicit Linux appearance, or empty when no supported signal is available
    private static Optional<Boolean> readLinuxTheme() {
        if (!SwingUtilities.isEventDispatchThread()) {
            Optional<Boolean> portal = readLinuxPortalTheme();
            if (portal.isPresent()) {
                return portal;
            }
        }
        return detectLinuxTheme(System.getenv(), NativeSystemThemeDetector::readAwtDesktopProperty);
    }

    /// Resolves common GTK, KDE, Qt, and desktop-session appearance hints in deterministic priority order.
    ///
    /// Explicit toolkit preferences take precedence over broader desktop names. Unknown values continue to
    /// the next signal, and a completely unknown environment resolves to light.
    ///
    /// @param environment process environment
    /// @param desktopProperties AWT desktop-property reader
    /// @return whether an explicit supported signal requests dark appearance
    static boolean isLinuxThemeDark(
            Map<String, String> environment,
            DesktopPropertyReader desktopProperties) {
        return detectLinuxTheme(environment, desktopProperties).orElse(false);
    }

    /// Resolves an explicit Linux appearance while preserving unknown as a third state.
    ///
    /// @param environment process environment
    /// @param desktopProperties AWT desktop-property reader
    /// @return explicit dark/light preference, or empty when every signal is ambiguous
    private static Optional<Boolean> detectLinuxTheme(
            Map<String, String> environment,
            DesktopPropertyReader desktopProperties) {
        Objects.requireNonNull(environment, "environment");
        Objects.requireNonNull(desktopProperties, "desktopProperties");

        Optional<Boolean> preference = parseBooleanPreference(
                environment.get("GTK_APPLICATION_PREFER_DARK_THEME"));
        if (preference.isPresent()) {
            return preference;
        }

        preference = parseThemeName(environment.get("GTK_THEME"));
        if (preference.isPresent()) {
            return preference;
        }
        preference = parseThemeName(environment.get("KDE_COLOR_SCHEME"));
        if (preference.isPresent()) {
            return preference;
        }
        preference = parseThemeName(environment.get("QT_STYLE_OVERRIDE"));
        if (preference.isPresent()) {
            return preference;
        }

        preference = parseThemeName(desktopProperties.read("gnome.Net/ThemeName"));
        if (preference.isPresent()) {
            return preference;
        }
        preference = parseThemeName(desktopProperties.read("gnome.Gtk/ColorScheme"));
        if (preference.isPresent()) {
            return preference;
        }
        return Optional.empty();
    }

    /// Reads the standardized freedesktop color-scheme preference through an installed `dbus-send` tool.
    ///
    /// The optional local command has a two-second upper bound, performs no network access, and is never run
    /// on the Swing event dispatch thread. Portal value `1` means dark, `2` means light, and `0` is unknown.
    ///
    /// @return explicit portal preference, or empty when unavailable or unspecified
    private static Optional<Boolean> readLinuxPortalTheme() {
        @Nullable Path dbusSend = SystemUtils.which("dbus-send");
        if (dbusSend == null) {
            return Optional.empty();
        }
        try {
            String output = SystemUtils.run(List.of(
                    dbusSend.toString(),
                    "--session",
                    "--print-reply=literal",
                    "--reply-timeout=1000",
                    "--dest=org.freedesktop.portal.Desktop",
                    "/org/freedesktop/portal/desktop",
                    "org.freedesktop.portal.Settings.Read",
                    "string:org.freedesktop.appearance",
                    "string:color-scheme"), Duration.ofSeconds(2));
            return parsePortalColorScheme(output);
        } catch (Exception failure) {
            return Optional.empty();
        }
    }

    /// Parses the terminal XDG portal color-scheme value.
    ///
    /// @param output `dbus-send` output
    /// @return explicit dark/light preference, or empty for value zero and malformed output
    static Optional<Boolean> parsePortalColorScheme(String output) {
        Objects.requireNonNull(output, "output");
        Matcher matcher = PORTAL_COLOR_SCHEME.matcher(output.trim());
        if (!matcher.find()) {
            return Optional.empty();
        }
        return switch (matcher.group(1)) {
            case "1" -> Optional.of(true);
            case "2" -> Optional.of(false);
            default -> Optional.empty();
        };
    }

    /// Reads one AWT desktop property without initializing a graphical toolkit in headless mode.
    ///
    /// @param name desktop-property name
    /// @return property value, or `null` when headless or unavailable
    private static @Nullable Object readAwtDesktopProperty(String name) {
        Objects.requireNonNull(name, "name");
        if (GraphicsEnvironment.isHeadless()) {
            return null;
        }
        try {
            return Toolkit.getDefaultToolkit().getDesktopProperty(name);
        } catch (Throwable failure) {
            return null;
        }
    }

    /// Interprets a conventional boolean preference.
    ///
    /// @param value environment value, or `null`
    /// @return explicit dark/light preference, or empty for an unknown value
    private static Optional<Boolean> parseBooleanPreference(@Nullable String value) {
        if (value == null) {
            return Optional.empty();
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "1", "true", "yes", "on", "dark" -> Optional.of(true);
            case "0", "false", "no", "off", "light" -> Optional.of(false);
            default -> Optional.empty();
        };
    }

    /// Infers an explicit color preference from a toolkit or desktop theme name.
    ///
    /// @param value theme name or desktop-property value
    /// @return explicit dark/light preference, or empty for an ambiguous name
    private static Optional<Boolean> parseThemeName(@Nullable Object value) {
        if (value == null) {
            return Optional.empty();
        }
        String normalized = value.toString().trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            return Optional.empty();
        }
        if (normalized.contains("dark")
                || normalized.contains("black")
                || normalized.contains("night")
                || normalized.contains("noir")) {
            return Optional.of(true);
        }
        if (normalized.contains("light") || normalized.contains("white")) {
            return Optional.of(false);
        }
        return Optional.empty();
    }

    /// Returns whether a native pointer is absent or points to address zero.
    ///
    /// @param pointer native pointer, or `null`
    /// @return whether the pointer cannot be dereferenced
    private static boolean isNull(@Nullable Pointer pointer) {
        return pointer == null || Pointer.nativeValue(pointer) == 0L;
    }

    /// Reads one optional AWT desktop property for Linux theme detection.
    @FunctionalInterface
    @NotNullByDefault
    interface DesktopPropertyReader {
        /// Reads one property without throwing when the desktop integration is unavailable.
        ///
        /// @param name desktop-property name
        /// @return property value, or `null`
        @Nullable Object read(String name);
    }

    /// Reads one optional platform theme without exposing native failures to the Swing manager.
    @FunctionalInterface
    @NotNullByDefault
    interface ThemeReader {
        /// Reads the current explicit appearance.
        ///
        /// @return explicit dark/light preference, or empty when unavailable
        Optional<Boolean> read();
    }
}
