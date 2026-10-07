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
import org.jetbrains.annotations.Nullable;
import space.minecraftstl.xyml.image.EncodedImage;
import space.minecraftstl.xyml.library.nbt.tag.ByteArrayTag;
import space.minecraftstl.xyml.library.nbt.tag.CompoundTag;

import java.util.Base64;
import java.util.Objects;

/// One editable Minecraft multiplayer-server entry with local presentation metadata.
@NotNullByDefault
public final class ServerCatalogItem {
    /// Maximum decoded server-icon payload accepted from servers.dat.
    private static final int MAX_ICON_BYTES = 1_048_576;

    /// Maximum base64 text length accepted before decoding.
    private static final int MAX_ICON_BASE64_LENGTH = ((MAX_ICON_BYTES + 2) / 3) * 4;

    private final String name;
    private final String address;
    private final String description;
    private final @Nullable EncodedImage icon;
    private final CompoundTag template;

    /// Creates a new entry without server metadata.
    public ServerCatalogItem(String name, String address) {
        this(name, address, "", null, new CompoundTag());
    }

    /// Creates an entry retaining optional source metadata.
    ServerCatalogItem(String name, String address, CompoundTag template) {
        this(name, address, descriptionFrom(template), iconFrom(template), template);
    }

    /// Creates one presentation-aware entry while retaining the complete source tag.
    private ServerCatalogItem(
            String name,
            String address,
            String description,
            @Nullable EncodedImage icon,
            CompoundTag template) {
        this.name = requireNonBlank(name, "name");
        this.address = requireNonBlank(address, "address");
        this.description = Objects.requireNonNull(description, "description").trim();
        this.icon = icon;
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

    /// Returns the locally stored server description, or an empty string when absent.
    public String description() {
        return description;
    }

    /// Returns the locally stored encoded server icon, or null when absent or invalid.
    public @Nullable EncodedImage icon() {
        return icon;
    }

    /// Returns a copy with edited fields while preserving all presentation metadata.
    public ServerCatalogItem withValues(String newName, String newAddress) {
        return new ServerCatalogItem(newName, newAddress, description, icon, template);
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

    private static String descriptionFrom(CompoundTag template) {
        Objects.requireNonNull(template, "template");
        String motd = template.getStringOrEmpty("motd").trim();
        if (!motd.isEmpty()) {
            return motd;
        }
        return template.getStringOrEmpty("description").trim();
    }

    private static @Nullable EncodedImage iconFrom(CompoundTag template) {
        Objects.requireNonNull(template, "template");
        try {
            if (template.get("icon") instanceof ByteArrayTag bytes) {
                if (bytes.size() <= 0 || bytes.size() > MAX_ICON_BYTES) {
                    return null;
                }
                byte[] data = new byte[bytes.size()];
                for (int index = 0; index < data.length; index++) {
                    data[index] = bytes.get(index);
                }
                return new EncodedImage(data);
            }
            String encoded = template.getStringOrEmpty("icon").trim();
            if (encoded.isEmpty() || encoded.length() > MAX_ICON_BASE64_LENGTH) {
                return null;
            }
            byte[] data = Base64.getDecoder().decode(encoded);
            return data.length > 0 && data.length <= MAX_ICON_BYTES ? new EncodedImage(data) : null;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static String requireNonBlank(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
