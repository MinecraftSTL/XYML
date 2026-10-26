// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

// Modified by MinecraftSTL in 2026 for the XYML namespace and monorepo build.
package space.minecraftstl.xyml.library.nbt.internal.output;

import space.minecraftstl.xyml.library.nbt.io.MinecraftEdition;

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
