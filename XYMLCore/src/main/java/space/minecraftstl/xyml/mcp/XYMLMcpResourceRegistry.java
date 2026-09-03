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
package space.minecraftstl.xyml.mcp;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.library.mcp.McpResourceProvider;
import space.minecraftstl.xyml.library.mcp.McpResourceProvider.ResourceDefinition;
import space.minecraftstl.xyml.library.mcp.McpResourceProvider.ResourceReadResult;
import space.minecraftstl.xyml.library.mcp.McpResourceProvider.ResourceTemplateDefinition;
import space.minecraftstl.xyml.task.Schedulers;

import java.awt.EventQueue;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

/// Registers and reads the launcher data exposed as MCP resources.
///
/// Logs and crash reports are addressed by stable `xyml://` URIs.  Files are read by the
/// application-side operation implementation, which keeps filesystem ownership outside Core.
@NotNullByDefault
public final class XYMLMcpResourceRegistry implements McpResourceProvider {

    /// URI template for the latest log of an instance.
    private static final String LOG_URI_TEMPLATE = "xyml://instances/{instance_id}/logs/latest.log";

    /// URI template for an instance crash-report directory listing.
    private static final String CRASH_DIRECTORY_URI_TEMPLATE = "xyml://instances/{instance_id}/crash-reports/";

    /// URI template for an individual crash report file.
    private static final String CRASH_REPORT_URI_TEMPLATE =
            "xyml://instances/{instance_id}/crash-reports/{report_name}";

    /// Operation implementation, or null when only protocol schemas are being inspected.
    private final @Nullable XYMLMcpOperations service;

    /// Creates a resource registry.
    ///
    /// @param service launcher operation implementation, or null for schema-only use
    public XYMLMcpResourceRegistry(@Nullable XYMLMcpOperations service) {
        this.service = service;
    }

    /// Lists concrete resources belonging to the currently installed instances.
    ///
    /// The list contains the latest log and crash-report directory for each instance. Individual
    /// report files are discoverable through the crash-report URI template.
    ///
    /// @return immutable resource definitions
    /// @throws Exception when the launcher cannot enumerate instances
    @Override
    public @Unmodifiable List<ResourceDefinition> resourceDefinitions() throws Exception {
        @Nullable XYMLMcpOperations configuredService = service;
        if (configuredService == null) {
            return List.of();
        }
        return callOnIo(() -> {
            List<ResourceDefinition> result = new ArrayList<>();
            for (Map<String, Object> instance : McpTaskExecution.execute(configuredService.listInstances())) {
                @Nullable Object rawId = instance.get("id");
                if (rawId instanceof String instanceId && !instanceId.isBlank()) {
                    String encodedId = encodePathSegment(instanceId);
                    result.add(new ResourceDefinition(
                            "xyml://instances/" + encodedId + "/logs/latest.log",
                            "latest-log-" + instanceId,
                            "Latest game log for instance " + instanceId,
                            "text/plain"));
                    result.add(new ResourceDefinition(
                            "xyml://instances/" + encodedId + "/crash-reports/",
                            "crash-reports-" + instanceId,
                            "Crash-report directory for instance " + instanceId,
                            "text/plain"));
                }
            }
            return List.copyOf(result);
        });
    }

    /// Lists templates for parameterized log and crash-report resources.
    ///
    /// @return immutable resource template definitions
    @Override
    public @Unmodifiable List<ResourceTemplateDefinition> resourceTemplateDefinitions() {
        return List.of(
                new ResourceTemplateDefinition(LOG_URI_TEMPLATE, "latest_log",
                        "Latest game log for an XYML instance", "text/plain"),
                new ResourceTemplateDefinition(CRASH_DIRECTORY_URI_TEMPLATE, "crash_reports",
                        "Crash-report directory for an XYML instance", "text/plain"),
                new ResourceTemplateDefinition(CRASH_REPORT_URI_TEMPLATE, "crash_report",
                        "One crash report for an XYML instance", "text/plain"));
    }

    /// Reads one resource through the launcher operation boundary.
    ///
    /// @param uri resource URI
    /// @return immutable resource contents
    /// @throws Exception when the URI is unsupported or its contents cannot be read
    @Override
    public ResourceReadResult readResource(String uri) throws Exception {
        XYMLMcpOperations configuredService = Objects.requireNonNull(service,
                "This registry has no launcher service");
        Map<String, String> result = callOnIo(
                () -> McpTaskExecution.execute(configuredService.readResource(uri)));
        return new ResourceReadResult(
                Objects.requireNonNull(result.get("uri"), "resource uri"),
                Objects.requireNonNull(result.get("mime_type"), "resource mime type"),
                Objects.requireNonNull(result.get("text"), "resource text"));
    }

    /// Encodes an instance identifier for use as one URI path segment.
    ///
    /// @param value raw path segment
    /// @return percent-encoded path segment
    private static String encodePathSegment(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8)
                .replace("+", "%20");
    }

    /// Executes launcher work on the shared XYML I/O scheduler and propagates caller cancellation to that work.
    ///
    /// @param operation operation to execute
    /// @param <T> result type
    /// @return operation result
    /// @throws Exception when the operation fails or is interrupted
    private static <T> T callOnIo(Callable<T> operation) throws Exception {
        if (EventQueue.isDispatchThread()) {
            throw new IllegalStateException("MCP resource access cannot block the AWT event dispatch thread");
        }
        Future<T> future = Schedulers.io().submit(operation);
        try {
            return future.get();
        } catch (InterruptedException exception) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw exception;
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof Exception operationException) {
                throw operationException;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException("MCP resource read failed", cause);
        }
    }

}
