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
package space.minecraftstl.xyml.library.mesa;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

/// Verifies the public identity and unsupported-driver boundary of the namespaced Mesa loader.
@NotNullByDefault
public final class MesaLoaderIdentityTest {
    /// Verifies that downstream users see only the XYML package.
    @Test
    public void loaderUsesXymlNamespace() {
        assertEquals("space.minecraftstl.xyml.library.mesa", Loader.class.getPackage().getName());
    }

    /// Verifies that an unsupported driver returns without attempting native extraction or loading.
    @Test
    public void unsupportedDriverDoesNotTouchNativeRuntime() {
        assertDoesNotThrow(() -> Loader.premain("xyml-unsupported-driver"));
    }
}
