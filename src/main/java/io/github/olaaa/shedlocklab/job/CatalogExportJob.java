package io.github.olaaa.shedlocklab.job;

import java.util.Optional;

import io.github.olaaa.shedlocklab.config.JobProperties;
import io.github.olaaa.shedlocklab.service.JobExecutionService;
import net.javacrumbs.shedlock.core.LockAssert;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/*
 * Сценарии 1 и 2. Выгрузка каталога партнёру: расписание есть на каждой реплике, выполняет одна.
 *
 * Метод возвращает Optional: прокси ShedLock для такого метода возвращает Optional.empty(), если
 * блокировку взять не удалось и метод не вызывался. Так тест и контроллер видят, кто выполнил запуск.
 * Планировщик возвращаемое значение просто отбрасывает.
 */
@Component
public class CatalogExportJob {

    public static final String LOCK_NAME = "catalog-export";

    private final JobExecutionService jobExecutionService;
    private final JobProperties.CatalogExport properties;

    public CatalogExportJob(JobExecutionService jobExecutionService, JobProperties.CatalogExport properties) {
        this.jobExecutionService = jobExecutionService;
        this.properties = properties;
    }

    @Scheduled(cron = "${catalog-export.cron}")
    @SchedulerLock(name = LOCK_NAME,
            lockAtMostFor = "${catalog-export.lock-at-most-for}",
            lockAtLeastFor = "${catalog-export.lock-at-least-for}")
    public Optional<Long> export() {
        /* Бросит исключение, если метод вызван не под блокировкой: прокси не сработал, аннотацию забыли. */
        LockAssert.assertLocked();
        return Optional.of(jobExecutionService.run(LOCK_NAME, properties.workDuration()));
    }
}
