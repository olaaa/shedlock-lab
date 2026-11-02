package io.github.olaaa.shedlocklab.controller;

import java.util.Optional;
import java.util.concurrent.Callable;

import io.github.olaaa.shedlocklab.config.LabProperties;
import io.github.olaaa.shedlocklab.job.CatalogExportJob;
import io.github.olaaa.shedlocklab.job.LongReportJob;
import io.github.olaaa.shedlocklab.model.JobRun;
import io.github.olaaa.shedlocklab.model.LockRow;
import io.github.olaaa.shedlocklab.repository.JobRunRepository;
import io.github.olaaa.shedlocklab.repository.LockRepository;
import io.github.olaaa.shedlocklab.service.JobFaults;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/*
 * Ручки для ручной проверки. Задачи блокирующие (см. JobExecutionService), а поток обработки запроса в WebFlux
 * блокировать нельзя, поэтому запуск уходит на boundedElastic.
 */
@RestController
@RequestMapping("/lab")
public class LabController {

    private final CatalogExportJob catalogExportJob;
    private final LongReportJob longReportJob;
    private final JobRunRepository jobRunRepository;
    private final LockRepository lockRepository;
    private final JobFaults jobFaults;
    private final String replicaName;

    public LabController(CatalogExportJob catalogExportJob, LongReportJob longReportJob, JobRunRepository jobRunRepository,
                         LockRepository lockRepository, JobFaults jobFaults, LabProperties labProperties) {
        this.catalogExportJob = catalogExportJob;
        this.longReportJob = longReportJob;
        this.jobRunRepository = jobRunRepository;
        this.lockRepository = lockRepository;
        this.jobFaults = jobFaults;
        this.replicaName = labProperties.replicaName();
    }

    @PostMapping("/jobs/catalog-export")
    public Mono<LaunchResult> catalogExport() {
        return launch(catalogExportJob::export);
    }

    @PostMapping("/jobs/long-report")
    public Mono<LaunchResult> longReport() {
        return launch(longReportJob::build);
    }

    @PostMapping("/jobs/long-report-keep-alive")
    public Mono<LaunchResult> longReportWithKeepAlive() {
        return launch(longReportJob::buildWithKeepAlive);
    }

    @GetMapping("/runs")
    public Flux<JobRun> runs() {
        return jobRunRepository.runs();
    }

    @GetMapping("/locks")
    public Flux<LockRow> locks() {
        return lockRepository.rows();
    }

    @PostMapping("/faults/fail-next-run")
    public Mono<FaultResult> failNextRun() {
        jobFaults.failNextRun();
        return Mono.just(new FaultResult(replicaName, true));
    }

    @PostMapping("/reset")
    public Mono<ResetResult> reset() {
        jobFaults.reset();
        return jobRunRepository.clear().then(lockRepository.releaseAll())
                .thenReturn(new ResetResult(replicaName, true));
    }

    private Mono<LaunchResult> launch(Callable<Optional<Long>> job) {
        return Mono.fromCallable(job)
                .subscribeOn(Schedulers.boundedElastic())
                .map(runId -> new LaunchResult(replicaName, runId.isPresent()));
    }

    /* executed — выполнила ли задачу эта реплика: false, если блокировку держит другая. */
    public record LaunchResult(String replica, boolean executed) {
    }

    public record ResetResult(String replica, boolean reset) {
    }

    public record FaultResult(String replica, boolean failNextRun) {
    }
}
