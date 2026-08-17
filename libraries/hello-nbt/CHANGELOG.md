# Changelog

## 0.4.0 (In development)

- New API `NBTSchema#beginCompound()` for creating a schema for validating compound tags.
- Relax type bounds of the `NBTSchema#typeIs` method.

### Breaking Changes

- The `NBTSchema.Builder#build()` method has been renamed to `end()`.

### Bug Fixes

- Fix #3: `ParentTag#removeAt` throws `ArrayIndexOutOfBoundsException` at specific sizes (#4)

## 0.3.0 (2026-03-25)

- New API `NBTSchema` for validating NBT elements.

### Breaking Changes

- The `NBTValidator` interface has been rewritten and now provides two methods for validation:
    - [#test(NBTElement)]: Returns `true` if the element meets the rules of this validator, otherwise returns `false`.
    - [#validate(NBTElement)]: Does nothing if the element meets the rules of this validator, otherwise throws an
      exception.

## 0.2.0 (2026-03-17)

- New API `NBTCodec#writeRegion(Path, ChunkRegion)` and `NBTCodec#writeRegion(Path, ChunkRegion, ExternalChunkAccessor)`
  for writing chunk regions to files.
- New API `NBTCodec#byteSize(Tag)` and `NBTCodec#contentByteSize(Tag)` for calculating the encoded byte size of NBT
  tags.
- New API `NBTCodec#writeTag(ByteBuffer, Tag)` for writing NBT tags to a byte buffer.
- New API `NBTCodec#writeTagToByteArray(Tag)` for writing NBT tags to a byte array.
- New API `ArrayTag#getElementType()` for getting the element type of array tags.
  
## 0.1.0 (2026-03-16)

- Initial release
