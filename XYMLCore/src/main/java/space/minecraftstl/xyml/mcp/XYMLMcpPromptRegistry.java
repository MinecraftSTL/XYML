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
import space.minecraftstl.xyml.library.mcp.McpPromptProvider;
import space.minecraftstl.xyml.library.mcp.McpPromptProvider.PromptArgument;
import space.minecraftstl.xyml.library.mcp.McpPromptProvider.PromptDefinition;

import java.util.List;
import java.util.Map;

/// Registers the small set of launcher prompt templates exposed by the MCP endpoint.
@NotNullByDefault
public final class XYMLMcpPromptRegistry implements McpPromptProvider {

    /// Prompt definitions in stable declaration order.
    private static final @Unmodifiable List<PromptDefinition> PROMPTS = List.of(
            new PromptDefinition("diagnose_crash", "检查实例崩溃及其报告。",
                    List.of(new PromptArgument("instance_id", "实例标识符", true))));

    /// Returns every prompt definition exposed by XYML.
    ///
    /// @return immutable prompt definitions
    @Override
    public @Unmodifiable List<PromptDefinition> promptDefinitions() {
        return PROMPTS;
    }

    /// Expands a named prompt with its arguments.
    ///
    /// @param name prompt name
    /// @param arguments prompt arguments
    /// @return immutable MCP prompt result
    @Override
    public @Unmodifiable Map<String, Object> getPrompt(
            String name,
            @Unmodifiable Map<String, @Nullable Object> arguments) {
        if (!"diagnose_crash".equals(name)) {
            throw new IllegalArgumentException("Unknown prompt: " + name);
        }
        @Nullable Object rawInstanceId = arguments.get("instance_id");
        if (!(rawInstanceId instanceof String instanceId) || instanceId.isBlank()) {
            throw new IllegalArgumentException("instance_id must be a non-blank string");
        }
        String text = "请先读取实例“" + instanceId + "”的最新日志资源和崩溃报告目录资源。"
                + "如目录中存在报告，请将直接文件名作为 crash_report_path 调用 analyze_crash；"
                + "先总结崩溃原因，再对用户选择的 solution_id 调用 plan_crash_solution。"
                + "只有计划明确返回 executable=true 时，才可调用 execute_crash_solution；"
                + "执行后使用 get_crash_repair_status 轮询至终态。"
                + "通过 log_text 提供的外部日志只能用于分析，不得据此规划或执行修复。";
        return Map.of(
                "description", "实例崩溃诊断",
                "messages", List.of(Map.of(
                        "role", "user",
                        "content", Map.of("type", "text", "text", text))));
    }

}
