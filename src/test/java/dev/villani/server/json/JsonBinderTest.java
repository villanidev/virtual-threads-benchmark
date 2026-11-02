package dev.villani.server.json;

import dev.villani.server.memory.ByteRing;
import dev.villani.testsupport.AllocationProbe;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("JsonBinder, JsonRecord and JsonRecordPool")
class JsonBinderTest {

    private final ByteRing ring = new ByteRing(ByteBuffer.allocateDirect(1024));
    private JsonSchema schema;
    private int nome;
    private int servico;
    private int dataHora;
    private int confirmado;
    private JsonBinder binder;
    private JsonRecordPool pool;

    @BeforeEach
    void setUp() {
        schema = new JsonSchema(4);
        nome = schema.field("nome", JsonSchema.STRING, 8);
        servico = schema.field("servico", JsonSchema.INT);
        dataHora = schema.field("dataHora", JsonSchema.INT);
        confirmado = schema.field("confirmado", JsonSchema.BOOL);
        binder = new JsonBinder(schema, 4);
        pool = new JsonRecordPool(schema, 2);
    }

    private int bind(String json, JsonRecord out) {
        ring.clear();
        final byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        ring.put(ByteBuffer.wrap(bytes));
        return binder.bind(ring, 0, bytes.length, out);
    }

    private String text(JsonRecord r, int field) {
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < r.stringLength(field); i++) {
            sb.append((char) r.stringByte(field, i));
        }
        return sb.toString();
    }

    @Nested
    @DisplayName("binding")
    class Binding {

        @Test
        @DisplayName("given a matching object, when binding, then every field is filled in the record")
        void givenMatchingObject_whenBinding_thenFieldsFilled() {
            // given
            final JsonRecord r = pool.acquire();

            // when
            final int code = bind("{\"nome\":\"Ana\",\"servico\":3,\"dataHora\":1767225600,\"confirmado\":true}", r);

            // then
            assertEquals(JsonBinder.OK, code);
            assertEquals("Ana", text(r, nome));
            assertEquals(3, r.getInt(servico));
            assertEquals(1767225600L, r.getInt(dataHora));
            assertTrue(r.getBool(confirmado));
        }

        @Test
        @DisplayName("given unknown scalar keys and null values, when binding, then they are ignored")
        void givenUnknownKeysAndNull_whenBinding_thenIgnored() {
            // given
            final JsonRecord r = pool.acquire();

            // when
            final int code = bind("{\"x\":\"zzz\",\"y\":9,\"servico\":null,\"nome\":\"B\"}", r);

            // then
            assertEquals(JsonBinder.OK, code);
            assertFalse(r.has(servico));
            assertEquals("B", text(r, nome));
        }

        @Test
        @DisplayName("given a missing field, when binding, then has() is false for it")
        void givenMissingField_whenBinding_thenAbsent() {
            // given
            final JsonRecord r = pool.acquire();

            // when
            bind("{\"nome\":\"Ana\"}", r);

            // then
            assertTrue(r.has(nome));
            assertFalse(r.has(dataHora));
        }

        @Test
        @DisplayName("given a record bound before, when binding again, then old values are cleared")
        void givenUsedRecord_whenBindingAgain_thenCleared() {
            // given
            final JsonRecord r = pool.acquire();
            bind("{\"nome\":\"Longo\",\"servico\":9}", r);

            // when
            bind("{\"nome\":\"X\"}", r);

            // then
            assertEquals("X", text(r, nome));
            assertFalse(r.has(servico));
        }

        @Test
        @DisplayName("given a string field, when copying out, then bytes land in the destination buffer")
        void givenStringField_whenCopying_thenBytesCopied() {
            // given
            final JsonRecord r = pool.acquire();
            bind("{\"nome\":\"Ana\"}", r);
            final ByteBuffer dst = ByteBuffer.allocateDirect(8);

            // when
            r.copyStringTo(nome, dst);

            // then
            assertEquals(3, dst.position());
            assertEquals('A', dst.get(0));
        }
    }

    @Nested
    @DisplayName("rejections")
    class Rejections {

        @Test
        @DisplayName("given wrong types, when binding, then BAD_TYPE")
        void givenWrongTypes_whenBinding_thenBadType() {
            final JsonRecord r = pool.acquire();
            assertEquals(JsonBinder.BAD_TYPE, bind("{\"servico\":\"3\"}", r));
            assertEquals(JsonBinder.BAD_TYPE, bind("{\"nome\":3}", r));
            assertEquals(JsonBinder.BAD_TYPE, bind("{\"confirmado\":1}", r));
        }

        @Test
        @DisplayName("given a string longer than the field capacity, when binding, then TOO_LONG")
        void givenLongString_whenBinding_thenTooLong() {
            assertEquals(JsonBinder.TOO_LONG, bind("{\"nome\":\"123456789\"}", pool.acquire()));
        }

        @Test
        @DisplayName("given a duplicate key, when binding, then DUPLICATE_KEY")
        void givenDuplicateKey_whenBinding_thenRejected() {
            assertEquals(JsonBinder.DUPLICATE_KEY, bind("{\"servico\":1,\"servico\":2}", pool.acquire()));
        }

        @Test
        @DisplayName("given nested values or arrays, when binding, then UNSUPPORTED")
        void givenNested_whenBinding_thenUnsupported() {
            final JsonRecord r = pool.acquire();
            assertEquals(JsonBinder.UNSUPPORTED, bind("{\"a\":{\"b\":1}}", r));
            assertEquals(JsonBinder.UNSUPPORTED, bind("{\"a\":[1]}", r));
            assertEquals(JsonBinder.UNSUPPORTED, bind("[1]", r));
        }

        @Test
        @DisplayName("given broken or truncated JSON, when binding, then BAD_SYNTAX or INCOMPLETE")
        void givenBrokenJson_whenBinding_thenError() {
            final JsonRecord r = pool.acquire();
            assertEquals(JsonBinder.BAD_SYNTAX, bind("{nome}", r));
            assertEquals(JsonBinder.INCOMPLETE, bind("{\"nome\":\"A\"", r));
        }

        @Test
        @DisplayName("given a key longer than any schema name, when binding, then it is skipped as unknown")
        void givenHugeKey_whenBinding_thenSkipped() {
            assertEquals(JsonBinder.OK, bind("{\"averyveryverylongkeyname\":1,\"servico\":2}", pool.acquire()));
        }
    }

    @Nested
    @DisplayName("pooling")
    class Pooling {

        @Test
        @DisplayName("given an exhausted pool, when acquiring, then null; after release it is available again")
        void givenExhaustedPool_whenAcquiring_thenNullUntilRelease() {
            // given
            final JsonRecord a = pool.acquire();
            pool.acquire();

            // when / then
            assertNull(pool.acquire());
            pool.release(a);
            assertSame(a, pool.acquire());
        }

        @Test
        @DisplayName("given a released record, when releasing it again, then it fails")
        void givenReleasedRecord_whenReleasingAgain_thenFails() {
            final JsonRecord a = pool.acquire();
            pool.release(a);
            assertThrows(IllegalStateException.class, () -> pool.release(a));
        }
    }

    @Nested
    @DisplayName("allocation")
    class Allocation {

        @Test
        @DisplayName("given steady-state binding, when acquiring, binding and releasing, then zero bytes are allocated")
        void givenSteadyState_whenBinding_thenZeroAlloc() {
            // given
            final byte[] doc = "{\"nome\":\"Ana\",\"servico\":3,\"dataHora\":1767225600,\"confirmado\":false,\"extra\":\"zz\"}"
                    .getBytes(StandardCharsets.UTF_8);
            ring.put(ByteBuffer.wrap(doc));
            final Runnable body = () -> {
                final JsonRecord r = pool.acquire();
                if (binder.bind(ring, 0, doc.length, r) != JsonBinder.OK || r.getInt(servico) != 3) {
                    throw new AssertionError();
                }
                pool.release(r);
            };

            // when / then
            AllocationProbe.assertZeroAlloc(body, 200_000, 20_000);
        }
    }
}
