/*
 * Copyright 2024 Glavo
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
// Modified by MinecraftSTL in 2026 for the XYML namespace and monorepo build.
package space.minecraftstl.xyml.library.mesa;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.channels.FileLock;
import java.util.Locale;
import java.util.Properties;

/// Extracts and activates one bundled Mesa driver for Windows.
///
/// @author Glavo
@NotNullByDefault
public final class Loader {
    /// Prevents construction of the static javaagent entry-point class.
    private Loader() {
    }

    /// Selects, extracts, and activates the requested Mesa driver before application startup.
    ///
    /// @param requestedDriver optional driver name; defaults to `llvmpipe`
    public static void premain(@Nullable String requestedDriver) {
        if (!System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")) {
            System.err.println("[mesa-loader] unsupported operating system: " + System.getProperty("os.name"));
            return;
        }

        String arch;
        switch (System.getProperty("os.arch").toLowerCase(Locale.ROOT)) {
            case "x8664":
            case "x86-64":
            case "x86_64":
            case "amd64":
            case "ia32e":
            case "em64t":
            case "x64":
                arch = "x64";
                break;
            case "x8632":
            case "x86-32":
            case "x86_32":
            case "x86":
            case "i86pc":
            case "i386":
            case "i486":
            case "i586":
            case "i686":
            case "ia32":
            case "x32":
                arch = "x86";
                break;
            case "aarch64":
            case "arm64":
            case "armv9":
            case "armv8":
                arch = "arm64";
                break;
            default:
                System.err.println("[mesa-loader] Unsupported architecture: " + System.getProperty("os.arch"));
                return;
        }

        String driverName = requestedDriver == null || requestedDriver.isEmpty()
                ? "llvmpipe"
                : requestedDriver.toLowerCase(Locale.ROOT);

        @Nullable String icdName;
        switch (driverName) {
            case "lavapipe":
                icdName = "lvp";
                break;
            case "dzn":
                icdName = "dzn";
                break;
            case "llvmpipe":
            case "zink":
            case "d3d12":
                icdName = null;
                break;
            default:
                System.err.println("[mesa-loader] Unsupported driver: " + driverName);
                return;
        }

        boolean vulkan = icdName != null;
        String @Unmodifiable [] files;
        if (vulkan) {
            files = new String[]{
                    icdName + "_icd.json",
                    "vulkan_" + icdName + ".dll"
            };
        } else {
            files = new String[]{"opengl32.dll"};
        }

        Properties properties = new Properties();
        @Nullable InputStream versionInput = Loader.class.getResourceAsStream("version.properties");
        if (versionInput == null) {
            System.err.println("[mesa-loader] Missing version.properties");
            return;
        }
        try (Reader reader = new InputStreamReader(versionInput, "UTF-8")) {
            properties.load(reader);
        } catch (IOException e) {
            System.err.println("[mesa-loader] Failed to read version.properties");
            e.printStackTrace(System.err);
            return;
        }

        @Nullable String loaderVersion = properties.getProperty("loader.version");
        if (loaderVersion == null) {
            System.err.println("[mesa-loader] Missing loader version property in version.properties");
            return;
        }
        @Nullable String mesaVersion = properties.getProperty("mesa.version");
        if (mesaVersion == null) {
            System.err.println("[mesa-loader] Missing Mesa version property in version.properties");
            return;
        }

        @Nullable String nativeDir = System.getProperty("org.glavo.mesa.loader.nativeDir");

        System.out.println("[mesa-loader] Mesa Driver: " + driverName);
        System.out.println("[mesa-loader] Mesa Version: " + mesaVersion);

        File targetDir;
        if (nativeDir == null) {
            targetDir = new File(System.getProperty("java.io.tmpdir"),
                    String.format("mesa-loader/%s/%s/%s", loaderVersion, arch, driverName)).getAbsoluteFile();
        } else {
            targetDir = new File(nativeDir);
        }

        System.out.println("[mesa-loader] Native Directory: " + targetDir);

        if (!targetDir.isDirectory() && !targetDir.mkdirs()) {
            System.err.println("[mesa-loader] Failed to create native directory: " + targetDir);
            return;
        }

        File lockFile = new File(targetDir, "lock");

        try (FileOutputStream lockFileStream = new FileOutputStream(lockFile)) {
            @Nullable FileLock lock = lockFileStream.getChannel().tryLock();

            if (lock == null) {
                for (int retry = 0; retry < 20 && lock == null; retry++) {
                    System.out.println("[mesa-loader] Waiting for the file lock");

                    try {
                        Thread.sleep(3 * 1000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        System.out.println("[mesa-loader] Interrupted while waiting for lock");
                        return;
                    }

                    lock = lockFileStream.getChannel().tryLock();
                }
            }

            if (lock == null) {
                System.err.println("[mesa-loader] Could not get file lock");
                return;
            }

            byte[] buffer = new byte[8192];
            for (String file : files) {
                try (@Nullable InputStream input = Loader.class.getResourceAsStream(
                        arch + "/" + driverName + "/" + file)) {
                    if (input == null) {
                        System.err.println("[mesa-loader] " + file + " not exists");
                        return;
                    }

                    File targetFile = new File(targetDir, file);

                    if (targetFile.exists() && targetFile.length() == input.available()) {
                        System.out.println("[mesa-loader] Skip " + file + " in " + targetDir);
                    } else {
                        System.out.println("[mesa-loader] Extract " + file + " to " + targetDir);
                        try (FileOutputStream out = new FileOutputStream(targetFile)) {
                            int n;
                            while ((n = input.read(buffer)) > 0) {
                                out.write(buffer, 0, n);
                            }
                        }
                    }

                    if (!vulkan) {
                        String dllPath = targetFile.getAbsolutePath();
                        System.out.println("[mesa-loader] Loading " + dllPath);
                        System.load(dllPath);
                    }
                } catch (IOException e) {
                    System.err.println("[mesa-loader] Failed to extract " + file);
                    e.printStackTrace(System.err);
                } catch (UnsatisfiedLinkError e) {
                    System.err.println("[mesa-loader] Failed to load " + file);
                    e.printStackTrace(System.err);
                }
            }
        } catch (IOException e) {
            System.err.println("[mesa-loader] Failed to get file lock");
            e.printStackTrace(System.err);
        }
    }
}
