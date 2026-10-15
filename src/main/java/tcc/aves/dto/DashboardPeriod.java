package tcc.aves.dto;

import java.time.LocalDate;
import java.util.Locale;

public enum DashboardPeriod {
    LAST_7_DAYS("7", 7),
    LAST_30_DAYS("30", 30),
    LAST_90_DAYS("90", 90),
    LAST_180_DAYS("180", 180),
    LAST_365_DAYS("365", 365),
    ALL_HISTORY("all", null);

    private final String requestValue;
    private final Integer days;

    DashboardPeriod(String requestValue, Integer days) {
        this.requestValue = requestValue;
        this.days = days;
    }

    public static DashboardPeriod fromRequest(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        for (DashboardPeriod period : values()) {
            if (period.requestValue.equals(normalized)) {
                return period;
            }
        }
        throw new IllegalArgumentException("Período do dashboard inválido");
    }

    public LocalDate startDate(LocalDate today) {
        return days == null ? null : today.minusDays(days - 1L);
    }
}
