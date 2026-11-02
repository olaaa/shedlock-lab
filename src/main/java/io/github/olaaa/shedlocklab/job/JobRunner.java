package io.github.olaaa.shedlocklab.job;

import java.time.Duration;

import io.github.olaaa.shedlocklab.config.LabProperties;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/*
 * Общая «работа» задач: записать начало в журнал, поработать заданное время, записать конец.
 * Работа блокирующая и выполняется в потоке вызова: ShedLock держит блокировку, пока метод
 * с @SchedulerLock не вернул управление, — значит, вся работа должна закончиться внутри метода.
 */
@Component
public class JobRunner {

    private final JobRunLog jobRunLog;
    private final JobFaults jobFaults;
    private final String replicaName;

    public JobRunner(JobRunLog jobRunLog, JobFaults jobFaults, LabProperties labProperties) {
        this.jobRunLog = jobRunLog;
        this.jobFaults = jobFaults;
        this.replicaName = labProperties.replicaName();
    }

    public long run(String job, Duration workDuration) {
        long runId = jobRunLog.start(job, replicaName).blockOptional().orElseThrow();
        try {
            Mono.delay(workDuration).block();
            if (jobFaults.takeFailure()) {
                throw new IllegalStateException(job + " упала на " + replicaName);
            }
            return runId;
        } finally {
            jobRunLog.finish(runId).block();
        }
    }

    /* То же, но реактивно: ничего не делает, пока на результат не подписались. */
    public Mono<Long> runReactive(String job, Duration workDuration) {
        return jobRunLog.start(job, replicaName)
                .flatMap(runId -> Mono.delay(workDuration)
                        .then(jobRunLog.finish(runId))
                        .thenReturn(runId));
    }
}
