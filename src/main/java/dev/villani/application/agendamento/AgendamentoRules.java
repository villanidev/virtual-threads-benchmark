package dev.villani.application.agendamento;

/** Business rules of the appointment domain, independent of HTTP and storage. */
public final class AgendamentoRules {

    public static final int MAX_SERVICO = 1_000;
    /** 2000-01-01T00:00:00Z */
    public static final long MIN_DATA_HORA = 946_684_800L;
    /** 2100-01-01T00:00:00Z */
    public static final long MAX_DATA_HORA = 4_102_444_800L;

    private AgendamentoRules() {
    }

    public static boolean isValid(int nomeLength, long servico, long dataHora) {
        return nomeLength >= 1 && nomeLength <= AgendamentoTable.MAX_NOME
                && servico >= 1 && servico <= MAX_SERVICO
                && dataHora >= MIN_DATA_HORA && dataHora < MAX_DATA_HORA;
    }
}
