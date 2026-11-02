package dev.villani.application.agendamento;

/** Date arithmetic without java.time objects. */
final class AgendamentoDias {

    private static final int[] DAYS_IN_MONTH = {31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31};

    private AgendamentoDias() {
    }

    /** Epoch day (UTC) of the 10 ASCII bytes {@code YYYY-MM-DD}, or Long.MIN_VALUE when invalid. */
    static long parseIsoDate(byte[] text, int length) {
        if (length != 10 || text[4] != '-' || text[7] != '-') {
            return Long.MIN_VALUE;
        }
        final int y = digits(text, 0, 4);
        final int m = digits(text, 5, 2);
        final int d = digits(text, 8, 2);
        if (y < 0 || m < 1 || m > 12 || d < 1) {
            return Long.MIN_VALUE;
        }
        final boolean leap = (y % 4 == 0 && y % 100 != 0) || y % 400 == 0;
        final int max = m == 2 && leap ? 29 : DAYS_IN_MONTH[m - 1];
        return d > max ? Long.MIN_VALUE : daysFromCivil(y, m, d);
    }

    private static int digits(byte[] text, int from, int n) {
        int v = 0;
        for (int i = from; i < from + n; i++) {
            final int c = text[i] - '0';
            if (c < 0 || c > 9) {
                return -1;
            }
            v = v * 10 + c;
        }
        return v;
    }

    /** Howard Hinnant's days_from_civil. */
    private static long daysFromCivil(int year, int month, int day) {
        final int y = month <= 2 ? year - 1 : year;
        final long era = (y >= 0 ? y : y - 399) / 400;
        final long yoe = y - era * 400;
        final long doy = (153L * (month + (month > 2 ? -3 : 9)) + 2) / 5 + day - 1;
        final long doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
        return era * 146_097 + doe - 719_468;
    }
}
