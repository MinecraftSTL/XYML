/*
 * HMCLauncher for Windows
 * Copyright (C) 2025 huangyuhui and contributors
 * Modified by MinecraftSTL in 2026 for XYMLL.
 * SPDX-License-Identifier: GPL-3.0-only
 * See ../README.md for the GPLv3 Section 7 additional terms.
 */
#pragma once

#include <windows.h>

struct XYMLLI18N {
  static XYMLLI18N Instance();

  // Error Messages
  LPCWSTR errorSelfPath;
  LPCWSTR errorInvalidXYMLJavaHome;
  LPCWSTR errorJavaNotFound;
};
