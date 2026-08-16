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
package org.glavo.nbt.internal.cli;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.util.Optional;
import java.util.jar.Attributes;
import java.util.jar.Manifest;

public final class Main {

    private static String getVersion() throws IOException {
        Module module = Main.class.getModule();
        if (module.getName().equals("org.glavo.nbt")) {
            Optional<String> rawVersion = module.getDescriptor().rawVersion();
            if (rawVersion.isPresent()) {
                return rawVersion.get();
            }
        }

        try (InputStream manifestStream = Main.class.getResourceAsStream("/META-INF/MANIFEST.MF")) {
            if (manifestStream != null) {
                Manifest manifest = new Manifest(manifestStream);
                Attributes attributes = manifest.getMainAttributes();
                String version = attributes.getValue("HelloNBT-Version");
                if (version != null) {
                    return version;
                }
            }
        }

        return "Unknown";
    }

    private static void printUsage(PrintStream out) {
        out.println("Usage: hello-nbt [options]");
        out.println("Options:");
        out.println("  -h, --help      Show this help message and exit");
        out.println("  -v, --version   Show version information and exit");
    }

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
