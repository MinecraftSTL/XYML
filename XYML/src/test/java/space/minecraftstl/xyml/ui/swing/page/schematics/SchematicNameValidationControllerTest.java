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
package space.minecraftstl.xyml.ui.swing.page.schematics;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.SwingUtilities;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/// Verifies bounded validation demand, revision isolation and actual filesystem checks.
@NotNullByDefault
public final class SchematicNameValidationControllerTest {
    /// Only the first and latest name are submitted; closed controllers ignore late completions.
    @Test
    void coalescesAndIgnoresStaleCompletions() throws Exception {
        List<String> requested = new ArrayList<>();
        List<CompletableFuture<@Nullable String>> responses = new ArrayList<>();
        AtomicReference<Boolean> valid = new AtomicReference<>(false);
        AtomicReference<SchematicNameValidationController> controller = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            controller.set(new SchematicNameValidationController(name -> {
                requested.add(name);
                CompletableFuture<@Nullable String> response = new CompletableFuture<>();
                responses.add(response);
                return response;
            }, (accepted, error) -> valid.set(accepted)));
            controller.get().request("first");
            controller.get().request("obsolete");
            controller.get().request("latest");
            assertEquals(List.of("first"), requested);
            responses.get(0).complete(null);
            assertEquals(List.of("first", "latest"), requested);
            assertFalse(valid.get());
            controller.get().close();
            responses.get(1).complete(null);
            assertFalse(valid.get());
        });
    }

    /// Empty, illegal and already existing names are distinct from a valid absent child.
    @Test
    void validatesActualDirectory(@TempDir Path directory) throws Exception {
        Files.createDirectory(directory.resolve("existing"));
        assertNotNull(SchematicDirectoryNameValidation.validate(directory, "").toCompletableFuture().get(5, TimeUnit.SECONDS));
        assertNotNull(SchematicDirectoryNameValidation.validate(directory, "../escape").toCompletableFuture().get(5, TimeUnit.SECONDS));
        assertNotNull(SchematicDirectoryNameValidation.validate(directory, "existing").toCompletableFuture().get(5, TimeUnit.SECONDS));
        assertNull(SchematicDirectoryNameValidation.validate(directory, "new-child").toCompletableFuture().get(5, TimeUnit.SECONDS));
        assertFalse(Files.exists(directory.resolve("new-child")));
    }
}
