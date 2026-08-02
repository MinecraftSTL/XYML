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

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.WString;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import space.minecraftstl.xyml.Metadata;
import space.minecraftstl.xyml.util.io.FileUtils;
import space.minecraftstl.xyml.util.io.JarUtils;
import space.minecraftstl.xyml.util.platform.NativeUtils;
import space.minecraftstl.xyml.util.platform.OperatingSystem;
import space.minecraftstl.xyml.util.platform.windows.IPropertyStore;
import space.minecraftstl.xyml.util.platform.windows.Shell32;
import space.minecraftstl.xyml.util.platform.windows.WinTypes;

import java.awt.Window;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

import static space.minecraftstl.xyml.util.logging.Logger.LOG;

/// Integrates XYML's Swing process and native windows with Windows AppUserModel metadata.
@NotNullByDefault
public final class WindowsNativeUtils {
    /// Prevents construction of this platform utility holder.
    private WindowsNativeUtils() {
    }

    /// Sets the process AppUserModelID before the first native Swing window is created.
    public static void setProcessAppUserModelId() {
        @Nullable Shell32 shell32 = availableShell();
        if (shell32 == null) {
            return;
        }

        try {
            int result = shell32.SetCurrentProcessExplicitAppUserModelID(
                    new WString(Metadata.WINDOWS_APP_USER_MODEL_ID));
            if (result < 0) {
                LOG.warning("Failed to set AppUserModelID, HRESULT=0x" + Integer.toHexString(result));
            } else {
                LOG.info("Set AppUserModelID: " + Metadata.WINDOWS_APP_USER_MODEL_ID);
            }
        } catch (Throwable failure) {
            LOG.warning("Failed to set AppUserModelID", failure);
        }
    }

    /// Writes relaunch metadata to a visible Swing window's native property store.
    ///
    /// Values are applied after the native peer is shown because JNA can only resolve the HWND of a
    /// displayable heavyweight component. The AppUserModelID is written last so Windows observes complete
    /// relaunch metadata when it first groups the taskbar window.
    ///
    /// @param window visible native Swing window
    public static void applyAppUserModelRelaunchProperties(Window window) {
        Objects.requireNonNull(window, "window");
        @Nullable Shell32 shell32 = availableShell();
        @Nullable RelaunchProperties properties = relaunchProperties();
        if (shell32 == null || properties == null || !window.isDisplayable()) {
            return;
        }

        try (IPropertyStore store = propertyStore(shell32, window)) {
            if (store == null) {
                return;
            }
            if (!setProperty(store, WinTypes.PROPERTYKEY.PKEY_AppUserModel_RelaunchCommand,
                    properties.command())
                    || !setProperty(store, WinTypes.PROPERTYKEY.PKEY_AppUserModel_RelaunchIconResource,
                    properties.iconResource())
                    || !setProperty(store, WinTypes.PROPERTYKEY.PKEY_AppUserModel_RelaunchDisplayNameResource,
                    Metadata.FULL_NAME)
                    || !setProperty(store, WinTypes.PROPERTYKEY.PKEY_AppUserModel_ID,
                    Metadata.WINDOWS_APP_USER_MODEL_ID)) {
                return;
            }
            LOG.info("Set AppUserModel relaunch properties for the Swing window");
        } catch (Throwable failure) {
            LOG.warning("Failed to set AppUserModel relaunch properties", failure);
        }
    }

    /// Removes relaunch metadata before a Swing window hides or releases its native peer.
    ///
    /// @param window displayable native Swing window
    public static void clearAppUserModelRelaunchProperties(Window window) {
        Objects.requireNonNull(window, "window");
        @Nullable Shell32 shell32 = availableShell();
        if (shell32 == null || !window.isDisplayable()) {
            return;
        }

        try (IPropertyStore store = propertyStore(shell32, window)) {
            if (store == null) {
                return;
            }
            WinTypes.PROPVARIANT empty = new WinTypes.PROPVARIANT();
            empty.setEmpty();
            clearProperty(store, WinTypes.PROPERTYKEY.PKEY_AppUserModel_ID, empty);
            clearProperty(store, WinTypes.PROPERTYKEY.PKEY_AppUserModel_RelaunchDisplayNameResource, empty);
            clearProperty(store, WinTypes.PROPERTYKEY.PKEY_AppUserModel_RelaunchIconResource, empty);
            clearProperty(store, WinTypes.PROPERTYKEY.PKEY_AppUserModel_RelaunchCommand, empty);
        } catch (Throwable failure) {
            LOG.warning("Failed to clear AppUserModel relaunch properties", failure);
        }
    }

    /// Returns the loaded Windows shell API when native integration is available.
    ///
    /// @return loaded shell API, or `null` outside supported Windows processes
    private static @Nullable Shell32 availableShell() {
        if (OperatingSystem.CURRENT_OS != OperatingSystem.WINDOWS || !NativeUtils.USE_JNA) {
            return null;
        }
        return Shell32.INSTANCE;
    }

    /// Resolves relaunch metadata only for the native executable distribution.
    ///
    /// @return immutable relaunch metadata, or `null` for classpath and JAR launches
    private static @Nullable RelaunchProperties relaunchProperties() {
        @Nullable Path executable = JarUtils.thisJarPath();
        if (executable == null
                || !Files.isRegularFile(executable)
                || !"exe".equalsIgnoreCase(FileUtils.getExtension(executable))) {
            return null;
        }
        String executablePath = FileUtils.getAbsolutePath(executable);
        return new RelaunchProperties('"' + executablePath + '"', executablePath + ",0");
    }

    /// Opens the window property store for a displayable AWT peer.
    ///
    /// @param shell32 loaded shell API
    /// @param window displayable native Swing window
    /// @return owned property-store wrapper, or `null` when the HWND or store is unavailable
    private static @Nullable IPropertyStore propertyStore(Shell32 shell32, Window window) {
        @Nullable Pointer nativeHandle = Native.getComponentPointer(window);
        if (nativeHandle == null || Pointer.nativeValue(nativeHandle) == 0L) {
            LOG.warning("Failed to get Swing window handle for AppUserModel relaunch properties");
            return null;
        }
        @Nullable IPropertyStore store = IPropertyStore.forWindow(shell32, new WinTypes.HANDLE(nativeHandle));
        if (store == null) {
            LOG.warning("Failed to call SHGetPropertyStoreForWindow");
        }
        return store;
    }

    /// Writes one string value to a window property store.
    ///
    /// @param store native window property store
    /// @param key property key to write
    /// @param value string value to copy
    /// @return whether the native operation succeeded
    private static boolean setProperty(IPropertyStore store, WinTypes.PROPERTYKEY key, String value) {
        WinTypes.PROPVARIANT property = new WinTypes.PROPVARIANT();
        property.setStringValue(value);
        int result = store.SetValue(key, property);
        if (result < 0) {
            LOG.warning("Failed to set property pid=" + key.pid
                    + " on IPropertyStore, HRESULT=0x" + Integer.toHexString(result));
            return false;
        }
        return true;
    }

    /// Removes one value from a window property store by writing `VT_EMPTY`.
    ///
    /// @param store native window property store
    /// @param key property key to clear
    /// @param empty reusable empty property value
    private static void clearProperty(
            IPropertyStore store,
            WinTypes.PROPERTYKEY key,
            WinTypes.PROPVARIANT empty) {
        int result = store.SetValue(key, empty);
        if (result < 0) {
            LOG.warning("Failed to clear property pid=" + key.pid
                    + " on IPropertyStore, HRESULT=0x" + Integer.toHexString(result));
        }
    }

    /// Immutable executable relaunch metadata written to a Windows property store.
    ///
    /// @param command quoted executable command line
    /// @param iconResource executable icon resource reference
    @NotNullByDefault
    private record RelaunchProperties(String command, String iconResource) {
    }
}
