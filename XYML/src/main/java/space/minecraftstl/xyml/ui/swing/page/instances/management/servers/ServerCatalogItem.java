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
package space.minecraftstl.xyml.ui.swing.page.instances.management.servers;

import org.jetbrains.annotations.NotNullByDefault;
import space.minecraftstl.xyml.library.nbt.tag.CompoundTag;

import java.util.Objects;

/// One editable Minecraft multiplayer-server entry.
@NotNullByDefault
public final class ServerCatalogItem {
    private final String name;
    private final String address;
    private final CompoundTag template;

    /// Creates a new entry.
    public ServerCatalogItem(String name, String address) {
        this(name, address, new CompoundTag());
    }

    /// Creates an entry retaining optional source metadata.
    ServerCatalogItem(String name, String address, CompoundTag template) {
        this.name = requireNonBlank(name, "name");
        this.address = requireNonBlank(address, "address");
        this.template = Objects.requireNonNull(template, "template").clone();
    }

    /// Returns the display name.
    public String name() {
        return name;
    }

    /// Returns the server address.
    public String address() {
        return address;
    }

    /// Returns a copy with edited fields.
    public ServerCatalogItem withValues(String newName, String newAddress) {
        return new ServerCatalogItem(newName, newAddress, template);
    }

    /// Returns a detached NBT tag preserving optional fields.
    CompoundTag toTag() {
        CompoundTag result = template.clone();
        result.removeTag("name");
        result.removeTag("ip");
        result.addString("name", name);
        result.addString("ip", address);
        return result;
    }

    private static String requireNonBlank(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
