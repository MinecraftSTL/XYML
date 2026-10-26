// SPDX-FileCopyrightText: Copyright (C) 2025 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.java;

import com.google.gson.annotations.SerializedName;
import space.minecraftstl.xyml.util.gson.JsonSerializable;
import space.minecraftstl.xyml.util.gson.JsonUtils;
import space.minecraftstl.xyml.util.io.JarUtils;
import space.minecraftstl.xyml.util.platform.Architecture;
import space.minecraftstl.xyml.util.platform.OperatingSystem;
import space.minecraftstl.xyml.util.platform.Platform;
import space.minecraftstl.xyml.util.platform.SystemUtils;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.Path;

/**
 * @author Glavo
 * @see <a href="https://github.com/Glavo/java-info">Glavo/java-info</a>
 */
public final class JavaInfoUtils {

    private JavaInfoUtils() {
    }

    public static @NotNull JavaInfo fromExecutable(Path executable) throws IOException {
        assert executable.isAbsolute();

        Path thisPath = JarUtils.thisJarPath();
        if (thisPath == null) {
            throw new IOException("Failed to find current XYML location");
        }

        try {
            Result result = JsonUtils.GSON.fromJson(SystemUtils.run(
                    executable.toString(),
                    "-classpath",
                    thisPath.toString(),
                    org.glavo.info.Main.class.getName()
            ), Result.class);

            if (result == null) {
                throw new IOException("Failed to get Java info from " + executable);
            }

            if (result.javaVersion == null) {
                throw new IOException("Failed to get Java version from " + executable);
            }

            Architecture architecture = Architecture.parseArchName(result.osArch);
            Platform platform = Platform.getPlatform(OperatingSystem.CURRENT_OS,
                    architecture != Architecture.UNKNOWN
                            ? architecture
                            : Architecture.SYSTEM_ARCH);

            return new JavaInfo(platform, result.javaVersion, result.javaVendor);
        } catch (IOException e) {
            throw e;
        } catch (Throwable e) {
            throw new IOException(e);
        }
    }

    @JsonSerializable
    private record Result(@SerializedName("os.name") String osName, @SerializedName("os.arch") String osArch,
                          @SerializedName("java.version") String javaVersion,
                          @SerializedName("java.vendor") String javaVendor) {
    }
}
