/*
 * HMCLauncher for Windows
 * Copyright (C) 2025 huangyuhui and contributors
 * Modified by MinecraftSTL in 2026 for XYMLL.
 * SPDX-License-Identifier: GPL-3.0-only
 * See ../README.md for the GPLv3 Section 7 additional terms.
 */
#pragma once

#include "path.h"

extern bool HLVerboseOutput;

bool HLAttachConsole(bool force = false);

void HLStartDebugLogger(const HLPath &xymlCurrentDir);

void HLDebugLog(const std::wstring &message);

#define HLDebugLogVerbose(message) \
  do {                             \
    if (HLVerboseOutput) {         \
      HLDebugLog(message);         \
    }                              \
  } while (0)
