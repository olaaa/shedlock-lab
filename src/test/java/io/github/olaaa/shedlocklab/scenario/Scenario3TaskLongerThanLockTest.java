package io.github.olaaa.shedlocklab.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import io.github.olaaa.shedlocklab.job.JobRun;
import io.github.olaaa.shedlocklab.job.LockRow;
import io.github.olaaa.shedlocklab.job.LongReportJob;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/*
 * Сценарий 3. Задача работает дольше lockAtMostFor.
 * Сроки из application-scenario.yml: lockAtMostFor 2 с, работа 4 с; с продлением — 30 с и 35 с.
 */
class Scenario3TaskLongerThanLockTest extends ScenarioTestSupport {

    @Autowired
    LongReportJob reportOnReplicaA;

    @Test
    void secondReplicaTakesLockAfterLockAtMostForAndBothWork() {
        LongReportJob reportOnReplicaB = startReplica("replica-b").getBean(LongReportJob.class);

        CompletableFuture<Optional<Long>> onReplicaA = inBackground(reportOnReplicaA::build);
        awaitRunStarted();
        await().atMost(Duration.ofSeconds(5)).pollInterval(Duration.ofMillis(100))
                .until(() -> !lock(LongReportJob.LOCK_NAME).held());
        CompletableFuture<Optional<Long>> onReplicaB = inBackground(reportOnReplicaB::build);

        onReplicaA.join();
        LockRow afterReplicaAFinished = lock(LongReportJob.LOCK_NAME);
        boolean replicaBStillWorking = !onReplicaB.isDone();
        onReplicaB.join();
        List<JobRun> runs = runs();
        System.out.println("SC3 afterA=" + afterReplicaAFinished + " runs=" + runs);

        assertThat(onReplicaA.join()).isPresent();
        assertThat(onReplicaB.join()).as("lockAtMostFor истёк — реплика B взяла блокировку").isPresent();
        assertThat(overlappingPairs(runs)).as("отчёт строили двое одновременно").isEqualTo(1);
        assertThat(replicaBStillWorking).isTrue();
        assertThat(afterReplicaAFinished.lockedBy())
                .as("A закончила и сняла «свою» блокировку — но в строке уже B, и строка не тронута")
                .isEqualTo("replica-b");
        assertThat(afterReplicaAFinished.held()).isTrue();
    }

    @Test
    void sameLockedByLetsLateHolderReleaseForeignLock() {
        /* Вторая реплика с тем же именем — как два процесса на одном хосте при locked_by по умолчанию. */
        LongReportJob reportOnTwin = startReplica("replica-a").getBean(LongReportJob.class);

        CompletableFuture<Optional<Long>> first = inBackground(reportOnReplicaA::build);
        awaitRunStarted();
        await().atMost(Duration.ofSeconds(5)).pollInterval(Duration.ofMillis(100))
                .until(() -> !lock(LongReportJob.LOCK_NAME).held());
        CompletableFuture<Optional<Long>> onTwin = inBackground(reportOnTwin::build);

        first.join();
        LockRow afterFirstFinished = lock(LongReportJob.LOCK_NAME);
        Optional<Long> third = reportOnReplicaA.build();
        onTwin.join();
        List<JobRun> runs = runs();
        System.out.println("SC3 sameLockedBy afterFirst=" + afterFirstFinished + " third=" + third + " runs=" + runs);

        assertThat(afterFirstFinished.held())
                .as("первая сняла блокировку, которую уже держал «двойник»: locked_by одинаковый")
                .isFalse();
        assertThat(third).as("пока двойник работает, третий запуск проходит").isPresent();
        assertThat(runs).hasSize(3);
        assertThat(overlappingPairs(runs)).isEqualTo(2);
    }

    @Test
    void keepAliveExtendsLockWhileTaskRuns() {
        LongReportJob reportOnReplicaB = startReplica("replica-b").getBean(LongReportJob.class);

        CompletableFuture<Optional<Long>> onReplicaA = inBackground(reportOnReplicaA::buildWithKeepAlive);
        awaitRunStarted();
        LockRow taken = lock(LongReportJob.KEEP_ALIVE_LOCK_NAME);

        /* Ждём, пока исходный срок (30 с) пройдёт: без продления блокировка была бы уже свободна. */
        await().pollDelay(Duration.ofSeconds(31)).atMost(Duration.ofSeconds(32)).until(() -> true);
        LockRow afterOriginalDeadline = lock(LongReportJob.KEEP_ALIVE_LOCK_NAME);
        Optional<Long> onReplicaB = reportOnReplicaB.buildWithKeepAlive();

        onReplicaA.join();
        List<JobRun> runs = runs();
        System.out.println("SC3 keepAlive taken=" + taken + " after30s=" + afterOriginalDeadline + " runs=" + runs);

        assertThat(between(taken.lockedAt(), taken.lockUntil())).isEqualTo(Duration.ofSeconds(30));
        assertThat(afterOriginalDeadline.held()).as("блокировку продлили — она всё ещё занята").isTrue();
        assertThat(afterOriginalDeadline.lockUntil()).isAfter(taken.lockUntil());
        assertThat(onReplicaB).as("реплика B пропускает запуск").isEmpty();
        assertThat(runs).hasSize(1);
    }

    /* Ждём, пока первый запуск начался: строка блокировки от прошлых тестов уже есть, по ней не понять. */
    private void awaitRunStarted() {
        await().atMost(Duration.ofSeconds(5)).pollInterval(Duration.ofMillis(50)).until(() -> runs().size() == 1);
    }
}
