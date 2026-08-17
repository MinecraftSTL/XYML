/*
 * HMCLauncher for Windows
 * Copyright (C) 2025 huangyuhui and contributors
 * Modified by MinecraftSTL in 2026 for XYMLL.
 * SPDX-License-Identifier: GPL-3.0-only
 * See ../README.md for the GPLv3 Section 7 additional terms.
 */
#include <windows.h>
#include <cstdlib>
#include <algorithm>
#include <format>
#include <ranges>

#include <config.h>

#include "debug.h"
#include "i18n.h"
#include "path.h"
#include "platform.h"
#include "java.h"

int APIENTRY wWinMain(HINSTANCE, HINSTANCE, LPWSTR lpCmdLine, int) {
  HLVerboseOutput = HLGetEnvVar(L"XYML_LAUNCHER_VERBOSE_OUTPUT").value_or(L"") != L"false";

  LPCWSTR javaExecutableName;
  if (HLAttachConsole()) {
    javaExecutableName = L"java.exe";
  } else {
    javaExecutableName = L"javaw.exe";
  }

  const auto arch = HLGetArchitecture();
  const bool isX64 = arch == HLArchitecture::X86_64;
  const bool isARM64 = arch == HLArchitecture::ARM64;
  const bool isX86 = arch == HLArchitecture::X86;

  const auto i18n = XYMLLI18N::Instance();
  const auto selfPath = HLGetSelfPath();
  if (!selfPath.has_value()) {
    HLDebugLog(L"Failed to get self path");
    MessageBoxW(nullptr, i18n.errorSelfPath, nullptr, MB_OK | MB_ICONERROR);
    return EXIT_FAILURE;
  }

  const HLJavaOptions options = {.workdir = selfPath.value().first,
                                 .jarPath = selfPath.value().second,
                                 .jvmOptions = HLGetEnvVar(L"XYML_JAVA_OPTS"),
                                 .appArguments = lpCmdLine == nullptr ? L"" : lpCmdLine};
  HLDebugLog(std::format(L"*** XYMLL {} (based on HMCLauncher {}) ***", XYMLL_VERSION,
                         XYMLL_UPSTREAM_VERSION));
  if (isX64) {
    HLDebugLog(L"System Architecture: x86-64");
  } else if (isARM64) {
    HLDebugLog(L"System Architecture: arm64");
  } else {
    HLDebugLog(L"System Architecture: x86");
  }

  HLDebugLog(std::format(L"Working directory: {}", options.workdir.path));
  HLDebugLog(std::format(L"Exe File: {}\\{}", options.workdir.path, options.jarPath));
  if (options.jvmOptions.has_value()) {
    HLDebugLog(std::format(L"JVM Options: {}", options.jvmOptions.value()));
  }

  // If XYML_JAVA_HOME is set, it should always be used
  {
    const auto xymlJavaHome = HLGetEnvPath(L"XYML_JAVA_HOME");
    if (xymlJavaHome.has_value() && !xymlJavaHome.value().path.empty()) {
      HLDebugLog(L"XYML_JAVA_HOME: " + xymlJavaHome.value().path);
      HLPath javaExecutablePath = xymlJavaHome.value() / L"bin" / javaExecutableName;
      if (javaExecutablePath.IsRegularFile()) {
        if (const auto exitCode = HLLaunchJVM(javaExecutablePath, options, std::nullopt); exitCode.has_value()) {
          return static_cast<int>(exitCode.value());
        }
      } else {
        HLDebugLog(std::format(L"Invalid XYML_JAVA_HOME: {}", xymlJavaHome.value().path));
      }
      MessageBoxW(nullptr, i18n.errorInvalidXYMLJavaHome, nullptr, MB_OK | MB_ICONERROR);
      return EXIT_FAILURE;
    } else {
      HLDebugLogVerbose(L"XYML_JAVA_HOME: Not Found");
    }
  }

  // Try the Java packaged together.
  {
    HLPath javaExecutablePath = options.workdir;
    if (isARM64) {
      javaExecutablePath /= L"jre-arm64";
    } else if (isX64) {
      javaExecutablePath /= L"jre-x64";
    } else {
      javaExecutablePath /= L"jre-x86";
    }
    javaExecutablePath /= L"bin";
    javaExecutablePath /= javaExecutableName;
    if (javaExecutablePath.IsRegularFile()) {
      HLDebugLog(std::format(L"Bundled JRE: {}", javaExecutablePath.path));
      if (const auto exitCode = HLLaunchJVM(javaExecutablePath, options, std::nullopt); exitCode.has_value()) {
        return static_cast<int>(exitCode.value());
      }
    } else {
      HLDebugLogVerbose(std::format(L"Bundled JRE: Not Found"));
    }
  }

  // ------ Search All Java ------

  // To make the log look better, we first print JAVA_HOME
  const auto javaHome = HLGetEnvPath(L"JAVA_HOME");
  if (javaHome.has_value() && !javaHome.value().path.empty()) {
    HLDebugLog(L"JAVA_HOME: " + javaHome.value().path);
  } else {
    HLDebugLogVerbose(L"JAVA_HOME: Not Found");
  }

  HLJavaList javaRuntimes;
  {
    HLPath xymlJavaDir = options.workdir / L".xyml\\java";
    if (isARM64) {
      xymlJavaDir /= L"windows-arm64";
    } else if (isX64) {
      xymlJavaDir /= L"windows-x86_64";
    } else {
      xymlJavaDir /= L"windows-x86";
    }
    HLSearchJavaInDir(javaRuntimes, xymlJavaDir, javaExecutableName);
  }

  if (javaHome.has_value() && !javaHome.value().path.empty()) {
    HLDebugLogVerbose(L"Checking JAVA_HOME");

    HLPath javaExecutablePath = javaHome.value() / L"bin" / javaExecutableName;
    if (javaExecutablePath.IsRegularFile()) {
      javaRuntimes.TryAdd(javaExecutablePath);
    } else {
      HLDebugLog(std::format(L"JAVA_HOME is set to {}, but the Java executable {} does not exist",
                             javaHome.value().path, javaExecutablePath.path));
    }
  }

  {
    const auto appDataPath = HLGetEnvPath(L"APPDATA");
    if (appDataPath.has_value() && !appDataPath.value().path.empty()) {
      HLPath xymlJavaDir = appDataPath.value() / L".xyml\\java";
      if (isARM64) {
        xymlJavaDir /= L"windows-arm64";
      } else if (isX64) {
        xymlJavaDir /= L"windows-x86_64";
      } else {
        xymlJavaDir /= L"windows-x86";
      }
      HLSearchJavaInDir(javaRuntimes, xymlJavaDir, javaExecutableName);
    }
  }

  // Search Java in PATH
  {
    const auto paths = HLGetEnvVar(L"PATH");
    if (paths.has_value()) {
      HLDebugLogVerbose(L"Searching in PATH");
      HLSearchJavaInPath(javaRuntimes, paths.value(), javaExecutableName);
    } else {
      HLDebugLog(L"PATH: Not Found");
    }
  }

  // Search Java in C:\Program Files
  {
    std::optional<HLPath> programFilesPath;
    if (isX64 || isARM64) {
      programFilesPath = HLGetEnvPath(L"ProgramW6432");
    } else if (isX86) {
      programFilesPath = HLGetEnvPath(L"ProgramFiles");
    } else {
      programFilesPath = std::nullopt;
    }

    if (programFilesPath.has_value() && !programFilesPath.value().path.empty()) {
      HLSearchJavaInProgramFiles(javaRuntimes, programFilesPath.value(), javaExecutableName);
    } else {
      HLDebugLog(L"Failed to obtain the path to Program Files");
    }
  }

  // Search Java in registry
  HLSearchJavaInRegistry(javaRuntimes, L"SOFTWARE\\JavaSoft\\JDK", javaExecutableName);
  HLSearchJavaInRegistry(javaRuntimes, L"SOFTWARE\\JavaSoft\\JRE", javaExecutableName);

  // Try to launch JVM

  if (javaRuntimes.runtimes.empty()) {
    HLDebugLog(L"No Java runtime found.");
  } else {
    std::stable_sort(javaRuntimes.runtimes.begin(), javaRuntimes.runtimes.end());

    if (HLVerboseOutput) {
      std::wstring message = L"Found Java runtimes:";
      for (const auto &item : javaRuntimes.runtimes) {
        message += L"\n  - ";
        message += item.executablePath.path;
        message += L", Version ";
        message += item.version.ToWString();
      }

      HLDebugLog(message);
    }

    for (const auto &item : javaRuntimes.runtimes | std::views::reverse) {
      if (const auto exitCode = HLLaunchJVM(item.executablePath, options, item.version); exitCode.has_value()) {
        return static_cast<int>(exitCode.value());
      }
    }
  }

  LPCWSTR downloadLink;
  if (isARM64) {
    downloadLink = L"https://adoptium.net/temurin/releases/?version=17&os=windows&arch=aarch64";
  } else if (isX64) {
    downloadLink = L"https://adoptium.net/temurin/releases/?version=17&os=windows&arch=x64";
  } else {
    downloadLink = L"https://adoptium.net/temurin/releases/?version=17&os=windows&arch=x86";
  }

  if (MessageBoxW(nullptr, i18n.errorJavaNotFound, nullptr, MB_ICONWARNING | MB_OKCANCEL) == IDOK) {
    ShellExecuteW(nullptr, nullptr, downloadLink, nullptr, nullptr, SW_SHOW);
  }
  return EXIT_FAILURE;
}
