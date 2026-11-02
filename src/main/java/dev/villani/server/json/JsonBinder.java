package dev.villani.server.json;

import dev.villani.server.memory.ByteRing;

/**
 * Drives a {@link JsonTokenizer} over bytes of a {@link ByteRing} and binds a flat JSON object
 * to a {@link JsonRecord} according to a {@link JsonSchema}. Keys are matched byte-wise against
 * the schema; unknown scalar keys are ignored; nested values, duplicates and type mismatches are
 * errors. No String or boxed value is ever created.
 */
public final class JsonBinder {

    /**
     * roda o tokenizador sobre os bytes do ring e casa as chaves byte a byte com o schema. 
     * Chaves e valores desconhecidos são ignorados, e null deixa o campo ausente.
        Códigos de erro, todos viram 400 no handler: BAD_TYPE, TOO_LONG, DUPLICATE_KEY, 
        UNSUPPORTED (aninhado ou array), BAD_SYNTAX e INCOMPLETE.
     */

    public static final int OK = 0;
    public static final int BAD_SYNTAX = 1;
    public static final int BAD_TYPE = 2;
    public static final int TOO_LONG = 3;
    public static final int DUPLICATE_KEY = 4;
    public static final int UNSUPPORTED = 5;
    public static final int INCOMPLETE = 6;

    private static final int NO_FIELD = -1;
    private static final int UNKNOWN = -2;

    private final JsonSchema schema;
    private final JsonTokenizer tokenizer;
    private final byte[] keyBuffer;
    private int keyLength;
    private boolean keyOverflow;
    private int field;
    private JsonRecord record;

    public JsonBinder(JsonSchema schema, int maxDepth) {
        this.schema = schema;
        this.tokenizer = new JsonTokenizer(maxDepth);
        this.keyBuffer = new byte[schema.maxNameLength()];
    }

    /** Parses {@code length} bytes starting at ring offset {@code offset} into {@code out}. */
    public int bind(ByteRing ring, int offset, int length, JsonRecord out) {
        tokenizer.reset();
        record = out;
        field = NO_FIELD;
        out.clear();
        for (int i = 0; i < length; i++) {
            int code = handle(tokenizer.feed(ring.peek(offset + i)));
            if (code == OK) {
                code = handle(tokenizer.pending());
            }
            if (code != OK) {
                return code;
            }
        }
        return tokenizer.isComplete() ? OK : INCOMPLETE;
    }

    private int handle(int event) {
        switch (event) {
            case JsonTokenizer.NONE:
            case JsonTokenizer.OBJECT_END:
            case JsonTokenizer.STRING_END:
                return OK;
            case JsonTokenizer.OBJECT_START:
                return tokenizer.depth() == 1 ? OK : UNSUPPORTED;
            case JsonTokenizer.ARRAY_START:
                return UNSUPPORTED;
            case JsonTokenizer.ARRAY_END:
                return UNSUPPORTED;
            case JsonTokenizer.KEY_START:
                keyLength = 0;
                keyOverflow = false;
                field = NO_FIELD;
                return OK;
            case JsonTokenizer.CHAR:
                return onChar();
            case JsonTokenizer.KEY_END:
                return onKeyEnd();
            case JsonTokenizer.STRING_START:
                return onValue(JsonSchema.STRING);
            case JsonTokenizer.NUMBER:
                return onNumber();
            case JsonTokenizer.TRUE:
                return onBool(1);
            case JsonTokenizer.FALSE:
                return onBool(0);
            case JsonTokenizer.NULL:
                return OK; // null leaves the field absent
            default:
                return BAD_SYNTAX;
        }
    }

    private int onChar() {
        final byte b = tokenizer.current();
        if (field == NO_FIELD) { // inside a key
            if (keyLength < keyBuffer.length) {
                keyBuffer[keyLength++] = b;
            } else {
                keyOverflow = true;
            }
            return OK;
        }
        if (field == UNKNOWN) {
            return OK;
        }
        return record.appendStringByte(field, b) ? OK : TOO_LONG;
    }

    private int onKeyEnd() {
        field = UNKNOWN;
        if (keyOverflow) {
            return OK;
        }
        for (int f = 0; f < schema.fieldCount(); f++) {
            final byte[] name = schema.names[f];
            if (name.length != keyLength) {
                continue;
            }
            int k = 0;
            while (k < keyLength && name[k] == keyBuffer[k]) {
                k++;
            }
            if (k == keyLength) {
                if (record.has(f)) {
                    return DUPLICATE_KEY;
                }
                field = f;
                return OK;
            }
        }
        return OK;
    }

    /** Checks the type of the value being started and finishes the key-value pair when scalar. */
    private int onValue(int type) {
        if (field == UNKNOWN) {
            return OK;
        }
        if (schema.types[field] != type) {
            return BAD_TYPE;
        }
        record.markPresent(field);
        return OK;
    }

    private int onNumber() {
        final int f = field;
        field = NO_FIELD;
        if (f == UNKNOWN) {
            return OK;
        }
        if (schema.types[f] != JsonSchema.INT) {
            return BAD_TYPE;
        }
        record.setInt(f, tokenizer.number());
        return OK;
    }

    private int onBool(int value) {
        final int f = field;
        field = NO_FIELD;
        if (f == UNKNOWN) {
            return OK;
        }
        if (schema.types[f] != JsonSchema.BOOL) {
            return BAD_TYPE;
        }
        record.setInt(f, value);
        return OK;
    }
}
