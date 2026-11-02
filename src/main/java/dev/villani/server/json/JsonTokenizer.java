package dev.villani.server.json;

/**
 * Push-style JSON tokenizer: a byte-at-a-time state machine (Go/C# style) with all state in
 * primitive fields and a preallocated depth stack. Feeding a byte returns at most one event
 * (plus {@link #pending()} when a number is terminated by a delimiter that also has its own
 * event). Because every byte is independent input, parsing is resumable across partial reads.
 *
 * <p>Supported: objects, arrays, strings, integers (up to 18 digits), true/false/null.
 * Rejected on purpose: string escapes ({@code \}), decimals/exponents, control characters in
 * strings, trailing garbage and top-level scalars. String content is emitted byte by byte as
 * {@link #CHAR} events (raw UTF-8 bytes pass through), never materialised as a String.
 */
public final class JsonTokenizer {

    /**
     * Estilo: máquina de estados byte a byte, no estilo Go/C#
     * Todo o estado fica em campos primitivos e numa pilha de profundidade pré-alocada. 
     * Cada byte é uma entrada independente, então o parse pode ser retomado depois de uma leitura parcial.
     * Eventos: feed(byte) devolve um evento:
        estrutura (OBJECT_START, OBJECT_END, ARRAY_START, ARRAY_END);
        KEY_START/KEY_END e STRING_START/STRING_END;
        CHAR, com o byte do conteúdo em current();
        NUMBER, com o valor em number();
        TRUE, FALSE, NULL e ERROR.
     * Delimitador de número: quando um delimitador encerra um número (por exemplo }), ele gera um segundo 
        evento, lido em pending().
     * Aceita: objetos, arrays, strings (bytes UTF-8 passam crus), inteiros de até 18 dígitos e true/false/null.
     * Rejeita: escapes \, decimais, zero à esquerda, caracteres de controle, lixo depois do documento, 
        valor escalar no topo e profundidade acima do configurado.       
     *  
     */

    public static final int NONE = 0;
    public static final int OBJECT_START = 1;
    public static final int OBJECT_END = 2;
    public static final int ARRAY_START = 3;
    public static final int ARRAY_END = 4;
    public static final int KEY_START = 5;
    public static final int KEY_END = 6;
    public static final int STRING_START = 7;
    public static final int STRING_END = 8;
    /** A byte of string/key content, available through {@link #current()}. */
    public static final int CHAR = 9;
    /** An integer, available through {@link #number()}. */
    public static final int NUMBER = 10;
    public static final int TRUE = 11;
    public static final int FALSE = 12;
    public static final int NULL = 13;
    public static final int ERROR = 14;

    private static final int S_START = 0;
    private static final int S_KEY_OR_END = 1;
    private static final int S_KEY = 2;
    private static final int S_COLON = 3;
    private static final int S_VALUE_OR_END = 4;
    private static final int S_VALUE = 5;
    private static final int S_COMMA_OR_END = 6;
    private static final int S_STRING = 7;
    private static final int S_KEY_STRING = 8;
    private static final int S_NUMBER = 9;
    private static final int S_LITERAL = 10;
    private static final int S_DONE = 11;
    private static final int S_ERROR = 12;

    private static final int MAX_DIGITS = 18;
    private static final byte[][] LITERALS = {
            {'t', 'r', 'u', 'e'}, {'f', 'a', 'l', 's', 'e'}, {'n', 'u', 'l', 'l'}};
    private static final int[] LITERAL_EVENTS = {TRUE, FALSE, NULL};

    private final boolean[] objectStack;
    private int depth;
    private int state = S_START;
    private byte current;
    private long number;
    private boolean negative;
    private int digits;
    private int literal;
    private int literalPos;
    private int pending;

    public JsonTokenizer(int maxDepth) {
        objectStack = new boolean[maxDepth];
    }

    public void reset() {
        depth = 0;
        state = S_START;
        pending = NONE;
    }

    public int feed(byte b) {
        pending = NONE;
        return step(b);
    }

    /** Second event produced by the last {@link #feed}, or NONE. Reading it clears it. */
    public int pending() {
        final int p = pending;
        pending = NONE;
        return p;
    }

    public byte current() {
        return current;
    }

    public long number() {
        return number;
    }

    public int depth() {
        return depth;
    }

    public boolean isComplete() {
        return state == S_DONE;
    }

    public boolean isError() {
        return state == S_ERROR;
    }

    private int step(byte b) {
        switch (state) {
            case S_START:
                if (isSpace(b)) {
                    return NONE;
                }
                if (b == '{') {
                    return open(true);
                }
                if (b == '[') {
                    return open(false);
                }
                return fail();
            case S_KEY_OR_END:
                if (isSpace(b)) {
                    return NONE;
                }
                if (b == '}') {
                    return close(OBJECT_END);
                }
                return keyStart(b);
            case S_KEY:
                if (isSpace(b)) {
                    return NONE;
                }
                return keyStart(b);
            case S_COLON:
                if (isSpace(b)) {
                    return NONE;
                }
                if (b == ':') {
                    state = S_VALUE;
                    return NONE;
                }
                return fail();
            case S_VALUE_OR_END:
                if (isSpace(b)) {
                    return NONE;
                }
                if (b == ']') {
                    return close(ARRAY_END);
                }
                return valueStart(b);
            case S_VALUE:
                if (isSpace(b)) {
                    return NONE;
                }
                return valueStart(b);
            case S_COMMA_OR_END:
                if (isSpace(b)) {
                    return NONE;
                }
                if (b == ',') {
                    state = objectStack[depth - 1] ? S_KEY : S_VALUE;
                    return NONE;
                }
                if (b == '}' && objectStack[depth - 1]) {
                    return close(OBJECT_END);
                }
                if (b == ']' && !objectStack[depth - 1]) {
                    return close(ARRAY_END);
                }
                return fail();
            case S_STRING:
            case S_KEY_STRING:
                return inString(b);
            case S_NUMBER:
                return inNumber(b);
            case S_LITERAL:
                return inLiteral(b);
            case S_DONE:
                return isSpace(b) ? NONE : fail();
            default:
                return ERROR;
        }
    }

    private int open(boolean object) {
        if (depth == objectStack.length) {
            return fail();
        }
        objectStack[depth++] = object;
        state = object ? S_KEY_OR_END : S_VALUE_OR_END;
        return object ? OBJECT_START : ARRAY_START;
    }

    private int close(int event) {
        depth--;
        state = depth == 0 ? S_DONE : S_COMMA_OR_END;
        return event;
    }

    private int keyStart(byte b) {
        if (b != '"') {
            return fail();
        }
        state = S_KEY_STRING;
        return KEY_START;
    }

    private int valueStart(byte b) {
        if (b == '"') {
            state = S_STRING;
            return STRING_START;
        }
        if (b == '{') {
            return open(true);
        }
        if (b == '[') {
            return open(false);
        }
        if (b == '-') {
            negative = true;
            number = 0;
            digits = 0;
            state = S_NUMBER;
            return NONE;
        }
        if (b >= '0' && b <= '9') {
            negative = false;
            number = b - '0';
            digits = 1;
            state = S_NUMBER;
            return NONE;
        }
        if (b == 't') {
            return literalStart(0);
        }
        if (b == 'f') {
            return literalStart(1);
        }
        if (b == 'n') {
            return literalStart(2);
        }
        return fail();
    }

    private int inString(byte b) {
        if (b == '"') {
            if (state == S_KEY_STRING) {
                state = S_COLON;
                return KEY_END;
            }
            state = S_COMMA_OR_END;
            return STRING_END;
        }
        // escapes and control characters (0x00-0x1F) are rejected; bytes >= 0x80 are negative
        if (b == '\\' || (b >= 0 && b < 0x20)) {
            return fail();
        }
        current = b;
        return CHAR;
    }

    private int inNumber(byte b) {
        if (b >= '0' && b <= '9') {
            if (digits == 1 && number == 0 || digits == MAX_DIGITS) {
                return fail();
            }
            number = number * 10 + (b - '0');
            digits++;
            return NONE;
        }
        if (digits == 0 || !(isSpace(b) || b == ',' || b == ']' || b == '}')) {
            return fail();
        }
        if (negative) {
            number = -number;
        }
        state = S_COMMA_OR_END;
        // re-dispatch the delimiter in the new state; its event (if any) becomes pending
        pending = step(b);
        return NUMBER;
    }

    private int literalStart(int which) {
        literal = which;
        literalPos = 1;
        state = S_LITERAL;
        return NONE;
    }

    private int inLiteral(byte b) {
        final byte[] word = LITERALS[literal];
        if (b != word[literalPos]) {
            return fail();
        }
        if (++literalPos == word.length) {
            state = S_COMMA_OR_END;
            return LITERAL_EVENTS[literal];
        }
        return NONE;
    }

    private int fail() {
        state = S_ERROR;
        return ERROR;
    }

    private static boolean isSpace(byte b) {
        return b == ' ' || b == '\t' || b == '\r' || b == '\n';
    }
}
