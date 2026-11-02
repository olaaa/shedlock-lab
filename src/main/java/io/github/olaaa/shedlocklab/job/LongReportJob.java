package io.github.olaaa.shedlocklab.job;

import java.util.Optional;

import io.github.olaaa.shedlocklab.config.JobProperties;
import io.github.olaaa.shedlocklab.config.LockProviderConfiguration;
import net.javacrumbs.shedlock.core.LockAssert;
import net.javacrumbs.shedlock.spring.annotation.LockProviderToUse;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/*
 * Сценарий 3. Долгий отчёт, который может работать дольше lockAtMostFor.
 * build() — обычная блокировка: после lockAtMostFor её возьмёт другая реплика, и работать будут двое.
 * buildWithKeepAlive() — блокировка с продлением (KeepAliveLockProvider): пока задача идёт,
 * срок блокировки сдвигается вперёд.
 */
@Component
public class LongReportJob {

    public static final String LOCK_NAME = "long-report";
    public static final String KEEP_ALIVE_LOCK_NAME = "long-report-keep-alive";

    private final JobRunner jobRunner;
    private final JobProperties.LongReport properties;

    public LongReportJob(JobRunner jobRunner, JobProperties.LongReport properties) {
        this.jobRunner = jobRunner;
        this.properties = properties;
    }

    @Scheduled(cron = "${long-report.cron}")
    @SchedulerLock(name = LOCK_NAME, lockAtMostFor = "${long-report.lock-at-most-for}")
    public Optional<Long> build() {
        LockAssert.assertLocked();
        return Optional.of(jobRunner.run(LOCK_NAME, properties.workDuration()));
    }

    @Scheduled(cron = "${long-report.cron}")
    @LockProviderToUse(LockProviderConfiguration.KEEP_ALIVE_LOCK_PROVIDER)
    @SchedulerLock(name = KEEP_ALIVE_LOCK_NAME, lockAtMostFor = "${long-report.keep-alive-lock-at-most-for}")
    public Optional<Long> buildWithKeepAlive() {
        LockAssert.assertLocked();
        return Optional.of(jobRunner.run(KEEP_ALIVE_LOCK_NAME, properties.keepAliveWorkDuration()));
    }
}
