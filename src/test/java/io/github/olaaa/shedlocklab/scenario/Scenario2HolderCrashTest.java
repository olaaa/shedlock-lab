package io.github.olaaa.shedlocklab.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import io.github.olaaa.shedlocklab.job.CatalogExportJob;
import io.github.olaaa.shedlocklab.model.JobRun;
import io.github.olaaa.shedlocklab.model.LockRow;
import net.javacrumbs.shedlock.core.ClockProvider;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.SimpleLock;
import net.javacrumbs.shedlock.provider.r2dbc.R2dbcLockProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/*
 * Сценарий 2. Держатель блокировки упал посреди задачи и не снял её.
 * «Падение» — блокировка взята провайдером реплики A с теми же сроками, что у выгрузки, и не снята:
 * ровно это остаётся в таблице, когда процесс умирает между lock() и unlock().
 */
class Scenario2HolderCrashTest extends ScenarioTestSupport {

    private static final Duration LOCK_AT_MOST_FOR = Duration.ofSeconds(6);
    private static final Duration LOCK_AT_LEAST_FOR = Duration.ofSeconds(3);

    @Autowired
    R2dbcLockProvider lockProviderOfReplicaA;

    @Autowired
    CatalogExportJob exportOnReplicaA;

    @Test
    void lockOfCrashedHolderIsReleasedOnlyAfterLockAtMostFor() {
        CatalogExportJob exportOnReplicaB = startReplica("replica-b").getBean(CatalogExportJob.class);

        Optional<SimpleLock> taken = lockProviderOfReplicaA.lock(new LockConfiguration(
                ClockProvider.now(), CatalogExportJob.LOCK_NAME, LOCK_AT_MOST_FOR, LOCK_AT_LEAST_FOR));
        /* unlock() не вызываем: реплика A «упала». */
        LockRow crashedLock = lock(CatalogExportJob.LOCK_NAME);

        assertThat(taken).isPresent();
        assertThat(crashedLock.lockedBy()).isEqualTo("replica-a");
        assertThat(exportOnReplicaB.export()).as("блокировка висит — реплика B пропускает запуск").isEmpty();

        await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(200))
                .until(() -> exportOnReplicaB.export().isPresent());
        List<JobRun> runs = runs();
        System.out.println("SC2 crashedLock=" + crashedLock + " runs=" + runs + " now=" + lock(CatalogExportJob.LOCK_NAME));

        assertThat(between(crashedLock.lockedAt(), crashedLock.lockUntil())).isEqualTo(LOCK_AT_MOST_FOR);
        assertThat(runs).hasSize(1);
        assertThat(runs.getFirst().replica()).isEqualTo("replica-b");
        assertThat(runs.getFirst().startedAt())
                .as("реплика B выполнила выгрузку только после lock_until упавшей блокировки")
                .isAfterOrEqualTo(crashedLock.lockUntil());
    }

    @Test
    void failedTaskReleasesLockWithoutWaitingForLockAtMostFor() {
        faultsOfReplicaA.failNextRun();

        assertThatThrownBy(exportOnReplicaA::export).hasMessageContaining("упала");
        LockRow afterFailure = lock(CatalogExportJob.LOCK_NAME);
        System.out.println("SC2 afterFailure=" + afterFailure);

        assertThat(between(afterFailure.lockedAt(), afterFailure.lockUntil()))
                .as("исключение — блокировка снята в finally: lock_until = locked_at + lockAtLeastFor, а не + lockAtMostFor")
                .isEqualTo(LOCK_AT_LEAST_FOR);
    }
}
