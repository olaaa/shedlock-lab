package io.github.olaaa.shedlocklab.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/* Сколько длится работа задач. Сроки блокировок задаются прямо в @SchedulerLock через ${...}. */
public final class JobProperties {

    private JobProperties() {
    }

    @ConfigurationProperties("catalog-export")
    public record CatalogExport(Duration workDuration) {
    }

    @ConfigurationProperties("long-report")
    public record LongReport(Duration workDuration, Duration keepAliveWorkDuration) {
    }

    @ConfigurationProperties("reactive-export")
    public record ReactiveExport(Duration workDuration) {
    }
}
