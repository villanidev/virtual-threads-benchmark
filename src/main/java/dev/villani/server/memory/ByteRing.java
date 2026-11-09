package dev.villani.server.memory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;

/**
 * Circular byte buffer over a pre-allocated (direct) ByteBuffer, used per connection to hold bytes that arrived
 * but are not parsed yet (partial requests, pipelined requests). Single-threaded, no per-operation allocation.
 * The backing buffer must be exclusively owned by the ring and its capacity must be a power of two.
 */
public final class ByteRing {

    /**
     * Guarda os bytes que chegaram e ainda não foram parseados.
     * A escrita é por putByte ou put(ByteBuffer), que copia só o que cabe e trata a volta no limite.
     * A leitura é por peek(offset), get() e skip(n).
     * readFrom(channel) lê do canal direto no espaço livre, com no máximo 2 leituras quando o espaço dá a volta. Devolve 0 se não há dados e -1 no fim do stream.
     * Cobre leitura parcial (request partido em várias leituras) e pipelining (o segundo request continua legível depois de consumir o primeiro).
     */

    private final ByteBuffer buf;
    private final int capacity;
    private final int mask;
    private int head;
    private int size;

    public ByteRing(ByteBuffer backing) {
        final int cap = backing.capacity();
        if (cap < 2 || Integer.bitCount(cap) != 1) {
            throw new IllegalArgumentException("capacity must be a power of two: " + cap);
        }
        this.buf = backing;
        this.capacity = cap;
        this.mask = cap - 1;
    }

    public int capacity() {
        return capacity;
    }

    public int readable() {
        return size;
    }

    public int writable() {
        return capacity - size;
    }

    /** Backing buffer, so a transport can receive straight into the free space (see {@link #tail()}). */
    public ByteBuffer backing() {
        return buf;
    }

    /** Offset in the backing buffer where the next byte is written. */
    public int tail() {
        return (head + size) & mask;
    }

    /** Free bytes starting at {@link #tail()} that do not wrap around the end of the backing buffer. */
    public int contiguousWritable() {
        return Math.min(capacity - size, capacity - tail());
    }

    /** Declares that {@code n} bytes were written by the transport at {@link #tail()}. */
    public void commit(int n) {
        if (n < 0 || n > contiguousWritable()) {
            throw new IllegalArgumentException("cannot commit " + n + ", contiguous free " + contiguousWritable());
        }
        size += n;
    }

    public void clear() {
        head = 0;
        size = 0;
    }

    /** @return false if the ring is full */
    public boolean putByte(byte value) {
        if (size == capacity) {
            return false;
        }
        buf.put((head + size) & mask, value);
        size++;
        return true;
    }

    /** Copies as much of {@code src} as fits and advances its position. @return the number of bytes copied */
    public int put(ByteBuffer src) {
        final int n = Math.min(src.remaining(), writable());
        final int tail = (head + size) & mask;
        final int first = Math.min(n, capacity - tail);
        buf.put(tail, src, src.position(), first);
        if (n > first) {
            buf.put(0, src, src.position() + first, n - first);
        }
        src.position(src.position() + n);
        size += n;
        return n;
    }

    /** Byte at {@code offset} from the oldest unread byte, without consuming it. */
    public byte peek(int offset) {
        if (offset < 0 || offset >= size) {
            throw new IndexOutOfBoundsException("offset " + offset + ", readable " + size);
        }
        return buf.get((head + offset) & mask);
    }

    /** Consumes and returns the oldest byte. */
    public byte get() {
        if (size == 0) {
            throw new IllegalStateException("ring is empty");
        }
        final byte b = buf.get(head);
        head = (head + 1) & mask;
        size--;
        return b;
    }

    /** Discards the {@code n} oldest bytes. */
    public void skip(int n) {
        if (n < 0 || n > size) {
            throw new IllegalArgumentException("cannot skip " + n + ", readable " + size);
        }
        head = (head + n) & mask;
        size -= n;
    }

    /**
     * Reads from the channel straight into the free space (at most two reads when the free space wraps).
     * @return bytes read, or -1 if the channel hit end-of-stream and nothing was read
     */
    public int readFrom(ReadableByteChannel channel) throws IOException {
        final int free = capacity - size;
        if (free == 0) {
            return 0;
        }
        final int tail = (head + size) & mask;
        final int firstLen = Math.min(free, capacity - tail);
        int total = readInto(channel, tail, firstLen);
        if (total < 0) {
            return -1;
        }
        if (total == firstLen && free > firstLen) {
            final int more = readInto(channel, 0, free - firstLen);
            if (more > 0) {
                total += more;
            }
        }
        return total;
    }

    private int readInto(ReadableByteChannel channel, int index, int length) throws IOException {
        buf.limit(index + length).position(index);
        try {
            final int n = channel.read(buf);
            if (n > 0) {
                size += n;
            }
            return n;
        } finally {
            buf.clear();
        }
    }
}
