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
import org.junit.jupiter.api.Test;
import space.minecraftstl.xyml.library.mcp.McpPromptProvider.PromptDefinition;
import space.minecraftstl.xyml.library.mcp.McpToolProvider.ToolCallResult;
import space.minecraftstl.xyml.library.mcp.McpToolProvider.ToolDefinition;
import space.minecraftstl.xyml.task.Task;
import space.minecraftstl.xyml.task.TaskResource;

import java.awt.EventQueue;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies the SDK-independent MCP contract without a configured XYML workspace.
@NotNullByDefault
public final class XYMLMcpToolRegistryTest {

    /// Ensures tool dispatch rejects the Swing event thread before submitting or constructing launcher work.
    @Test
    public void rejectsToolCallsOnAwtEventDispatchThreadBeforeSubmission() throws Exception {
        AtomicBoolean serviceCalled = new AtomicBoolean();
        AtomicBoolean taskExecuted = new AtomicBoolean();
        Task<List<Map<String, Object>>> task = Task.supplyAsync(() -> {
            taskExecuted.set(true);
            return List.<Map<String, Object>>of(Map.of("id", "unexpected"));
        }).setResources(TaskResource.configuration(pathFor("tool-awt")));
        XYMLMcpOperations operations = (XYMLMcpOperations) Proxy.newProxyInstance(
                XYMLMcpOperations.class.getClassLoader(), new Class<?>[]{XYMLMcpOperations.class},
                (proxy, method, arguments) -> {
                    serviceCalled.set(true);
                    return task;
                });
        XYMLMcpToolRegistry registry = new XYMLMcpToolRegistry(operations);
        AtomicReference<@Nullable ToolCallResult> observedResult = new AtomicReference<>();

        EventQueue.invokeAndWait(() -> observedResult.set(registry.call("list_instances", Map.of())));

        ToolCallResult result = assertInstanceOf(ToolCallResult.class, observedResult.get());
        assertTrue(result.error());
        assertFalse(serviceCalled.get());
        assertFalse(taskExecuted.get());
    }

    /// Ensures resource enumeration rejects the Swing event thread before invoking the launcher service.
    @Test
    public void rejectsResourceAccessOnAwtEventDispatchThreadBeforeSubmission() throws Exception {
        AtomicBoolean serviceCalled = new AtomicBoolean();
        XYMLMcpOperations operations = (XYMLMcpOperations) Proxy.newProxyInstance(
                XYMLMcpOperations.class.getClassLoader(), new Class<?>[]{XYMLMcpOperations.class},
                (proxy, method, arguments) -> {
                    serviceCalled.set(true);
                    return Task.completed(List.of());
                });
        XYMLMcpResourceRegistry registry = new XYMLMcpResourceRegistry(operations);
        AtomicReference<@Nullable Throwable> observedFailure = new AtomicReference<>();

        EventQueue.invokeAndWait(() -> {
            try {
                registry.resourceDefinitions();
            } catch (Throwable failure) {
                observedFailure.set(failure);
            }
        });

        assertInstanceOf(IllegalStateException.class, observedFailure.get());
        assertFalse(serviceCalled.get());
    }

    /// Ensures interrupting a Registry caller cancels an inner Task that is waiting for a shared resource.
    @Test
    public void callerInterruptionCancelsPendingTask() throws Exception {
        TaskResource resource = TaskResource.configuration(pathFor("pending-interruption"));
        CountDownLatch holderStarted = new CountDownLatch(1);
        CountDownLatch releaseHolder = new CountDownLatch(1);
        CountDownLatch holderStopped = new CountDownLatch(1);
        CountDownLatch serviceCalled = new CountDownLatch(1);
        CountDownLatch contenderStopped = new CountDownLatch(1);
        AtomicBoolean contenderExecuted = new AtomicBoolean();
        AtomicReference<@Nullable Thread> operationThread = new AtomicReference<>();
        Task<Boolean> holder = Task.supplyAsync(() -> {
            holderStarted.countDown();
            releaseHolder.await();
            return true;
        }).setResources(resource);
        holder.onDone().register(holderStopped::countDown);
        Task<List<Map<String, Object>>> contender = Task.supplyAsync(() -> {
            contenderExecuted.set(true);
            return List.<Map<String, Object>>of(Map.of("id", "unexpected"));
        }).setResources(resource);
        contender.onDone().register(contenderStopped::countDown);
        XYMLMcpOperations operations = (XYMLMcpOperations) Proxy.newProxyInstance(
                XYMLMcpOperations.class.getClassLoader(), new Class<?>[]{XYMLMcpOperations.class},
                (proxy, method, arguments) -> {
                    operationThread.set(Thread.currentThread());
                    serviceCalled.countDown();
                    return contender;
                });
        XYMLMcpToolRegistry registry = new XYMLMcpToolRegistry(operations);
        AtomicReference<@Nullable ToolCallResult> observedResult = new AtomicReference<>();
        AtomicBoolean interruptRetained = new AtomicBoolean();
        Thread caller = new Thread(() -> {
            observedResult.set(registry.call("list_instances", Map.of()));
            interruptRetained.set(Thread.currentThread().isInterrupted());
        }, "mcp-tool-registry-interruption-test");

        holder.start();
        try {
            assertTrue(holderStarted.await(5, TimeUnit.SECONDS));
            caller.start();
            assertTrue(serviceCalled.await(5, TimeUnit.SECONDS));
            awaitWaiting(operationThread);

            caller.interrupt();
            caller.join(TimeUnit.SECONDS.toMillis(5));

            assertFalse(caller.isAlive());
            ToolCallResult result = assertInstanceOf(ToolCallResult.class, observedResult.get());
            assertTrue(result.error());
            assertTrue(interruptRetained.get());
            assertTrue(contenderStopped.await(5, TimeUnit.SECONDS));
            assertFalse(contenderExecuted.get());
        } finally {
            releaseHolder.countDown();
            caller.interrupt();
            caller.join(TimeUnit.SECONDS.toMillis(5));
        }
        assertTrue(holderStopped.await(5, TimeUnit.SECONDS));
        assertFalse(contenderExecuted.get());
    }

    /// Ensures every approved tool is present exactly once.
    @Test
    public void registersCompleteToolSurface() {
        XYMLMcpToolRegistry registry = new XYMLMcpToolRegistry(null);
        List<ToolDefinition> definitions = registry.toolDefinitions();
        Set<String> names = definitions.stream().map(ToolDefinition::name)
                .collect(Collectors.toSet());
        assertEquals(24, definitions.size());
        assertEquals(24, names.size());
        assertEquals(Set.of("list_instances", "get_instance_settings", "rename_instance", "duplicate_instance",
                "delete_instance", "get_mods_directory", "analyze_crash", "list_java_runtimes", "list_local_mods",
                "set_java_version", "set_memory", "set_jvm_options", "set_window_options", "enable_mod",
                "disable_mod", "remove_mods", "launch_game", "stop_game", "get_launch_status",
                 "plan_crash_solution", "execute_crash_solution", "retry_crash_solution", "get_crash_repair_status",
                "cancel_crash_repair"), names);
        assertFalse(names.contains("get_logs"));
        assertFalse(names.contains("search_addons"));
        assertFalse(names.contains("create_instance"));
    }

    /// Ensures no tool schema exposes the removed MCP-level confirmation parameter.
    @Test
    public void omitsConfirmationFromAllToolSchemas() {
        XYMLMcpToolRegistry registry = new XYMLMcpToolRegistry(null);
        for (ToolDefinition tool : registry.toolDefinitions()) {
            Map<String, Object> schema = tool.inputSchema();
            @SuppressWarnings("unchecked")
            Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
            assertFalse(properties.containsKey("confirmed"), tool.name());
            @SuppressWarnings("unchecked")
            List<String> required = (List<String>) schema.getOrDefault("required", List.of());
            assertFalse(required.contains("confirmed"), tool.name());
        }
    }

    /// Ensures crash-repair schemas accept only the server-issued identifiers required by each stage.
    @Test
    public void restrictsCrashRepairToolSchemas() {
        XYMLMcpToolRegistry registry = new XYMLMcpToolRegistry(null);
        Map<String, Set<String>> expectedProperties = Map.of(
                "plan_crash_solution", Set.of("analysis_id", "solution_id", "candidate_id"),
                "execute_crash_solution", Set.of("plan_id", "candidate_id"),
                "retry_crash_solution", Set.of("plan_id"),
                "get_crash_repair_status", Set.of("operation_id"),
                "cancel_crash_repair", Set.of("operation_id"));

        for (Map.Entry<String, Set<String>> expected : expectedProperties.entrySet()) {
            ToolDefinition tool = registry.toolDefinitions().stream()
                    .filter(definition -> expected.getKey().equals(definition.name()))
                    .findFirst()
                    .orElseThrow();
            Map<String, Object> schema = tool.inputSchema();
            @SuppressWarnings("unchecked")
            Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
            @SuppressWarnings("unchecked")
            List<String> required = (List<String>) schema.get("required");
            assertEquals(expected.getValue(), properties.keySet(), expected.getKey());
            Set<String> expectedRequired = switch (expected.getKey()) {
                case "plan_crash_solution" -> Set.of("analysis_id", "solution_id");
                case "execute_crash_solution" -> Set.of("plan_id");
                default -> expected.getValue();
            };
            assertEquals(expectedRequired, Set.copyOf(required), expected.getKey());
            assertEquals(false, schema.get("additionalProperties"), expected.getKey());
        }
    }

    /// Ensures instance lifecycle and launch tools dispatch without a confirmation argument.
    @Test
    public void dispatchesInstanceLifecycleAndLaunchTools() {
        Map<String, List<Object>> calls = new HashMap<>();
        XYMLMcpToolRegistry registry = new XYMLMcpToolRegistry(recordingService(calls));

        assertFalse(registry.call("rename_instance", Map.of(
                "source_instance_id", "old", "destination_instance_id", "new")).error());
        assertEquals(List.of("old", "new"), calls.get("renameInstance"));

        assertFalse(registry.call("duplicate_instance", Map.of(
                "source_instance_id", "old", "destination_instance_id", "copy", "copy_saves", true)).error());
        assertEquals(List.of("old", "copy", true), calls.get("duplicateInstance"));

        assertFalse(registry.call("delete_instance", Map.of("instance_id", "old")).error());
        assertEquals(List.of("old"), calls.get("deleteInstance"));

        assertFalse(registry.call("remove_mods", Map.of(
                "instance_id", "old", "paths", List.of("example.jar"))).error());
        assertEquals(List.of("old", List.of("example.jar")), calls.get("removeMods"));

        assertFalse(registry.call("launch_game", Map.of("instance_id", "old")).error());
        assertEquals(List.of("old"), calls.get("launchGame"));
        assertFalse(registry.call("stop_game", Map.of("instance_id", "old")).error());
        assertEquals(List.of("old"), calls.get("stopGame"));
        assertFalse(registry.call("get_launch_status", Map.of("instance_id", "old")).error());
        assertEquals(List.of("old"), calls.get("getLaunchStatus"));
    }

    /// Ensures crash-repair tools pass only server-issued identifiers to the launcher service.
    @Test
    public void dispatchesCrashRepairTools() {
        Map<String, List<Object>> calls = new HashMap<>();
        XYMLMcpToolRegistry registry = new XYMLMcpToolRegistry(recordingService(calls));

        assertFalse(registry.call("plan_crash_solution", Map.of(
                "analysis_id", "analysis-1", "solution_id", "solution-2")).error());
        assertEquals(java.util.Arrays.asList("analysis-1", "solution-2", null), calls.get("planCrashSolution"));

        assertFalse(registry.call("execute_crash_solution", Map.of("plan_id", "plan-3")).error());
        assertEquals(java.util.Arrays.asList("plan-3", null), calls.get("executeCrashSolution"));

        assertFalse(registry.call("retry_crash_solution", Map.of("plan_id", "plan-3")).error());
        assertEquals(List.of("plan-3"), calls.get("retryCrashSolution"));

        assertFalse(registry.call("get_crash_repair_status", Map.of("operation_id", "operation-4")).error());
        assertEquals(List.of("operation-4"), calls.get("getCrashRepairStatus"));

        assertFalse(registry.call("cancel_crash_repair", Map.of("operation_id", "operation-4")).error());
        assertEquals(List.of("operation-4"), calls.get("cancelCrashRepair"));
    }

    /// Ensures each instance setting tool passes its inherit flag to the launcher service.
    @Test
    public void passesInheritSettingArguments() {
        Map<String, List<Object>> calls = new HashMap<>();
        XYMLMcpToolRegistry registry = new XYMLMcpToolRegistry(recordingService(calls));

        assertFalse(registry.call("set_java_version", Map.of(
                "instance_id", "demo", "java_version", "21", "java_path", "java.exe", "inherit", true)).error());
        assertEquals(List.of("demo", "21", "java.exe", true), calls.get("setJavaVersion"));

        assertFalse(registry.call("set_memory", Map.of(
                "instance_id", "demo", "min_memory_mb", 512, "max_memory_mb", 4096, "inherit", true)).error());
        assertEquals(List.of("demo", 512, 4096, true), calls.get("setMemory"));

        assertFalse(registry.call("set_jvm_options", Map.of(
                "instance_id", "demo", "options", "-Xmx4G", "inherit", true)).error());
        assertEquals(List.of("demo", "-Xmx4G", true), calls.get("setJvmOptions"));

        assertFalse(registry.call("set_window_options", Map.of(
                "instance_id", "demo", "width", 1280, "height", 720, "fullscreen", false, "inherit", true)).error());
        assertEquals(List.of("demo", 1280, 720, false, true), calls.get("setWindowOptions"));
    }

    /// Ensures numeric JSON arguments are not silently truncated before reaching launcher settings.
    @Test
    public void rejectsFractionalIntegerArguments() {
        Map<String, List<Object>> calls = new HashMap<>();
        XYMLMcpToolRegistry registry = new XYMLMcpToolRegistry(recordingService(calls));

        assertTrue(registry.call("set_memory", Map.of(
                "instance_id", "demo", "max_memory_mb", 1024.5)).error());
        assertFalse(calls.containsKey("setMemory"));
    }

    /// Waits until the background operation is blocked in the Task bridge.
    ///
    /// @param operationThread background operation thread reference
    private static void awaitWaiting(AtomicReference<@Nullable Thread> operationThread) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            @Nullable Thread thread = operationThread.get();
            if (thread != null && (thread.getState() == Thread.State.WAITING
                    || thread.getState() == Thread.State.TIMED_WAITING)) {
                return;
            }
            Thread.onSpinWait();
        }
        throw new AssertionError("MCP operation did not enter a waiting state");
    }

    /// Returns a unique normalized path for one registry resource scenario.
    ///
    /// @param scenario scenario identifier
    /// @return path used only as a semantic resource key
    private static Path pathFor(String scenario) {
        return Path.of("build", "mcp-tool-registry", scenario);
    }

    /// Creates a proxy service that records operation arguments and returns typed placeholder results.
    ///
    /// @param calls operation argument records
    /// @return recording service proxy
    private static XYMLMcpOperations recordingService(Map<String, List<Object>> calls) {
        return (XYMLMcpOperations) Proxy.newProxyInstance(
                XYMLMcpOperations.class.getClassLoader(), new Class<?>[]{XYMLMcpOperations.class},
                (proxy, method, arguments) -> {
                    calls.put(method.getName(), arguments == null
                            ? List.of() : new ArrayList<>(Arrays.asList(arguments)));
                    return switch (method.getName()) {
                        case "renameInstance", "duplicateInstance", "deleteInstance", "removeMods", "setJavaVersion",
                                "setMemory", "setJvmOptions", "setWindowOptions", "stopGame", "launchGame" ->
                                Task.completed(Map.of("operation", method.getName()));
                        case "getLaunchStatus",
                                "planCrashSolution", "executeCrashSolution", "retryCrashSolution", "getCrashRepairStatus",
                                "cancelCrashRepair" -> Map.of("operation", method.getName());
                        default -> throw new UnsupportedOperationException(method.getName());
                    };
                });
    }

    /// Ensures the resource and prompt registries expose protocol-neutral definitions without a repository.
    @Test
    public void registersResourceAndPromptInterfaces() throws Exception {
        XYMLMcpOperations operations = (XYMLMcpOperations) Proxy.newProxyInstance(
                XYMLMcpOperations.class.getClassLoader(), new Class<?>[]{XYMLMcpOperations.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "listInstances" -> Task.completed(List.of(Map.of("id", "demo")));
                    case "readResource" -> Task.completed(
                            Map.of("uri", "xyml://demo", "mime_type", "text/plain", "text", "ok"));
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        XYMLMcpResourceRegistry resources = new XYMLMcpResourceRegistry(operations);
        assertEquals(3, resources.resourceTemplateDefinitions().size());
        assertEquals(2, resources.resourceDefinitions().size());
        assertEquals("ok", resources.readResource("xyml://demo").text());

        XYMLMcpPromptRegistry prompts = new XYMLMcpPromptRegistry();
        assertEquals(1, prompts.promptDefinitions().size());
        PromptDefinition definition = prompts.promptDefinitions().get(0);
        assertEquals("diagnose_crash", definition.name());
        assertEquals("检查实例崩溃及其报告。", definition.description());
        assertEquals("instance_id", definition.arguments().get(0).name());
        assertEquals("实例标识符", definition.arguments().get(0).description());
        Map<String, Object> result = prompts.getPrompt("diagnose_crash", Map.of("instance_id", "demo"));
        assertTrue(result.containsKey("messages"));
        assertEquals("实例崩溃诊断", result.get("description"));
        String messages = String.valueOf(result.get("messages"));
        assertTrue(messages.contains("实例“demo”"));
        assertTrue(messages.contains("崩溃报告目录资源"));
        assertTrue(messages.contains("crash_report_path"));
        assertTrue(messages.contains("plan_crash_solution"));
        assertTrue(messages.contains("executable=true"));
        assertTrue(messages.contains("execute_crash_solution"));
        assertTrue(messages.contains("get_crash_repair_status"));
        assertTrue(messages.contains("log_text"));
        assertTrue(messages.contains("不得据此规划或执行修复"));
    }
}
