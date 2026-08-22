/*
 * HMCLauncher for Windows
 * Copyright (C) 2025 huangyuhui and contributors
 * Modified by MinecraftSTL in 2026 for XYMLL.
 * SPDX-License-Identifier: GPL-3.0-only
 * See ../README.md for the GPLv3 Section 7 additional terms.
 */
#include <windows.h>

#include "i18n.h"

#include "config.h"

XYMLLI18N XYMLLI18N::Instance() {
  XYMLLI18N i18n = {.errorSelfPath = L"Failed to get the executable path.",
                 .errorInvalidXYMLJavaHome =
                     L"The Java path specified by XYML_JAVA_HOME is invalid. Please update it to a valid Java "
                     "installation path or remove this environment variable.",
                 .errorJavaNotFound =
                     L"XYML requires Java " XYML_EXPECTED_JAVA_MAJOR_VERSION_STR " or later to run,\n"
                     L"Click 'OK' to open the Java download page.\n"
                     L"Please restart XYML after installing Java."};

  const auto language = GetUserDefaultUILanguage();
  if (language == 2052) {  // zh-CN
    i18n.errorSelfPath = L"获取程序路径失败。";
    i18n.errorInvalidXYMLJavaHome = L"XYML_JAVA_HOME 所指向的 Java 路径无效，请更新或删除该变量。\n";
    i18n.errorJavaNotFound =
        L"XYML 需要 Java " XYML_EXPECTED_JAVA_MAJOR_VERSION_STR " 或更高版本才能运行，点击“确定”打开 Java 下载页面。\n"
        L"请在安装 Java 完成后重新启动 XYML。";
  }
  return i18n;
}
