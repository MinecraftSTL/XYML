/*
 * Copyright 2026 Glavo
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.glavo.nbt.validation;

import org.glavo.nbt.tag.CompoundTag;
import org.glavo.nbt.tag.IntTag;
import org.glavo.nbt.tag.StringTag;
import org.glavo.nbt.tag.Tag;
import org.glavo.nbt.tag.TagType;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SuppressWarnings("ThrowableNotThrown")
final class NBTSchemaTest {

	private static <T extends Tag> void assertAccepts(NBTSchema<T> schema, T tag) {
		assertTrue(schema.test(tag));
		assertDoesNotThrow(() -> schema.validate(tag));
	}

	private static <T extends Tag> NBTValidationException assertRejects(NBTSchema<T> schema, T tag) {
		assertFalse(schema.test(tag));
		return assertThrows(NBTValidationException.class, () -> schema.validate(tag));
	}

	private static <T extends Tag> NBTSchema<T> invokeOr(NBTSchema<T> left, NBTSchema<? extends T> right) {
		try {
			@SuppressWarnings("unchecked")
			NBTSchema<T> schema = (NBTSchema<T>) NBTSchema.class.getMethod("or", NBTSchema.class).invoke(left, right);
			return schema;
		} catch (ReflectiveOperationException e) {
			throw new AssertionError(e);
		}
	}

	private static <T extends Tag> NBTSchema<T> invokeAnd(NBTSchema<T> left, NBTSchema<? extends T> right) {
		try {
			@SuppressWarnings("unchecked")
			NBTSchema<T> schema = (NBTSchema<T>) NBTSchema.class.getMethod("and", NBTSchema.class).invoke(left, right);
			return schema;
		} catch (ReflectiveOperationException e) {
			throw new AssertionError(e);
		}
	}

	private static <T extends Tag> void assertOrRejectsNull(NBTSchema<T> schema) {
		InvocationTargetException exception = assertThrows(InvocationTargetException.class,
				() -> NBTSchema.class.getMethod("or", NBTSchema.class).invoke(schema, new Object[]{null}));
		assertInstanceOf(NullPointerException.class, exception.getCause());
	}

	private static <T extends Tag> void assertAndRejectsNull(NBTSchema<T> schema) {
		InvocationTargetException exception = assertThrows(InvocationTargetException.class,
				() -> NBTSchema.class.getMethod("and", NBTSchema.class).invoke(schema, new Object[]{null}));
		assertInstanceOf(NullPointerException.class, exception.getCause());
	}

	@Test
	void testTypeIs() {
		NBTSchema<StringTag> schema = NBTSchema.typeIs(TagType.STRING);

		assertAccepts(schema, new StringTag("Hello"));
		assertAccepts(schema, new StringTag("Hello").setName("value"));
	}

	@Test
	void testTypeIsRejectsWrongType() {
		NBTSchema<StringTag> schema = NBTSchema.typeIs(TagType.STRING);

		@SuppressWarnings("unchecked")
		NBTSchema<Tag> rawSchema = (NBTSchema<Tag>) (NBTSchema<?>) schema;

        NBTValidationException exception = assertRejects(rawSchema, new IntTag(42));
		assertEquals("Expected TAG_String, but got TAG_Int", exception.getMessage());
	}

    @Test
	void testMatchScalarUsesEqualsAndClonesExpectedTag() {
		IntTag expected = new IntTag(42).setName("score");
		NBTSchema<IntTag> schema = NBTSchema.match(expected);

		expected.set(99).setName("changed");

		assertAccepts(schema, new IntTag(42).setName("score"));
		assertRejects(schema, new IntTag(42).setName("other"));
		assertRejects(schema, new IntTag(99).setName("score"));
	}

	@Test
	void testMatchCompoundUsesSubsetMatchingAndClonesExpectedTag() {
		CompoundTag expected = new CompoundTag()
				.setString("name", "Alex")
				.addTag("stats", new CompoundTag().setInt("score", 42));

		NBTSchema<CompoundTag> schema = NBTSchema.match(expected);

		expected.setString("name", "Steve");
		CompoundTag stats = assertInstanceOf(CompoundTag.class, expected.get("stats"));
		stats.setInt("score", 0);

		CompoundTag actual = new CompoundTag()
				.setString("name", "Alex")
				.setString("title", "Hero")
				.addTag("stats", new CompoundTag()
						.setInt("score", 42)
						.setInt("level", 7));

		assertAccepts(schema, actual);
		assertRejects(schema, new CompoundTag()
				.setString("name", "Alex")
				.addTag("stats", new CompoundTag().setInt("score", 1)));
		assertRejects(schema, new CompoundTag().setString("name", "Alex"));
	}

	@Test
	void testUnionAndOr() {
		NBTSchema<StringTag> stringSchema = NBTSchema.typeIs(TagType.STRING);
		NBTSchema<IntTag> intSchema = NBTSchema.typeIs(TagType.INT);
		NBTSchema<StringTag> helloSchema = NBTSchema.match(new StringTag("hello"));
		NBTSchema<StringTag> worldSchema = NBTSchema.match(new StringTag("world"));

		assertSame(stringSchema, NBTSchema.union(stringSchema));
		assertThrows(IllegalArgumentException.class, NBTSchema::union);
		assertOrRejectsNull(helloSchema);

		NBTSchema<Tag> union = NBTSchema.union(stringSchema, intSchema);
		NBTSchema<StringTag> orSchema = invokeOr(helloSchema, worldSchema);

		assertAccepts(union, new StringTag("hello"));
		assertAccepts(union, new IntTag(42));
		assertAccepts(orSchema, new StringTag("hello"));
		assertAccepts(orSchema, new StringTag("world"));
		assertRejects(orSchema, new StringTag("other"));

		CompoundTag actual = new CompoundTag();
		NBTValidationException exception = assertRejects(union, actual);
		assertEquals("No schema matched", exception.getMessage());
		assertEquals(2, exception.getSuppressed().length);
		assertEquals("Expected TAG_String, but got TAG_Compound", exception.getSuppressed()[0].getMessage());
		assertEquals("Expected TAG_Int, but got TAG_Compound", exception.getSuppressed()[1].getMessage());
	}

	@Test
	void testIntersectionAndAnd() {
		NBTSchema<CompoundTag> hasName = NBTSchema.match(new CompoundTag().setString("name", "Alex"));
		NBTSchema<CompoundTag> hasScore = NBTSchema.match(new CompoundTag().setInt("score", 42));

		assertSame(hasName, NBTSchema.intersection(hasName));
		assertThrows(IllegalArgumentException.class, NBTSchema::intersection);
		assertAndRejectsNull(hasName);

		NBTSchema<CompoundTag> intersection = NBTSchema.intersection(hasName, hasScore);
		NBTSchema<CompoundTag> andSchema = invokeAnd(hasName, hasScore);

		CompoundTag valid = new CompoundTag().setString("name", "Alex").setInt("score", 42).setInt("level", 7);
		assertAccepts(intersection, valid);
		assertAccepts(andSchema, valid);

		NBTValidationException exception = assertRejects(intersection, new CompoundTag().setString("name", "Alex"));
		assertTrue(exception.getMessage().startsWith("Failed to validate tag against schema: "));
		NBTValidationException cause = assertInstanceOf(NBTValidationException.class, exception.getCause());
		assertEquals("Tag does not match the expected schema: " + new CompoundTag().setInt("score", 42), cause.getMessage());
	}

	@Test
	void testCompoundTagBuilderWithRequiredOptionalAndNestedSchemas() {
		CompoundTag profilePattern = new CompoundTag().setString("role", "admin");

		NBTSchema<CompoundTag> profileSchema = NBTSchema.beginCompound()
				.addRequired("id", TagType.INT)
				.addOptional("nickname", TagType.STRING)
				.addRequired("meta", profilePattern)
				.end();

		profilePattern.setString("role", "guest");

		NBTSchema<CompoundTag> rootSchema = NBTSchema.beginCompound()
				.addRequired("name", TagType.STRING)
				.addOptional("age", TagType.INT)
				.addRequired("profile", profileSchema)
				.end();

		CompoundTag valid = new CompoundTag()
				.setString("name", "Alex")
				.setInt("age", 20)
				.addTag("profile", new CompoundTag()
						.setInt("id", 7)
						.setString("nickname", "builder")
						.addTag("meta", new CompoundTag()
								.setString("role", "admin")
								.setString("extra", "ok")));

		assertAccepts(rootSchema, valid);

		NBTValidationException missingRequired = assertRejects(rootSchema, new CompoundTag()
				.addTag("profile", new CompoundTag()
						.setInt("id", 7)
						.addTag("meta", new CompoundTag().setString("role", "admin"))));
		assertEquals("Missing required subtag: name", missingRequired.getMessage());

		NBTValidationException invalidNested = assertRejects(rootSchema, new CompoundTag()
				.setString("name", "Alex")
				.addTag("profile", new CompoundTag()
						.setInt("id", 7)
						.addTag("meta", new CompoundTag().setString("role", "guest"))));
		assertEquals("Invalid subtag profile", invalidNested.getMessage());
		NBTValidationException nestedCause = assertInstanceOf(NBTValidationException.class, invalidNested.getCause());
		assertEquals("Invalid subtag meta", nestedCause.getMessage());
		NBTValidationException leafCause = assertInstanceOf(NBTValidationException.class, nestedCause.getCause());
		assertEquals("Tag does not match the expected schema: " + new CompoundTag().setString("role", "admin"), leafCause.getMessage());
	}
}
