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
package space.minecraftstl.xyml.auth.authlibinjector;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.util.Optional;

import static space.minecraftstl.xyml.util.io.NetworkUtils.decodeURL;

/// Parses authlib-injector launcher integration URLs independently of a presentation toolkit.
///
/// @see <a href="https://github.com/yushijinhun/authlib-injector/wiki/%E5%90%AF%E5%8A%A8%E5%99%A8%E6%8A%80%E6%9C%AF%E8%A7%84%E8%8C%83#dnd-%E6%96%B9%E5%BC%8F%E6%B7%BB%E5%8A%A0-yggdrasil-%E6%9C%8D%E5%8A%A1%E7%AB%AF">Launcher Technical Specification for Authlib-Injector</a>
@NotNullByDefault
public final class AuthlibInjectorUrl {
    /// URL scheme reserved by the authlib-injector launcher integration.
    private static final String SCHEME = "authlib-injector";

    /// Integration path identifying a Yggdrasil server registration request.
    private static final String PATH_YGGDRASIL_SERVER = "yggdrasil-server";

    /// Prevents instantiation of this parser utility.
    private AuthlibInjectorUrl() {
    }

    /// Extracts and decodes a Yggdrasil server URL from launcher integration text.
    ///
    /// @param text transferred text, or null when a clipboard has no string representation
    /// @return decoded server URL when the text has the supported scheme and path
    public static Optional<String> parse(@Nullable String text) {
        if (text == null) {
            return Optional.empty();
        }

        String @Unmodifiable [] elements = text.split(":", 3);
        if (elements.length == 3
                && SCHEME.equals(elements[0])
                && PATH_YGGDRASIL_SERVER.equals(elements[1])) {
            return Optional.of(decodeURL(elements[2]));
        }
        return Optional.empty();
    }
}
