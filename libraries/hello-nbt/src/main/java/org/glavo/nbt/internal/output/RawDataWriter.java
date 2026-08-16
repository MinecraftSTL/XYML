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
package org.glavo.nbt.internal.output;

import org.glavo.nbt.io.MinecraftEdition;

import java.io.IOException;

public final class RawDataWriter extends DataWriter {
    private static final int DEFAULT_BUFFER_SIZE = 8192;

    public final OutputTarget target;
    public final MinecraftEdition edition;

    private final OutputBuffer buffer;
    private final long targetStartPosition;

    public RawDataWriter(OutputTarget target, MinecraftEdition edition) {
        this.target = target;
        this.edition = edition;
        this.buffer = OutputBuffer.allocate(DEFAULT_BUFFER_SIZE, target.supportDirectBuffer(), edition.byteOrder());
        this.targetStartPosition = target.position();
    }

    @Override
    public MinecraftEdition getEdition() {
        return edition;
    }

    @Override
    public RawDataWriter getRawWriter() {
        return this;
    }

    @Override
    protected OutputBuffer getBuffer() {
        return buffer;
    }

    public long position() {
        return target.position() - targetStartPosition + buffer.pending();
    }

    @Override
    public void flush() throws IOException {
        flushBuffer();
        target.flush();
    }

    @Override
    public void flushBuffer() throws IOException {
        target.write(buffer);
    }

    public void skip(long bytes) throws IOException {
        if (bytes < 0L) {
            throw new IllegalArgumentException("bytes must be non-negative");
        }

        if (bytes == 0L) {
            return;
        }

        flushBuffer();
        target.skip(bytes);
    }

    @Override
    public void close() throws IOException {
        flushBuffer();
        target.close();
    }
}
