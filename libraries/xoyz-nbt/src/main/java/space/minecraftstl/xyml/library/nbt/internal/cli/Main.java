/*
 * Copyright 2026 Glavo
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
package space.minecraftstl.xyml.library.nbt.internal.cli;

import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.util.Optional;
import java.util.jar.Attributes;
import java.util.jar.Manifest;

/// Runs the XoyzNBT command-line interface.
@NotNullByDefault
public final class Main {
    /// Prevents construction of this command-line entry point.
    private Main() {
    }

    /// Resolves the module or manifest version for command-line output.
    ///
    /// @return the resolved version, or `Unknown` when no version metadata is available
    /// @throws IOException when the manifest cannot be read
    private static String getVersion() throws IOException {
        Module module = Main.class.getModule();
        if ("space.minecraftstl.xyml.library.nbt".equals(module.getName())) {
            Optional<String> rawVersion = module.getDescriptor().rawVersion();
            if (rawVersion.isPresent()) {
                return rawVersion.get();
            }
        }

        try (InputStream manifestStream = Main.class.getResourceAsStream("/META-INF/MANIFEST.MF")) {
            if (manifestStream != null) {
                Manifest manifest = new Manifest(manifestStream);
                Attributes attributes = manifest.getMainAttributes();
                String version = attributes.getValue("XoyzNBT-Version");
                if (version != null) {
                    return version;
                }
            }
        }

        return "Unknown";
    }

    /// Prints command-line usage information.
    ///
    /// @param out destination stream
    private static void printUsage(PrintStream out) {
        out.println("Usage: xoyz-nbt [options]");
        out.println("Options:");
        out.println("  -h, --help      Show this help message and exit");
        out.println("  -v, --version   Show version information and exit");
    }

    /// Executes the command-line interface.
    ///
    /// @param args command-line arguments
    /// @throws IOException when version metadata cannot be read
    public static void main(String[] args) throws IOException {
        if (args.length == 0) {
            printUsage(System.out);
            return;
        }

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            switch (arg) {
                case "-h", "-help", "--help", "-?" -> {
                    printUsage(System.out);
                    return;
                }
                case "-v", "-version", "--version" -> {
                    System.out.println(getVersion());
                }
                default -> System.err.println("Unknown option: " + arg);
            }
        }
    }
}
