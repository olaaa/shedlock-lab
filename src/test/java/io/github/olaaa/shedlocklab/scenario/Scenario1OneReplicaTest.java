package io.github.olaaa.shedlocklab.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import io.github.olaaa.shedlocklab.job.CatalogExportJob;
import io.github.olaaa.shedlocklab.job.JobRun;
import io.github.olaaa.shedlocklab.job.LockRow;
import io.github.olaaa.shedlocklab.job.ReactiveExportJob;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import reactor.core.publisher.Mono;

/*
 * Сценарий 1. Расписание срабатывает на обеих репликах одновременно — выгрузку выполняет одна.
 * Сроки из application-scenario.yml: lockAtMostFor 6 с, lockAtLeastFor 3 с, работа 1 с.
 */
class Scenario1OneReplicaTest extends ScenarioTestSupport {

    @Autowired
    CatalogExportJob exportOnReplicaA;

    @Autowired
    ReactiveExportJob reactiveExportOnReplicaA;

    @Test
    void sameTickOnTwoReplicasExportsOnce() {
        CatalogExportJob exportOnReplicaB = startReplica("replica-b").getBean(CatalogExportJob.class);

        CompletableFuture<Optional<Long>> onReplicaA = inBackground(exportOnReplicaA::export);
        CompletableFuture<Optional<Long>> onReplicaB = inBackground(exportOnReplicaB::export);

        long executed = List.of(onReplicaA.join(), onReplicaB.join()).stream().filter(Optional::isPresent).count();
        List<JobRun> runs = runs();
        LockRow lockRow = lock(CatalogExportJob.LOCK_NAME);
        System.out.println("SC1 runs=" + runs + " lock=" + lockRow);

        assertThat(executed).as("выполнила одна реплика, вторая пропустила запуск").isEqualTo(1);
        assertThat(runs).hasSize(1);
        assertThat(lockRow.lockedBy()).isEqualTo(runs.getFirst().replica());
        assertThat(between(lockRow.lockedAt(), lockRow.lockUntil()))
                .as("после работы в 1 с блокировка держится до locked_at + lockAtLeastFor (3 с)")
                .isEqualTo(Duration.ofSeconds(3));
    }

    @Test
    void lockAtLeastForKeepsLockAfterShortTask() {
        CatalogExportJob exportOnReplicaB = startReplica("replica-b").getBean(CatalogExportJob.class);

        assertThat(exportOnReplicaA.export()).isPresent();
        LockRow afterFirstRun = lock(CatalogExportJob.LOCK_NAME);

        assertThat(afterFirstRun.held()).as("задача кончилась, но lockAtLeastFor ещё не прошёл").isTrue();
        assertThat(exportOnReplicaB.export()).as("реплика B сразу после — пропуск").isEmpty();

        await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(200))
                .until(() -> exportOnReplicaB.export().isPresent());
        List<JobRun> runs = runs();
        System.out.println("SC1 lockAtLeastFor runs=" + runs + " firstLock=" + afterFirstRun);

        assertThat(runs).hasSize(2);
        assertThat(runs.get(1).startedAt())
                .as("второй запуск — не раньше locked_at + lockAtLeastFor")
                .isAfterOrEqualTo(afterFirstRun.lockUntil());
    }

    @Test
    void methodReturningMonoIsNotProtected() {
        ReactiveExportJob reactiveExportOnReplicaB = startReplica("replica-b").getBean(ReactiveExportJob.class);

        Mono<Long> onReplicaA = reactiveExportOnReplicaA.export();
        LockRow afterCallReturned = lock(ReactiveExportJob.LOCK_NAME);
        Mono<Long> onReplicaB = reactiveExportOnReplicaB.export();

        Mono.zip(onReplicaA, onReplicaB).block();
        List<JobRun> runs = runs();
        System.out.println("SC1 reactive lockAfterReturn=" + afterCallReturned + " runs=" + runs);

        assertThat(afterCallReturned.held())
                .as("метод вернул Mono — блокировка уже снята, а работа ещё не начиналась")
                .isFalse();
        assertThat(runs).as("обе реплики выполнили выгрузку").hasSize(2);
        assertThat(overlappingPairs(runs)).as("и одновременно").isEqualTo(1);
    }
}
