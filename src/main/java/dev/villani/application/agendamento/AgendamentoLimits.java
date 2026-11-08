package dev.villani.application.agendamento;

/** Sizing shared by every Agendamento server variant, so all of them hold the same data and answer the same bytes. */
public final class AgendamentoLimits {

    public static final int DEFAULT_CONNECTIONS = 256;
    public static final int READ_BYTES = 4096;
    public static final int RESPONSE_BODY_BYTES = 8192;
    public static final int RECORDS = 200_000;
    public static final int DAYS = 4096;

    private AgendamentoLimits() {
    }
}
