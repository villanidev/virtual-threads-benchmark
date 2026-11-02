package dev.villani.application.agendamento;

import dev.villani.testsupport.AllocationProbe;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("AgendamentoTable")
class AgendamentoTableTest {

    private static final long DAY = 86_400L;
    private static final long T0 = 1_800_000_000L - 1_800_000_000L % DAY; // aligned to a day start

    private static ByteBuffer nome(String text) {
        return ByteBuffer.wrap(text.getBytes(StandardCharsets.US_ASCII));
    }

    private static String nomeOf(AgendamentoTable table, int id) {
        final ByteBuffer out = ByteBuffer.allocate(AgendamentoTable.MAX_NOME);
        table.copyNome(id, out);
        return new String(out.array(), 0, out.position(), StandardCharsets.US_ASCII);
    }

    @Nested
    @DisplayName("create and read")
    class CreateAndRead {

        @Test
        @DisplayName("given an empty table, when creating, then ids are sequential and fields are stored")
        void givenEmptyTable_whenCreating_thenSequentialIds() {
            // given
            final AgendamentoTable table = new AgendamentoTable(8, 8);

            // when
            final int a = table.create(1, T0 + 100, nome("Ana"));
            final int b = table.create(2, T0 + 200, nome("Bia"));

            // then
            assertEquals(1, a);
            assertEquals(2, b);
            assertEquals("Bia", nomeOf(table, b));
            assertEquals(2, table.servico(b));
            assertEquals(T0 + 200, table.dataHora(b));
            assertEquals(AgendamentoTable.CONFIRMADO, table.status(b));
        }

        @Test
        @DisplayName("given appointments on one day, when listing the day, then they come newest first")
        void givenSameDay_whenListing_thenNewestFirst() {
            // given
            final AgendamentoTable table = new AgendamentoTable(8, 8);
            table.create(1, T0 + 100, nome("A"));
            table.create(1, T0 + 200, nome("B"));
            table.create(1, T0 + DAY + 100, nome("OtherDay"));

            // when
            final int first = table.firstOfDay((int) (T0 / DAY));
            final int second = table.nextOfDay(first);
            final int end = table.nextOfDay(second);

            // then
            assertEquals(2, first);
            assertEquals(1, second);
            assertEquals(0, end);
            assertEquals(0, table.firstOfDay((int) (T0 / DAY) + 5));
        }
    }

    @Nested
    @DisplayName("conflicts")
    class Conflicts {

        @Test
        @DisplayName("given a confirmed slot, when creating the same service and time, then CONFLICT")
        void givenConfirmedSlot_whenCreatingSame_thenConflict() {
            // given
            final AgendamentoTable table = new AgendamentoTable(8, 8);
            table.create(1, T0 + 100, nome("A"));

            // when
            final int result = table.create(1, T0 + 100, nome("B"));

            // then
            assertEquals(AgendamentoTable.CONFLICT, result);
            assertEquals(1, table.count());
        }

        @Test
        @DisplayName("given the same time for another service, when creating, then it is allowed")
        void givenOtherService_whenCreating_thenAllowed() {
            // given
            final AgendamentoTable table = new AgendamentoTable(8, 8);
            table.create(1, T0 + 100, nome("A"));

            // when / then
            assertEquals(2, table.create(2, T0 + 100, nome("B")));
        }

        @Test
        @DisplayName("given a cancelled slot, when creating the same service and time, then the slot is reusable")
        void givenCancelledSlot_whenCreatingSame_thenReusable() {
            // given
            final AgendamentoTable table = new AgendamentoTable(8, 8);
            final int a = table.create(1, T0 + 100, nome("A"));
            table.cancel(a);

            // when
            final int b = table.create(1, T0 + 100, nome("B"));

            // then
            assertEquals(2, b);
            assertEquals(AgendamentoTable.CONFLICT, table.create(1, T0 + 100, nome("C")));
        }
    }

    @Nested
    @DisplayName("cancelling")
    class Cancelling {

        @Test
        @DisplayName("given ids, when cancelling, then OK once, ALREADY_CANCELLED after, NOT_FOUND for unknown")
        void givenIds_whenCancelling_thenResults() {
            // given
            final AgendamentoTable table = new AgendamentoTable(8, 8);
            final int id = table.create(1, T0 + 100, nome("A"));

            // when / then
            assertEquals(AgendamentoTable.OK, table.cancel(id));
            assertEquals(AgendamentoTable.CANCELADO, table.status(id));
            assertEquals(AgendamentoTable.ALREADY_CANCELLED, table.cancel(id));
            assertEquals(AgendamentoTable.NOT_FOUND, table.cancel(99));
            assertEquals(AgendamentoTable.NOT_FOUND, table.cancel(0));
        }
    }

    @Nested
    @DisplayName("capacity")
    class Capacity {

        @Test
        @DisplayName("given a full table, when creating, then FULL")
        void givenFullTable_whenCreating_thenFull() {
            // given
            final AgendamentoTable table = new AgendamentoTable(2, 8);
            table.create(1, T0 + 1, nome("A"));
            table.create(1, T0 + 2, nome("B"));

            // when / then
            assertEquals(AgendamentoTable.FULL, table.create(1, T0 + 3, nome("C")));
        }

        @Test
        @DisplayName("given every day slot used, when creating on a new day, then DAYS_FULL and nothing changes")
        void givenAllDaysUsed_whenCreatingNewDay_thenDaysFull() {
            // given
            final AgendamentoTable table = new AgendamentoTable(16, 4);
            for (int d = 0; d < 4; d++) {
                assertTrue(table.create(1, T0 + d * DAY, nome("A")) > 0);
            }

            // when
            final int result = table.create(1, T0 + 4 * DAY, nome("B"));

            // then
            assertEquals(AgendamentoTable.DAYS_FULL, result);
            assertEquals(4, table.count());
            assertTrue(table.create(2, T0, nome("same day still works")) > 0);
        }

        @Test
        @DisplayName("given a name over the limit, when creating, then IllegalArgumentException")
        void givenLongName_whenCreating_thenRejected() {
            final AgendamentoTable table = new AgendamentoTable(2, 2);
            assertThrows(IllegalArgumentException.class, () -> table.create(1, T0, nome("x".repeat(41))));
        }
    }

    @Nested
    @DisplayName("allocation")
    class Allocation {

        @Test
        @DisplayName("given steady-state use, when creating, conflicting, cancelling and reading, then zero bytes are allocated")
        void givenSteadyState_whenUsing_thenZeroAlloc() {
            // given
            final AgendamentoTable table = new AgendamentoTable(300_000, 64);
            final ByteBuffer name = nome("Ana");
            final ByteBuffer out = ByteBuffer.allocateDirect(64);
            final long[] n = {0};
            final Runnable body = () -> {
                final int id = table.create(1, T0 + n[0]++ % 200_000, name);
                if (id > 0) {
                    out.clear();
                    table.copyNome(id, out);
                    table.firstOfDay((int) (T0 / DAY));
                    table.cancel(id);
                }
            };

            // when / then
            AllocationProbe.assertZeroAlloc(body, 100_000, 100_000);
        }
    }
}
