/*
 * Copyright 2026 MinecraftSTL
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
// Added by MinecraftSTL in 2026 for the XYML namespace and monorepo build.
package space.minecraftstl.xyml.library.nbt;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import space.minecraftstl.xyml.library.nbt.io.NBTCodec;
import space.minecraftstl.xyml.library.nbt.tag.CompoundTag;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleFinder;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies the public identity and basic serialization contract of the namespaced HelloNBT artifact.
@NotNullByDefault
public final class LibraryIdentityTest {
    /// Verifies the renamed module and public package exposed to downstream consumers.
    @Test
    void moduleAndPublicPackageUseXymlNamespace() {
        Path artifact = Path.of(System.getProperty("xyml.helloNbt.jar"));
        ModuleDescriptor descriptor = ModuleFinder.of(artifact).findAll().iterator().next().descriptor();
        assertEquals("space.minecraftstl.xyml.library.nbt", descriptor.name());
        assertTrue(descriptor.exports().stream().anyMatch(export ->
                export.source().equals("space.minecraftstl.xyml.library.nbt")));
        assertEquals("space.minecraftstl.xyml.library.nbt", NBTElement.class.getPackageName());
    }

    /// Verifies that a representative compound tag survives binary NBT serialization.
    ///
    /// @throws IOException when the in-memory codec unexpectedly fails
    @Test
    void compoundTagRoundTripsThroughBinaryCodec() throws IOException {
        CompoundTag original = new CompoundTag()
                .addString("name", "XYML")
                .addInt("format", 1);
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        NBTCodec.of().writeTag(output, original);
        CompoundTag decoded = NBTCodec.of().readTag(output.toByteArray(), CompoundTag.class);

        assertEquals(original, decoded);
    }
}
