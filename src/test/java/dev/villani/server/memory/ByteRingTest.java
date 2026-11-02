package dev.villani.server.memory;

import dev.villani.testsupport.AllocationProbe;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Pipe;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ByteRing")
class ByteRingTest {

    private static ByteRing ringOf(int capacity) {
        return new ByteRing(ByteBuffer.allocateDirect(capacity));
    }

    private static ByteBuffer bytes(String text) {
        return ByteBuffer.wrap(text.getBytes(StandardCharsets.US_ASCII));
    }

    private static String drain(ByteRing ring) {
        final StringBuilder sb = new StringBuilder();
        while (ring.readable() > 0) {
            sb.append((char) ring.get());
        }
        return sb.toString();
    }

    @Nested
    @DisplayName("put and get")
    class PutAndGet {

        @Test
        @DisplayName("given bytes put in, when reading them, then they come out FIFO")
        void givenBytesPut_whenReading_thenFifo() {
            // given
            final ByteRing ring = ringOf(16);

            // when
            ring.put(bytes("GET /"));

            // then
            assertEquals(5, ring.readable());
            assertEquals('G', ring.peek(0));
            assertEquals("GET /", drain(ring));
        }

        @Test
        @DisplayName("given a ring near its end, when putting across the boundary, then wrap-around keeps byte order")
        void givenRingNearEnd_whenPuttingAcrossBoundary_thenOrderKept() {
            // given
            final ByteRing ring = ringOf(8);
            ring.put(bytes("abcdef"));
            ring.skip(6);

            // when
            ring.put(bytes("123456"));

            // then
            assertEquals("123456", drain(ring));
        }

        @Test
        @DisplayName("given a nearly full ring, when putting more than fits, then only what fits is copied")
        void givenNearlyFullRing_whenPuttingMore_thenPartialCopy() {
            // given
            final ByteRing ring = ringOf(4);
            final ByteBuffer src = bytes("abcdef");

            // when
            final int copied = ring.put(src);

            // then
            assertEquals(4, copied);
            assertEquals(2, src.remaining());
            assertFalse(ring.putByte((byte) 'x'));
            assertEquals("abcd", drain(ring));
        }

        @Test
        @DisplayName("given an empty ring, when reading or peeking past the data, then it is rejected")
        void givenEmptyRing_whenReadingPastData_thenRejected() {
            // given
            final ByteRing ring = ringOf(8);

            // when / then
            assertThrows(IllegalStateException.class, ring::get);
            assertThrows(IndexOutOfBoundsException.class, () -> ring.peek(0));
        }
    }

    @Nested
    @DisplayName("pipelining")
    class Pipelining {

        @Test
        @DisplayName("given two requests in one read, when consuming the first, then the second stays readable")
        void givenTwoRequests_whenConsumingFirst_thenSecondRemains() {
            // given
            final ByteRing ring = ringOf(64);
            ring.put(bytes("GET /a\r\n\r\nGET /b\r\n\r\n"));

            // when
            ring.skip(10);

            // then
            assertEquals("GET /b\r\n\r\n", drain(ring));
        }

        @Test
        @DisplayName("given a request split in two reads, when the second part arrives, then both parts are contiguous")
        void givenSplitRequest_whenSecondPartArrives_thenContiguous() {
            // given
            final ByteRing ring = ringOf(32);
            ring.put(bytes("POST /api/agen"));

            // when
            ring.put(bytes("damentos HTTP/1.1"));

            // then
            assertEquals("POST /api/agendamentos HTTP/1.1", drain(ring));
        }

        @Test
        @DisplayName("given a negative or oversized skip, when skipping, then it is rejected")
        void givenInvalidSkip_whenSkipping_thenRejected() {
            // given
            final ByteRing ring = ringOf(8);
            ring.put(bytes("abc"));

            // when / then
            assertThrows(IllegalArgumentException.class, () -> ring.skip(4));
            assertThrows(IllegalArgumentException.class, () -> ring.skip(-1));
        }
    }

    @Nested
    @DisplayName("channel read")
    class ChannelRead {

        @Test
        @DisplayName("given data on a channel and a wrapped free area, when reading, then both segments are filled in order")
        void givenWrappedFreeArea_whenReadingFromChannel_thenBytesInOrder() throws IOException {
            // given
            final Pipe pipe = Pipe.open();
            pipe.source().configureBlocking(false);
            final ByteRing ring = ringOf(8);
            ring.put(bytes("abcdef"));
            ring.skip(6);
            pipe.sink().write(bytes("12345678"));

            // when
            final int read = ring.readFrom(pipe.source());

            // then
            assertEquals(8, read);
            assertEquals("12345678", drain(ring));
        }

        @Test
        @DisplayName("given nothing available on a non-blocking channel, when reading, then zero bytes are read")
        void givenNoData_whenReadingFromChannel_thenZero() throws IOException {
            // given
            final Pipe pipe = Pipe.open();
            pipe.source().configureBlocking(false);
            final ByteRing ring = ringOf(8);

            // when
            final int read = ring.readFrom(pipe.source());

            // then
            assertEquals(0, read);
            assertEquals(0, ring.readable());
        }

        @Test
        @DisplayName("given a closed channel, when reading, then end-of-stream is reported")
        void givenClosedChannel_whenReading_thenEndOfStream() throws IOException {
            // given
            final Pipe pipe = Pipe.open();
            pipe.source().configureBlocking(false);
            pipe.sink().close();
            final ByteRing ring = ringOf(8);

            // when
            final int read = ring.readFrom(pipe.source());

            // then
            assertEquals(-1, read);
        }

        @Test
        @DisplayName("given a full ring, when reading from a channel, then nothing is consumed from the channel")
        void givenFullRing_whenReadingFromChannel_thenNothingRead() throws IOException {
            // given
            final Pipe pipe = Pipe.open();
            pipe.source().configureBlocking(false);
            final ByteRing ring = ringOf(4);
            ring.put(bytes("abcd"));
            pipe.sink().write(bytes("zz"));

            // when
            final int read = ring.readFrom(pipe.source());

            // then
            assertEquals(0, read);
            assertEquals("abcd", drain(ring));
        }
    }

    @Nested
    @DisplayName("construction")
    class Construction {

        @Test
        @DisplayName("given a capacity that is not a power of two, when creating a ring, then it is rejected")
        void givenNonPowerOfTwo_whenCreating_thenRejected() {
            // when / then
            assertThrows(IllegalArgumentException.class, () -> ringOf(12));
        }
    }

    @Nested
    @DisplayName("allocation")
    class Allocation {

        @Test
        @DisplayName("given a warmed-up ring, when putting, peeking, consuming and clearing, then nothing is allocated")
        void givenWarmRing_whenCycling_thenZeroAllocation() {
            // given
            final ByteRing ring = ringOf(4096);
            final ByteBuffer src = ByteBuffer.allocateDirect(1000);
            final int[] sink = new int[1];
            final Runnable cycle = () -> {
                src.clear();
                ring.put(src);
                for (int i = 0; i < 1000; i++) {
                    sink[0] += ring.peek(i);
                }
                ring.skip(500);
                while (ring.readable() > 0) {
                    sink[0] += ring.get();
                }
                ring.clear();
            };

            // when / then
            AllocationProbe.assertZeroAlloc(cycle, 20_000, 2_000);
        }
    }
}
