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
// Added by MinecraftSTL in 2026 for bounded tolerant XoyzNBT reads.
package space.minecraftstl.xyml.library.nbt.io;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNullByDefault;

import java.util.Objects;

/// Defensive limits applied while decoding standalone and region NBT data.
///
/// The limits apply to the encoded input, one decompressed payload, the cumulative document
/// output, logical nodes, nesting depth, string payloads, and primitive arrays. They are deliberately
/// independent so a small compressed input cannot allocate an unbounded object graph. A limit is
/// inclusive; a zero limit rejects any positive input in that category.
@NotNullByDefault
public final class ReadLimits {
    /// Conservative defaults suitable for ordinary Java Edition saves.
    private static final ReadLimits DEFAULTS = new ReadLimits(
            65L * 1024L * 1024L,
            64L * 1024L * 1024L,
            256L * 1024L * 1024L,
            1_000_000L,
            512L,
            16L * 1024L * 1024L,
            16L * 1024L * 1024L,
            16L * 1024L * 1024L);

    /// Largest encoded payload accepted by one read operation.
    private final long maxEncodedBytes;
    /// Largest decompressed output accepted from one payload.
    private final long maxDecompressedBytes;
    /// Largest cumulative decompressed output accepted for one document.
    private final long maxDocumentDecompressedBytes;
    /// Largest number of logical NBT nodes accepted for one document.
    private final long maxNodes;
    /// Deepest accepted NBT nesting level.
    private final long maxDepth;
    /// Largest encoded UTF-8 string payload accepted for one tag.
    private final long maxStringBytes;
    /// Largest element count accepted for one list or primitive array.
    private final long maxArrayLength;
    /// Largest encoded byte size accepted for one primitive array.
    private final long maxArrayBytes;

    /// Creates a bounded read policy.
    ///
    /// @param maxEncodedBytes largest encoded input
    /// @param maxDecompressedBytes largest decompressed payload
    /// @param maxNodes largest number of logical tags
    /// @param maxDepth largest nesting depth
    /// @param maxStringBytes largest encoded string payload
    /// @param maxArrayLength largest primitive-array or list element count
    public ReadLimits(long maxEncodedBytes, long maxDecompressedBytes, long maxNodes,
                         long maxDepth, long maxStringBytes, long maxArrayLength) {
        this(maxEncodedBytes, maxDecompressedBytes, maxDecompressedBytes, maxNodes, maxDepth,
                maxStringBytes, maxArrayLength, maxArrayLength);
    }

    /// Creates a bounded read policy with a separate cumulative document limit.
    ///
    /// @param maxEncodedBytes largest encoded input
    /// @param maxDecompressedBytes largest decompressed payload
    /// @param maxDocumentDecompressedBytes largest cumulative decompressed document output
    /// @param maxNodes largest number of logical tags
    /// @param maxDepth largest nesting depth
    /// @param maxStringBytes largest encoded string payload
    /// @param maxArrayLength largest primitive-array or list element count
    public ReadLimits(long maxEncodedBytes, long maxDecompressedBytes,
                         long maxDocumentDecompressedBytes, long maxNodes, long maxDepth,
                         long maxStringBytes, long maxArrayLength) {
        this(maxEncodedBytes, maxDecompressedBytes, maxDocumentDecompressedBytes, maxNodes, maxDepth,
                maxStringBytes, maxArrayLength, maxArrayLength);
    }

    /// Creates a bounded read policy with independent array count and encoded-byte limits.
    ///
    /// @param maxEncodedBytes largest encoded input
    /// @param maxDecompressedBytes largest decompressed payload
    /// @param maxDocumentDecompressedBytes largest cumulative decompressed document output
    /// @param maxNodes largest number of logical tags
    /// @param maxDepth largest nesting depth
    /// @param maxStringBytes largest encoded string payload
    /// @param maxArrayLength largest primitive-array or list element count
    /// @param maxArrayBytes largest encoded primitive-array payload
    public ReadLimits(long maxEncodedBytes, long maxDecompressedBytes,
                         long maxDocumentDecompressedBytes, long maxNodes, long maxDepth,
                         long maxStringBytes, long maxArrayLength, long maxArrayBytes) {
        this.maxEncodedBytes = nonNegative(maxEncodedBytes, "maxEncodedBytes");
        this.maxDecompressedBytes = nonNegative(maxDecompressedBytes, "maxDecompressedBytes");
        this.maxDocumentDecompressedBytes = nonNegative(maxDocumentDecompressedBytes,
                "maxDocumentDecompressedBytes");
        this.maxNodes = nonNegative(maxNodes, "maxNodes");
        this.maxDepth = nonNegative(maxDepth, "maxDepth");
        this.maxStringBytes = nonNegative(maxStringBytes, "maxStringBytes");
        this.maxArrayLength = nonNegative(maxArrayLength, "maxArrayLength");
        this.maxArrayBytes = nonNegative(maxArrayBytes, "maxArrayBytes");
    }

    /// Returns the default bounded policy.
    ///
    /// @return immutable defaults
    @Contract(pure = true)
    public static ReadLimits defaults() {
        return DEFAULTS;
    }

    /// Returns the encoded-input limit.
    public long maxEncodedBytes() {
        return maxEncodedBytes;
    }

    /// Returns the decompressed-payload limit.
    public long maxDecompressedBytes() {
        return maxDecompressedBytes;
    }

    /// Returns the cumulative decompressed-document limit.
    public long maxDocumentDecompressedBytes() {
        return maxDocumentDecompressedBytes;
    }

    /// Alias for callers that describe the bound as cumulative output.
    public long maxCumulativeDecompressedBytes() {
        return maxDocumentDecompressedBytes;
    }

    /// Returns the logical-node limit.
    public long maxNodes() {
        return maxNodes;
    }

    /// Returns the nesting-depth limit.
    public long maxDepth() {
        return maxDepth;
    }

    /// Returns the encoded string-payload limit.
    public long maxStringBytes() {
        return maxStringBytes;
    }

    /// Returns the primitive-array and list-length limit.
    public long maxArrayLength() {
        return maxArrayLength;
    }

    /// Returns the encoded primitive-array payload limit.
    public long maxArrayBytes() {
        return maxArrayBytes;
    }

    /// Returns a diagnostic representation without exposing mutable state.
    @Override
    public String toString() {
        return "ReadLimits[maxEncodedBytes=" + maxEncodedBytes
                + ", maxDecompressedBytes=" + maxDecompressedBytes
                + ", maxDocumentDecompressedBytes=" + maxDocumentDecompressedBytes
                + ", maxNodes=" + maxNodes
                + ", maxDepth=" + maxDepth
                + ", maxStringBytes=" + maxStringBytes
                + ", maxArrayLength=" + maxArrayLength
                + ", maxArrayBytes=" + maxArrayBytes + ']';
    }

    /// Compares all read bounds.
    @Override
    public boolean equals(Object object) {
        return this == object || object instanceof ReadLimits other
                && maxEncodedBytes == other.maxEncodedBytes
                && maxDecompressedBytes == other.maxDecompressedBytes
                && maxDocumentDecompressedBytes == other.maxDocumentDecompressedBytes
                && maxNodes == other.maxNodes
                && maxDepth == other.maxDepth
                && maxStringBytes == other.maxStringBytes
                && maxArrayLength == other.maxArrayLength
                && maxArrayBytes == other.maxArrayBytes;
    }

    /// Returns a hash code consistent with [#equals(Object)].
    @Override
    public int hashCode() {
        return Objects.hash(maxEncodedBytes, maxDecompressedBytes, maxDocumentDecompressedBytes,
                maxNodes, maxDepth, maxStringBytes, maxArrayLength, maxArrayBytes);
    }

    /// Creates a fresh cumulative budget for one logical document read.
    ///
    /// @return mutable budget scoped to one read operation
    Budget newDocumentBudget() {
        return new Budget(maxDocumentDecompressedBytes);
    }

    /// Creates a fresh cumulative node budget for one logical document read.
    ///
    /// @return mutable budget scoped to one read operation
    NodeBudget newNodeBudget() {
        return new NodeBudget(maxNodes);
    }

    /// Validates one inclusive non-negative limit.
    ///
    /// @param value configured limit
    /// @param name configuration field name
    /// @return validated value
    /// @throws IllegalArgumentException when the limit is negative
    private static long nonNegative(long value, String name) {
        if (value < 0L) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
        return value;
    }

    /// Mutable cumulative output budget shared by one read operation.
    @NotNullByDefault
    static final class Budget {
        /// Unreserved cumulative decompression allowance.
        private long remaining;

        /// Creates one operation-scoped cumulative budget.
        ///
        /// @param maximum initial decompression allowance
        private Budget(long maximum) {
            remaining = maximum;
        }

        /// Reserves decompressed bytes before they are materialized.
        ///
        /// @param bytes number of bytes to reserve
        /// @throws java.io.IOException when the document budget is exhausted
        void consume(long bytes) throws java.io.IOException {
            if (bytes < 0L) {
                throw new IllegalArgumentException("bytes must not be negative");
            }
            if (bytes > remaining) {
                throw new java.io.IOException("Cumulative decompressed NBT output exceeds the read limit");
            }
            remaining -= bytes;
        }

        /// Returns bytes still available to this document.
        long remaining() {
            return remaining;
        }
    }

    /// Mutable cumulative node budget shared by one read operation.
    @NotNullByDefault
    static final class NodeBudget {
        /// Unreserved logical-node allowance.
        private long remaining;

        /// Creates one operation-scoped node budget.
        ///
        /// @param maximum initial node allowance
        private NodeBudget(long maximum) {
            remaining = maximum;
        }

        /// Reserves logical nodes before a recovered tree is exposed.
        ///
        /// @param nodes number of nodes to reserve
        /// @throws java.io.IOException when the document budget is exhausted
        void consume(long nodes) throws java.io.IOException {
            if (nodes < 0L) {
                throw new IllegalArgumentException("nodes must not be negative");
            }
            if (nodes > remaining) {
                throw new java.io.IOException("Cumulative NBT node count exceeds the read limit");
            }
            remaining -= nodes;
        }

        /// Tries to reserve one node without throwing a checked exception.
        ///
        /// @return `true` when one node was reserved
        boolean tryConsumeOne() {
            if (remaining == 0L) {
                return false;
            }
            remaining--;
            return true;
        }

        /// Returns nodes still available to this document.
        ///
        /// @return remaining logical-node allowance
        long remaining() {
            return remaining;
        }

        /// Captures the current allowance before a speculative parse.
        ///
        /// @return restorable remaining-node checkpoint
        long checkpoint() {
            return remaining;
        }

        /// Restores a checkpoint after a speculative parse is discarded.
        ///
        /// @param checkpoint allowance returned by [#checkpoint()]
        void restore(long checkpoint) {
            if (checkpoint < remaining) {
                throw new IllegalArgumentException("checkpoint must not be below the current allowance");
            }
            remaining = checkpoint;
        }
    }
}
