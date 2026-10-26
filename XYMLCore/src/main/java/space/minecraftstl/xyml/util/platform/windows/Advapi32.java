// SPDX-FileCopyrightText: Copyright (C) 2025 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.util.platform.windows;

import com.sun.jna.Pointer;
import com.sun.jna.WString;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;
import com.sun.jna.win32.StdCallLibrary;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import space.minecraftstl.xyml.util.platform.NativeUtils;

/// Native Windows registry functions exposed through JNA.
///
/// @author Glavo
@NotNullByDefault
public interface Advapi32 extends StdCallLibrary {

    /// Loaded native binding, or `null` when JNA or Windows is unavailable.
    @Nullable Advapi32 INSTANCE = NativeUtils.USE_JNA && com.sun.jna.Platform.isWindows()
            ? NativeUtils.load("advapi32", Advapi32.class)
            : null;

    /**
     * @see <a href="https://learn.microsoft.com/windows/win32/api/winreg/nf-winreg-regopenkeyexw">RegOpenKeyExW function</a>
     */
    int RegOpenKeyExW(Pointer hKey, WString lpSubKey, int ulOptions, int samDesired, PointerByReference phkResult);

    /**
     * @see <a href="https://learn.microsoft.com/windows/win32/api/winreg/nf-winreg-regclosekey">RegCloseKey function</a>
     */
    int RegCloseKey(Pointer hKey);

    /**
     * @see <a href="https://learn.microsoft.com/windows/win32/api/winreg/nf-winreg-regqueryvalueexw">RegQueryValueExW function</a>
     */
    int RegQueryValueExW(Pointer hKey, WString lpValueName, Pointer lpReserved, IntByReference lpType, Pointer lpData, IntByReference lpcbData);

    /**
     * @see <a href="https://learn.microsoft.com/windows/win32/api/winreg/nf-winreg-regenumkeyexw">RegEnumKeyExW function</a>
     */
    int RegEnumKeyExW(Pointer hKey, int dwIndex,
                      Pointer lpName, IntByReference lpcchName,
                      IntByReference lpReserved,
                      Pointer lpClass, IntByReference lpcchClass,
                      Pointer lpftLastWriteTime);

    /// Creates or opens a registry key.
    ///
    /// @see <a href="https://learn.microsoft.com/windows/win32/api/winreg/nf-winreg-regcreatekeyexw">RegCreateKeyExW function</a>
    int RegCreateKeyExW(
            Pointer hKey,
            WString lpSubKey,
            int reserved,
            @Nullable WString lpClass,
            int dwOptions,
            int samDesired,
            @Nullable Pointer lpSecurityAttributes,
            PointerByReference phkResult,
            @Nullable IntByReference lpdwDisposition);

    /// Writes one registry value.
    ///
    /// @see <a href="https://learn.microsoft.com/windows/win32/api/winreg/nf-winreg-regsetvalueexw">RegSetValueExW function</a>
    int RegSetValueExW(
            Pointer hKey,
            WString lpValueName,
            int reserved,
            int dwType,
            Pointer lpData,
            int cbData);

    /// Deletes one registry value.
    ///
    /// @see <a href="https://learn.microsoft.com/windows/win32/api/winreg/nf-winreg-regdeletevaluew">RegDeleteValueW function</a>
    int RegDeleteValueW(Pointer hKey, WString lpValueName);
}
