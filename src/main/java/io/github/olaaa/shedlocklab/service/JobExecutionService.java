package io.github.olaaa.shedlocklab.service;

import java.time.Duration;

import io.github.olaaa.shedlocklab.config.LabProperties;
import io.github.olaaa.shedlocklab.repository.JobRunRepository;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/*
 * Общая «работа» задач: записать начало в журнал, поработать заданное время, записать конец.
 * Работа блокирующая и выполняется в потоке вызова: ShedLock держит блокировку, пока метод
 * с @SchedulerLock не вернул управление, — значит, вся работа должна закончиться внутри метода.
 */
@Service
public class JobExecutionService {

    private final JobRunRepository jobRunRepository;
    private final JobFaults jobFaults;
    private final String replicaName;

    public JobExecutionService(JobRunRepository jobRunRepository, JobFaults jobFaults, LabProperties labProperties) {
        this.jobRunRepository = jobRunRepository;
        this.jobFaults = jobFaults;
        this.replicaName = labProperties.replicaName();
    }

    public long run(String job, Duration workDuration) {
        long runId = jobRunRepository.start(job, replicaName).blockOptional().orElseThrow();
        try {
            Mono.delay(workDuration).block();
            if (jobFaults.takeFailure()) {
                throw new IllegalStateException(job + " упала на " + replicaName);
            }
            return runId;
        } finally {
            jobRunRepository.finish(runId).block();
        }
    }

    /* То же, но реактивно: ничего не делает, пока на результат не подписались. */
    public Mono<Long> runReactive(String job, Duration workDuration) {
        return jobRunRepository.start(job, replicaName)
                .flatMap(runId -> Mono.delay(workDuration)
                        .then(jobRunRepository.finish(runId))
                        .thenReturn(runId));
    }
}
