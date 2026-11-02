package io.github.olaaa.shedlocklab.web;

import java.util.Optional;
import java.util.concurrent.Callable;

import io.github.olaaa.shedlocklab.config.LabProperties;
import io.github.olaaa.shedlocklab.job.CatalogExportJob;
import io.github.olaaa.shedlocklab.job.JobFaults;
import io.github.olaaa.shedlocklab.job.JobRun;
import io.github.olaaa.shedlocklab.job.JobRunLog;
import io.github.olaaa.shedlocklab.job.LockRow;
import io.github.olaaa.shedlocklab.job.LockTable;
import io.github.olaaa.shedlocklab.job.LongReportJob;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/*
 * Ручки для ручной проверки. Задачи блокирующие (см. JobRunner), а поток обработки запроса в WebFlux
 * блокировать нельзя, поэтому запуск уходит на boundedElastic.
 */
@RestController
@RequestMapping("/lab")
public class LabController {

    private final CatalogExportJob catalogExportJob;
    private final LongReportJob longReportJob;
    private final JobRunLog jobRunLog;
    private final LockTable lockTable;
    private final JobFaults jobFaults;
    private final String replicaName;

    public LabController(CatalogExportJob catalogExportJob, LongReportJob longReportJob, JobRunLog jobRunLog,
                         LockTable lockTable, JobFaults jobFaults, LabProperties labProperties) {
        this.catalogExportJob = catalogExportJob;
        this.longReportJob = longReportJob;
        this.jobRunLog = jobRunLog;
        this.lockTable = lockTable;
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
        return jobRunLog.runs();
    }

    @GetMapping("/locks")
    public Flux<LockRow> locks() {
        return lockTable.rows();
    }

    @PostMapping("/faults/fail-next-run")
    public Mono<FaultResult> failNextRun() {
        jobFaults.failNextRun();
        return Mono.just(new FaultResult(replicaName, true));
    }

    @PostMapping("/reset")
    public Mono<ResetResult> reset() {
        jobFaults.reset();
        return jobRunLog.clear().then(lockTable.releaseAll())
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
