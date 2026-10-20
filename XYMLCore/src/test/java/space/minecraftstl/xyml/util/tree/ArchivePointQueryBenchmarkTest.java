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
package space.minecraftstl.xyml.util.tree;

import kala.compress.archivers.zip.ZipArchiveReader;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/// Measures full-tree versus validated point-query cost in the same implementation and JVM.
/// This is not a claim about the pre-change binary or live launcher latency; no timing ratio is asserted.
@NotNullByDefault
public final class ArchivePointQueryBenchmarkTest {
    /// Reports median nanoseconds for typical and ten-thousand-entry nested archives.
    @Test
    void reportsPointAndTreeCosts(@TempDir Path directory) throws IOException {
        for (int count : new int[]{100, 10_000}) {
            Path file = directory.resolve("entries-" + count + ".zip");
            try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(file))) {
                for (int index = 0; index < count; index++) {
                    output.putNextEntry(new ZipEntry("folder-" + index + "/data.txt"));
                    output.write(1);
                    output.closeEntry();
                }
            }
            for (int warmup = 0; warmup < 3; warmup++) {
                measure(file, true);
                measure(file, false);
            }
            long[] eager = new long[7];
            long[] point = new long[7];
            for (int run = 0; run < eager.length; run++) {
                eager[run] = measure(file, true);
                point[run] = measure(file, false);
            }
            Arrays.sort(eager);
            Arrays.sort(point);
            System.out.println("archive-query entries=" + count + " fullTreeMedianNs=" + eager[3]
                    + " validatedPointMedianNs=" + point[3] + " java=" + System.getProperty("java.version"));
        }
    }

    /// Times only tree/query work after the reader has parsed the archive.
    private static long measure(Path file, boolean buildTree) throws IOException {
        try (ZipFileTree tree = new ZipFileTree(new ZipArchiveReader(file))) {
            long start = System.nanoTime();
            if (buildTree) tree.getRoot();
            assertNotNull(tree.getEntry("/folder-0/data.txt"));
            long elapsed = System.nanoTime() - start;
            if (!buildTree) assertFalse(tree.isTreeMaterialized());
            return elapsed;
        }
    }
}
