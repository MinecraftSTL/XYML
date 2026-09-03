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
package space.minecraftstl.xyml.game;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import space.minecraftstl.xyml.util.gson.JsonUtils;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests for library JSON parsing and serialization.
@NotNullByDefault
public final class LibraryTest {

    /// Standard library JSON preserves every supported field during a round trip.
    @Test
    public void testStandardJsonRoundTrip() {
        JsonObject source = JsonParser.parseString("""
                {
                  "name": "org.example:library:1.0.0",
                  "url": "https://example.com/maven/",
                  "downloads": {
                    "artifact": {
                      "path": "org/example/library/1.0.0/library-1.0.0.jar",
                      "url": "https://example.com/library.jar",
                      "sha1": "0123456789abcdef",
                      "size": 42
                    }
                  },
                  "checksums": ["0123456789abcdef"],
                  "extract": {"exclude": ["META-INF/"]},
                  "natives": {"linux": "natives-linux"},
                  "rules": [{"action": "allow"}],
                  "hint": "local",
                  "filename": "library.jar"
                }
                """).getAsJsonObject();

        Library library = JsonUtils.GSON.fromJson(source, Library.class);
        JsonElement serialized = JsonUtils.GSON.toJsonTree(library);

        assertEquals(source, serialized);
        assertEquals(serialized, JsonUtils.GSON.toJsonTree(library));
        assertEquals(serialized, JsonUtils.GSON.toJsonTree(JsonUtils.GSON.fromJson(serialized, Library.class)));
    }

    /// Classifier-only native artifacts are recognized even when no classifiers download map is present.
    @Test
    public void recognizesNativeArtifactClassifier() {
        assertTrue(new Library(new Artifact(
                "org.example", "native-library", "1.0.0", "natives-linux")).isNative());
        assertFalse(new Library(new Artifact(
                "org.example", "regular-library", "1.0.0", "sources")).isNative());
    }

}
