package io.github.olaaa.shedlocklab.repository;

import java.time.Instant;

import io.github.olaaa.shedlocklab.model.JobRun;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/* Журнал запусков в таблице job_run. Время — clock_timestamp() базы, тех же часов, что у блокировок. */
@Repository
public class JobRunRepository {

    private final DatabaseClient databaseClient;

    public JobRunRepository(DatabaseClient databaseClient) {
        this.databaseClient = databaseClient;
    }

    public Mono<Long> start(String job, String replica) {
        return databaseClient.sql("""
                        INSERT INTO job_run (job, replica, started_at)
                        VALUES (:job, :replica, clock_timestamp())
                        RETURNING id
                        """)
                .bind("job", job)
                .bind("replica", replica)
                .map(row -> row.get("id", Long.class))
                .one();
    }

    public Mono<Void> finish(long runId) {
        return databaseClient.sql("""
                        UPDATE job_run
                        SET finished_at = clock_timestamp()
                        WHERE id = :id
                        """)
                .bind("id", runId)
                .then();
    }

    public Flux<JobRun> runs() {
        return databaseClient.sql("""
                        SELECT id, job, replica, started_at, finished_at
                        FROM job_run
                        ORDER BY id
                        """)
                .map(row -> new JobRun(
                        row.get("id", Long.class),
                        row.get("job", String.class),
                        row.get("replica", String.class),
                        row.get("started_at", Instant.class),
                        row.get("finished_at", Instant.class)))
                .all();
    }

    public Mono<Void> clear() {
        return databaseClient.sql("TRUNCATE job_run").then();
    }
}
