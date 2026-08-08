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
package space.minecraftstl.xyml.ai;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.Metadata;
import space.minecraftstl.xyml.game.XYMLCacheRepository;
import space.minecraftstl.xyml.game.XYMLGameRepository;
import space.minecraftstl.xyml.java.JavaManager;
import space.minecraftstl.xyml.setting.DownloadProviders;
import space.minecraftstl.xyml.setting.GameDirectoryManager;
import space.minecraftstl.xyml.setting.ProxyManager;
import space.minecraftstl.xyml.setting.SettingsManager;
import space.minecraftstl.xyml.util.CacheRepository;

import java.io.PrintStream;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicBoolean;

import static space.minecraftstl.xyml.util.logging.Logger.LOG;

/// Initializes the headless launcher runtime and serves XYML capabilities over standard I/O.
@NotNullByDefault
public final class Main {

    /// Prevents instantiation of the process entry point.
    private Main() {
    }

    /// Starts the stdio MCP server and waits until the client closes the protocol stream.
    ///
    /// This entry point never writes application messages to stdout because stdout is reserved
    /// exclusively for newline-delimited MCP JSON-RPC frames.
    ///
    /// @param args reserved command-line arguments
    public static void main(String @Unmodifiable [] args) {
        PrintStream protocolOutput = System.out;
        System.setOut(System.err);
        try {
            initializeLauncherRuntime();
            if (!SettingsManager.settings().mcpEnabledProperty().get()) {
                System.err.println("XYML MCP server is disabled in launcher settings.");
                shutdownLauncherRuntime();
                return;
            }
            XYMLGameRepository repository = GameDirectoryManager.getSelectedRepository();
            XYMLMcpServer server = new XYMLMcpServer(
                    new XYMLMcpService(repository), System.in, protocolOutput);
            AtomicBoolean shutdownStarted = new AtomicBoolean();
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                if (shutdownStarted.compareAndSet(false, true)) {
                    shutdown(server);
                }
            }, "XYML MCP shutdown"));
            try {
                server.awaitTermination();
            } finally {
                if (shutdownStarted.compareAndSet(false, true)) {
                    shutdown(server);
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (Exception exception) {
            System.err.println("Unable to start the XYML MCP server: " + exception.getMessage());
            exception.printStackTrace(System.err);
        }
    }

    /// Initializes the non-UI subset of the launcher runtime used by MCP operations.
    private static void initializeLauncherRuntime() throws Exception {
        System.getProperties().putIfAbsent("java.net.useSystemProxies", "true");
        System.getProperties().putIfAbsent("http.agent", "XYML/" + Metadata.VERSION);
        Files.createDirectories(Metadata.XYML_LOCAL_HOME);
        Files.createDirectories(Metadata.XYML_USER_HOME);
        LOG.start(Metadata.XYML_LOCAL_HOME.resolve("logs"));
        SettingsManager.init();
        DownloadProviders.init();
        ProxyManager.init();
        GameDirectoryManager.init();
        CacheRepository.setInstance(XYMLCacheRepository.REPOSITORY);
        XYMLCacheRepository.REPOSITORY.setDirectory(SettingsManager.settings().getResolvedCommonDirectory());
        JavaManager.initialize();
    }

    /// Closes the protocol server and flushes launcher settings and logs.
    private static void shutdown(XYMLMcpServer server) {
        try {
            server.close();
        } finally {
            shutdownLauncherRuntime();
        }
    }

    /// Flushes launcher settings and stops the headless launcher log service.
    private static void shutdownLauncherRuntime() {
        try {
            SettingsManager.shutdown();
        } finally {
            LOG.shutdown();
        }
    }
}
