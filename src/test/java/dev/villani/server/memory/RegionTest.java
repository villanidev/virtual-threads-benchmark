package dev.villani.server.memory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Region (direct and FFM backends)")
class RegionTest {

    @ParameterizedTest(name = "ffm={0}")
    @ValueSource(booleans = {false, true})
    @DisplayName("given a fresh region, when reading, then it is zeroed and has the requested size")
    void givenFreshRegion_whenReading_thenZeroedWithSize(boolean ffm) {
        // given
        final Region region = Regions.allocate(256, ffm);

        // when
        final long size = region.size();

        // then
        assertEquals(256, size);
        assertEquals(0, region.getInt(0));
        assertEquals(0L, region.getLong(248));
        assertEquals(0, region.get(255));
    }

    @ParameterizedTest(name = "ffm={0}")
    @ValueSource(booleans = {false, true})
    @DisplayName("given values written at unaligned offsets, when reading back, then they round-trip")
    void givenValuesWritten_whenReadingBack_thenRoundTrip(boolean ffm) {
        // given
        final Region region = Regions.allocate(64, ffm);

        // when
        region.putInt(5, 0xCAFEBABE);
        region.putLong(13, Long.MIN_VALUE + 7);
        region.put(63, (byte) -3);

        // then
        assertEquals(0xCAFEBABE, region.getInt(5));
        assertEquals(Long.MIN_VALUE + 7, region.getLong(13));
        assertEquals((byte) -3, region.get(63));
    }

    @ParameterizedTest(name = "ffm={0}")
    @ValueSource(booleans = {false, true})
    @DisplayName("given a region, when viewed as a ByteBuffer, then both share the same memory in native order")
    void givenRegion_whenViewedAsByteBuffer_thenSharesMemory(boolean ffm) {
        // given
        final Region region = Regions.allocate(32, ffm);
        final ByteBuffer view = region.asByteBuffer();

        // when
        region.putInt(8, 1234567);
        view.putInt(16, 7654321);

        // then
        assertEquals(1234567, view.getInt(8));
        assertEquals(7654321, region.getInt(16));
        assertEquals(32, view.capacity());
        assertTrue(view.isDirect());
    }

    @ParameterizedTest(name = "ffm={0}")
    @ValueSource(booleans = {false, true})
    @DisplayName("given an access past the end, when reading or writing, then it fails instead of corrupting memory")
    void givenOutOfBounds_whenAccessing_thenThrows(boolean ffm) {
        // given
        final Region region = Regions.allocate(16, ffm);

        // when / then
        assertThrows(RuntimeException.class, () -> region.getInt(14));
        assertThrows(RuntimeException.class, () -> region.putLong(10, 1L));
    }
}
