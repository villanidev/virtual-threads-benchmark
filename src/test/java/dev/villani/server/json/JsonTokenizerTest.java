package dev.villani.server.json;

import dev.villani.testsupport.AllocationProbe;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("JsonTokenizer")
class JsonTokenizerTest {

    private final JsonTokenizer tokenizer = new JsonTokenizer(4);

    /** Compact textual trace of the events (test-only, allocates freely). */
    private String trace(String json) {
        final StringBuilder sb = new StringBuilder();
        tokenizer.reset();
        for (byte b : json.getBytes(StandardCharsets.UTF_8)) {
            append(sb, tokenizer.feed(b));
            append(sb, tokenizer.pending());
        }
        return sb.toString();
    }

    private void append(StringBuilder sb, int event) {
        switch (event) {
            case JsonTokenizer.NONE -> { }
            case JsonTokenizer.OBJECT_START -> sb.append('{');
            case JsonTokenizer.OBJECT_END -> sb.append('}');
            case JsonTokenizer.ARRAY_START -> sb.append('[');
            case JsonTokenizer.ARRAY_END -> sb.append(']');
            case JsonTokenizer.KEY_START -> sb.append("k(");
            case JsonTokenizer.KEY_END -> sb.append(')');
            case JsonTokenizer.STRING_START -> sb.append("s(");
            case JsonTokenizer.STRING_END -> sb.append(')');
            case JsonTokenizer.CHAR -> sb.append((char) (tokenizer.current() & 0xFF));
            case JsonTokenizer.NUMBER -> sb.append('#').append(tokenizer.number());
            case JsonTokenizer.TRUE -> sb.append('T');
            case JsonTokenizer.FALSE -> sb.append('F');
            case JsonTokenizer.NULL -> sb.append('N');
            case JsonTokenizer.ERROR -> sb.append('!');
            default -> throw new AssertionError(event);
        }
    }

    @Nested
    @DisplayName("valid documents")
    class Valid {

        @Test
        @DisplayName("given a flat object, when tokenizing, then keys, strings and numbers are emitted in order")
        void givenFlatObject_whenTokenizing_thenEvents() {
            // given
            final String json = "{\"nome\":\"Ana\",\"servico\":3}";

            // when
            final String events = trace(json);

            // then
            assertEquals("{k(nome)s(Ana)k(servico)#3}", events);
            assertTrue(tokenizer.isComplete());
        }

        @Test
        @DisplayName("given literals, negatives, arrays and nesting, when tokenizing, then all are recognised")
        void givenMixedDocument_whenTokenizing_thenAllRecognised() {
            // given
            final String json = " { \"a\" : true , \"b\":null,\"c\":[1,-20, false ],\"d\":{\"e\":0} }\n";

            // when
            final String events = trace(json);

            // then
            assertEquals("{k(a)Tk(b)Nk(c)[#1#-20F]k(d){k(e)#0}}", events);
            assertTrue(tokenizer.isComplete());
        }

        @Test
        @DisplayName("given multi-byte UTF-8 in a string, when tokenizing, then its raw bytes pass through")
        void givenUtf8_whenTokenizing_thenBytesPass() {
            // given / when
            trace("{\"n\":\"é\"}");

            // then
            assertTrue(tokenizer.isComplete());
        }

        @Test
        @DisplayName("given a tokenizer already used, when reset, then it parses a new document")
        void givenUsedTokenizer_whenReset_thenReusable() {
            // given
            trace("{\"a\":1}");

            // when
            final String events = trace("[2]");

            // then
            assertEquals("[#2]", events);
        }
    }

    @Nested
    @DisplayName("invalid documents")
    class Invalid {

        @Test
        @DisplayName("given a string escape, when tokenizing, then it is rejected")
        void givenEscape_whenTokenizing_thenError() {
            assertTrue(trace("{\"a\":\"x\\ny\"}").endsWith("!"));
            assertTrue(tokenizer.isError());
        }

        @Test
        @DisplayName("given decimals, leading zeros or a lone minus, when tokenizing, then they are rejected")
        void givenBadNumbers_whenTokenizing_thenError() {
            assertTrue(trace("{\"a\":1.5}").contains("!"));
            assertTrue(trace("{\"a\":01}").contains("!"));
            assertTrue(trace("{\"a\":-}").contains("!"));
            assertTrue(trace("{\"a\":1234567890123456789}").contains("!"));
        }

        @Test
        @DisplayName("given structural mistakes, when tokenizing, then they are rejected")
        void givenStructuralMistakes_whenTokenizing_thenError() {
            assertTrue(trace("{\"a\" 1}").contains("!"));
            assertTrue(trace("{\"a\":1,}").contains("!"));
            assertTrue(trace("{a:1}").contains("!"));
            assertTrue(trace("[1}").contains("!"));
            assertTrue(trace("{\"a\":tru}").contains("!"));
            assertTrue(trace("42").contains("!"));
        }

        @Test
        @DisplayName("given trailing garbage after a complete document, when tokenizing, then it is rejected")
        void givenTrailingGarbage_whenTokenizing_thenError() {
            assertTrue(trace("{}x").endsWith("!"));
        }

        @Test
        @DisplayName("given nesting beyond the configured depth, when tokenizing, then it is rejected")
        void givenTooDeep_whenTokenizing_thenError() {
            assertTrue(trace("[[[[[1]]]]]").contains("!"));
        }

        @Test
        @DisplayName("given a raw control character in a string, when tokenizing, then it is rejected")
        void givenControlChar_whenTokenizing_thenError() {
            assertTrue(trace("{\"a\":\"x\ty\"}").contains("!"));
        }

        @Test
        @DisplayName("given an incomplete document, when the input ends, then it is not complete")
        void givenIncomplete_whenInputEnds_thenNotComplete() {
            trace("{\"a\":1");
            assertFalse(tokenizer.isComplete());
            assertFalse(tokenizer.isError());
        }
    }

    @Nested
    @DisplayName("allocation")
    class Allocation {

        @Test
        @DisplayName("given steady-state tokenizing, when feeding a whole document, then zero bytes are allocated")
        void givenSteadyState_whenFeeding_thenZeroAlloc() {
            // given
            final byte[] doc = "{\"nome\":\"Ana Maria\",\"servico\":3,\"dataHora\":1767225600,\"ok\":true,\"t\":[1,2]}"
                    .getBytes(StandardCharsets.UTF_8);
            final Runnable body = () -> {
                tokenizer.reset();
                long sum = 0;
                for (byte b : doc) {
                    sum += tokenizer.feed(b) + tokenizer.pending();
                }
                if (!tokenizer.isComplete() || sum == 0) {
                    throw new AssertionError();
                }
            };

            // when / then
            AllocationProbe.assertZeroAlloc(body, 200_000, 20_000);
        }
    }
}
