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
// Added by MinecraftSTL in 2026 for safe generic NBT type conversion.
package space.minecraftstl.xyml.library.nbt.edit;

import space.minecraftstl.xyml.library.nbt.tag.ByteArrayTag;
import space.minecraftstl.xyml.library.nbt.tag.ByteTag;
import space.minecraftstl.xyml.library.nbt.tag.CompoundTag;
import space.minecraftstl.xyml.library.nbt.tag.DoubleTag;
import space.minecraftstl.xyml.library.nbt.tag.FloatTag;
import space.minecraftstl.xyml.library.nbt.tag.IntArrayTag;
import space.minecraftstl.xyml.library.nbt.tag.IntTag;
import space.minecraftstl.xyml.library.nbt.tag.ListTag;
import space.minecraftstl.xyml.library.nbt.tag.LongArrayTag;
import space.minecraftstl.xyml.library.nbt.tag.LongTag;
import space.minecraftstl.xyml.library.nbt.tag.ShortTag;
import space.minecraftstl.xyml.library.nbt.tag.StringTag;
import space.minecraftstl.xyml.library.nbt.tag.Tag;
import space.minecraftstl.xyml.library.nbt.tag.TagType;
import space.minecraftstl.xyml.library.nbt.tag.ValueTag;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.util.List;

/// Implements detached, deterministic conversions between compatible NBT tag types.
@NotNullByDefault
final class NBTTagConverter {
    /// Target types accepted for an integral scalar source.
    private static final @Unmodifiable List<TagType<?>> INTEGRAL_TARGETS = List.of(
            TagType.BYTE, TagType.SHORT, TagType.INT, TagType.LONG,
            TagType.FLOAT, TagType.DOUBLE, TagType.BYTE_ARRAY, TagType.STRING,
            TagType.INT_ARRAY, TagType.LONG_ARRAY);

    /// Target types accepted for a floating-point scalar source.
    private static final @Unmodifiable List<TagType<?>> FLOATING_TARGETS = List.of(
            TagType.BYTE, TagType.SHORT, TagType.INT, TagType.LONG,
            TagType.FLOAT, TagType.DOUBLE, TagType.STRING);

    /// Target types accepted for a string source.
    private static final @Unmodifiable List<TagType<?>> STRING_TARGETS = List.of(
            TagType.BYTE, TagType.SHORT, TagType.INT, TagType.LONG,
            TagType.FLOAT, TagType.DOUBLE, TagType.STRING);

    /// Target types accepted for an integral array source.
    private static final @Unmodifiable List<TagType<?>> INTEGRAL_ARRAY_TARGETS = List.of(
            TagType.BYTE, TagType.SHORT, TagType.INT, TagType.LONG,
            TagType.BYTE_ARRAY, TagType.INT_ARRAY, TagType.LONG_ARRAY);

    /// Prevents construction of this utility class.
    private NBTTagConverter() {
    }

    /// Returns the value-dependent conversion targets for a detached or attached tag.
    ///
    /// @param source source tag
    /// @return immutable conversion target list, including the current type
    static @Unmodifiable List<TagType<?>> getConvertibleTypes(Tag source) {
        if (isIntegralScalar(source)) {
            return INTEGRAL_TARGETS;
        }
        if (source instanceof FloatTag || source instanceof DoubleTag) {
            return FLOATING_TARGETS;
        }
        if (source instanceof StringTag) {
            return STRING_TARGETS;
        }
        if (isIntegralArray(source)) {
            return INTEGRAL_ARRAY_TARGETS;
        }
        if (source instanceof ListTag<?>) {
            return List.of(TagType.LIST, TagType.COMPOUND);
        }
        if (source instanceof CompoundTag compound && canBecomeList(compound)) {
            return List.of(TagType.LIST, TagType.COMPOUND);
        }
        return List.of(source.getType());
    }

    /// Creates a detached converted tag without changing the source.
    ///
    /// @param source source tag
    /// @param targetType target NBT type
    /// @return detached converted tag with the original name
    /// @throws NBTEditException if the source and target types are incompatible
    static Tag convert(Tag source, TagType<?> targetType) throws NBTEditException {
        if (!getConvertibleTypes(source).contains(targetType)) {
            throw conversionError(source, targetType);
        }

        Tag result;
        if (source.getType() == targetType) {
            result = source.clone();
        } else if (source instanceof ListTag<?> list && targetType == TagType.COMPOUND) {
            result = listToCompound(list);
        } else if (source instanceof CompoundTag compound && targetType == TagType.LIST) {
            result = compoundToList(compound);
        } else if (isIntegralArray(source) || isIntegralArrayType(targetType)) {
            result = convertIntegralBits(source, targetType);
        } else {
            result = convertScalar(source, targetType);
        }
        result.setName(source.getName());
        return result;
    }

    /// Converts a List into an insertion-ordered Compound whose keys are decimal indexes.
    ///
    /// @param source source list
    /// @return detached compound
    private static CompoundTag listToCompound(ListTag<?> source) {
        CompoundTag result = new CompoundTag();
        for (int index = 0; index < source.size(); index++) {
            result.addTag(Integer.toString(index), source.getTag(index).clone());
        }
        return result;
    }

    /// Converts a compatible numeric-key Compound into a List ordered by numeric index.
    ///
    /// @param source source compound
    /// @return detached homogeneous list
    private static ListTag<Tag> compoundToList(CompoundTag source) {
        ListTag<Tag> result = new ListTag<>();
        for (int index = 0; index < source.size(); index++) {
            Tag child = source.get(Integer.toString(index));
            if (child == null) {
                throw new AssertionError("Compound/List compatibility changed during conversion");
            }
            result.addTag(child.clone());
        }
        return result;
    }

    /// Tests whether all Compound keys are exactly `0` through `size - 1` and values are homogeneous.
    ///
    /// @param source source compound
    /// @return whether the compound has a valid List representation
    private static boolean canBecomeList(CompoundTag source) {
        TagType<?> elementType = null;
        for (int index = 0; index < source.size(); index++) {
            Tag child = source.get(Integer.toString(index));
            if (child == null) {
                return false;
            }
            if (elementType == null) {
                elementType = child.getType();
            } else if (elementType != child.getType()) {
                return false;
            }
        }
        return true;
    }

    /// Converts between scalar numeric and string types using Java numeric conversion rules.
    ///
    /// @param source scalar source
    /// @param targetType scalar target type
    /// @return detached scalar result
    /// @throws NBTEditException if the requested target is not a supported scalar type
    private static Tag convertScalar(Tag source, TagType<?> targetType) throws NBTEditException {
        if (targetType == TagType.STRING && source instanceof ValueTag<?> value) {
            return new StringTag(value.getAsString());
        }

        if (source instanceof StringTag string) {
            return convertString(string, targetType);
        }
        Number value = numericValue(source, targetType);
        if (targetType == TagType.BYTE) {
            return new ByteTag(value.byteValue());
        }
        if (targetType == TagType.SHORT) {
            return new ShortTag(value.shortValue());
        }
        if (targetType == TagType.INT) {
            return new IntTag(value.intValue());
        }
        if (targetType == TagType.LONG) {
            return new LongTag(value.longValue());
        }
        if (targetType == TagType.FLOAT) {
            return new FloatTag(value.floatValue());
        }
        if (targetType == TagType.DOUBLE) {
            return new DoubleTag(value.doubleValue());
        }
        throw conversionError(source, targetType);
    }

    /// Converts a base-ten string to a numeric scalar, using zero for invalid input.
    ///
    /// @param source string source
    /// @param targetType numeric scalar target type
    /// @return detached numeric result
    /// @throws NBTEditException if the target is not numeric
    private static Tag convertString(StringTag source, TagType<?> targetType) throws NBTEditException {
        BigDecimal decimal = parseDecimal(source.get());
        if (isIntegralScalarType(targetType)) {
            long lowBits = decimalLowBits(decimal, byteWidth(targetType) * Byte.SIZE);
            if (targetType == TagType.BYTE) {
                return new ByteTag((byte) lowBits);
            }
            if (targetType == TagType.SHORT) {
                return new ShortTag((short) lowBits);
            }
            if (targetType == TagType.INT) {
                return new IntTag((int) lowBits);
            }
            return new LongTag(lowBits);
        }
        if (targetType == TagType.FLOAT) {
            return new FloatTag(decimal.floatValue());
        }
        if (targetType == TagType.DOUBLE) {
            return new DoubleTag(decimal.doubleValue());
        }
        throw conversionError(source, targetType);
    }

    /// Returns the boxed numeric value held by a numeric scalar.
    ///
    /// @param source numeric scalar
    /// @param targetType requested target, used in an error message
    /// @return source number
    /// @throws NBTEditException if the source is not numeric
    private static Number numericValue(Tag source, TagType<?> targetType) throws NBTEditException {
        if (source instanceof ValueTag<?> value && value.getValue() instanceof Number number) {
            return number;
        }
        throw conversionError(source, targetType);
    }

    /// Parses a base-ten string, returning numeric zero when parsing is impossible.
    ///
    /// BigDecimal supplies truncation toward zero for integer targets and preserves decimal exponent
    /// syntax without accepting hexadecimal input.
    ///
    /// @param text source text
    /// @return parsed decimal value or zero
    private static BigDecimal parseDecimal(String text) {
        try {
            return new BigDecimal(text.trim());
        } catch (NumberFormatException exception) {
            return BigDecimal.ZERO;
        }
    }

    /// Truncates a decimal toward zero and returns its low-order two's-complement bits.
    ///
    /// Negative decimal scales are evaluated modulo the target width. This avoids materializing a
    /// potentially enormous integer for compact inputs such as `1e999999999`.
    ///
    /// @param decimal parsed decimal value
    /// @param bitWidth target bit width
    /// @return low-order bits in a long
    private static long decimalLowBits(BigDecimal decimal, int bitWidth) {
        BigInteger modulus = BigInteger.ONE.shiftLeft(bitWidth);
        BigInteger unscaled = decimal.unscaledValue();
        int scale = decimal.scale();
        if (scale > 0) {
            if (scale >= decimal.precision()) {
                return 0L;
            }
            return unscaled.divide(BigInteger.TEN.pow(scale)).mod(modulus).longValue();
        }
        if (scale < 0) {
            BigInteger factor = BigInteger.TEN.modPow(BigInteger.valueOf(-(long) scale), modulus);
            return unscaled.mod(modulus).multiply(factor).mod(modulus).longValue();
        }
        return unscaled.mod(modulus).longValue();
    }

    /// Converts between integral scalars and arrays through a big-endian two's-complement byte sequence.
    ///
    /// Scalar targets retain the low-order bits. Array targets preserve the complete sequence and
    /// zero-pad its most-significant side when the byte count is not a multiple of the target width.
    ///
    /// @param source integral scalar or array
    /// @param targetType integral scalar or array target type
    /// @return detached converted tag
    /// @throws NBTEditException if either side is not an integral representation
    private static Tag convertIntegralBits(Tag source, TagType<?> targetType) throws NBTEditException {
        if ((!isIntegralScalar(source) && !isIntegralArray(source))
                || (!isIntegralScalarType(targetType) && !isIntegralArrayType(targetType))) {
            throw conversionError(source, targetType);
        }
        byte[] bytes = toBigEndianBytes(source);
        if (targetType == TagType.BYTE_ARRAY) {
            return new ByteArrayTag(bytes);
        }
        if (targetType == TagType.INT_ARRAY) {
            return new IntArrayTag(toIntArray(bytes));
        }
        if (targetType == TagType.LONG_ARRAY) {
            return new LongArrayTag(toLongArray(bytes));
        }
        long lowBits = lowBits(bytes, byteWidth(targetType));
        if (targetType == TagType.BYTE) {
            return new ByteTag((byte) lowBits);
        }
        if (targetType == TagType.SHORT) {
            return new ShortTag((short) lowBits);
        }
        if (targetType == TagType.INT) {
            return new IntTag((int) lowBits);
        }
        return new LongTag(lowBits);
    }

    /// Serializes an integral scalar or array as fixed-width big-endian elements.
    ///
    /// @param source integral source
    /// @return raw two's-complement bytes
    /// @throws NBTEditException if the source is not integral
    private static byte[] toBigEndianBytes(Tag source) throws NBTEditException {
        if (source instanceof ByteTag value) {
            return new byte[]{value.get()};
        }
        if (source instanceof ShortTag value) {
            return ByteBuffer.allocate(Short.BYTES).putShort(value.get()).array();
        }
        if (source instanceof IntTag value) {
            return ByteBuffer.allocate(Integer.BYTES).putInt(value.get()).array();
        }
        if (source instanceof LongTag value) {
            return ByteBuffer.allocate(Long.BYTES).putLong(value.get()).array();
        }
        if (source instanceof ByteArrayTag values) {
            byte[] result = new byte[values.size()];
            for (int index = 0; index < result.length; index++) {
                result[index] = values.get(index);
            }
            return result;
        }
        if (source instanceof IntArrayTag values) {
            ByteBuffer result = ByteBuffer.allocate(checkedByteCount(values.size(), Integer.BYTES));
            for (int index = 0; index < values.size(); index++) {
                result.putInt(values.get(index));
            }
            return result.array();
        }
        if (source instanceof LongArrayTag values) {
            ByteBuffer result = ByteBuffer.allocate(checkedByteCount(values.size(), Long.BYTES));
            for (int index = 0; index < values.size(); index++) {
                result.putLong(values.get(index));
            }
            return result.array();
        }
        throw conversionError(source, TagType.BYTE_ARRAY);
    }

    /// Calculates an array byte count without allowing integer overflow.
    ///
    /// @param size element count
    /// @param width bytes per element
    /// @return exact byte count
    /// @throws NBTEditException if the array is too large to represent in memory
    private static int checkedByteCount(int size, int width) throws NBTEditException {
        try {
            return Math.multiplyExact(size, width);
        } catch (ArithmeticException exception) {
            throw new NBTEditException(NBTEditException.Reason.TYPE_MISMATCH,
                    "The integral array is too large to convert", exception);
        }
    }

    /// Packs a big-endian byte sequence into zero-padded big-endian integers.
    ///
    /// @param bytes source sequence
    /// @return packed integer array
    private static int[] toIntArray(byte[] bytes) {
        if (bytes.length == 0) {
            return new int[0];
        }
        int[] result = new int[(bytes.length - 1) / Integer.BYTES + 1];
        int padding = (Integer.BYTES - bytes.length % Integer.BYTES) % Integer.BYTES;
        for (int index = 0; index < bytes.length; index++) {
            int position = padding + index;
            int shift = (Integer.BYTES - 1 - position % Integer.BYTES) * Byte.SIZE;
            result[position / Integer.BYTES] |= Byte.toUnsignedInt(bytes[index]) << shift;
        }
        return result;
    }

    /// Packs a big-endian byte sequence into zero-padded big-endian longs.
    ///
    /// @param bytes source sequence
    /// @return packed long array
    private static long[] toLongArray(byte[] bytes) {
        if (bytes.length == 0) {
            return new long[0];
        }
        long[] result = new long[(bytes.length - 1) / Long.BYTES + 1];
        int padding = (Long.BYTES - bytes.length % Long.BYTES) % Long.BYTES;
        for (int index = 0; index < bytes.length; index++) {
            int position = padding + index;
            int shift = (Long.BYTES - 1 - position % Long.BYTES) * Byte.SIZE;
            result[position / Long.BYTES] |= (long) Byte.toUnsignedInt(bytes[index]) << shift;
        }
        return result;
    }

    /// Reads the low-order target width from a big-endian byte sequence.
    ///
    /// @param bytes source sequence
    /// @param width target byte width
    /// @return low-order bits in a long
    private static long lowBits(byte[] bytes, int width) {
        long result = 0L;
        for (int index = Math.max(0, bytes.length - width); index < bytes.length; index++) {
            result = result << Byte.SIZE | Byte.toUnsignedLong(bytes[index]);
        }
        return result;
    }

    /// Returns the fixed byte width of an integral scalar type.
    ///
    /// @param type integral scalar type
    /// @return byte width
    private static int byteWidth(TagType<?> type) {
        if (type == TagType.BYTE) {
            return Byte.BYTES;
        }
        if (type == TagType.SHORT) {
            return Short.BYTES;
        }
        if (type == TagType.INT) {
            return Integer.BYTES;
        }
        return Long.BYTES;
    }

    /// Tests whether a tag is one of the four integral scalar types.
    ///
    /// @param tag candidate tag
    /// @return whether it is integral
    private static boolean isIntegralScalar(Tag tag) {
        return isIntegralScalarType(tag.getType());
    }

    /// Tests whether a type is one of the four integral scalar types.
    ///
    /// @param type candidate type
    /// @return whether it is integral
    private static boolean isIntegralScalarType(TagType<?> type) {
        return type == TagType.BYTE || type == TagType.SHORT || type == TagType.INT || type == TagType.LONG;
    }

    /// Tests whether a tag is one of the three integral array types.
    ///
    /// @param tag candidate tag
    /// @return whether it is an integral array
    private static boolean isIntegralArray(Tag tag) {
        return isIntegralArrayType(tag.getType());
    }

    /// Tests whether a type is one of the three integral array types.
    ///
    /// @param type candidate type
    /// @return whether it is an integral array
    private static boolean isIntegralArrayType(TagType<?> type) {
        return type == TagType.BYTE_ARRAY || type == TagType.INT_ARRAY || type == TagType.LONG_ARRAY;
    }

    /// Creates a stable checked error for an unsupported conversion.
    ///
    /// @param source source tag
    /// @param targetType requested target type
    /// @return checked conversion error
    private static NBTEditException conversionError(Tag source, TagType<?> targetType) {
        return new NBTEditException(NBTEditException.Reason.TYPE_MISMATCH,
                "Cannot convert " + source.getType() + " to " + targetType);
    }
}
