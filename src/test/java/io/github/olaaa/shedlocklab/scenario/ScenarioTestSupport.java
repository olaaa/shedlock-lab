package io.github.olaaa.shedlocklab.scenario;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Callable;

import io.github.olaaa.shedlocklab.ShedlockLabApplication;
import io.github.olaaa.shedlocklab.TestcontainersConfiguration;
import io.github.olaaa.shedlocklab.job.JobFaults;
import io.github.olaaa.shedlocklab.job.JobRun;
import io.github.olaaa.shedlocklab.job.JobRunLog;
import io.github.olaaa.shedlocklab.job.LockRow;
import io.github.olaaa.shedlocklab.job.LockTable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.postgresql.PostgreSQLContainer;

/*
 * Тестовый контекст — реплика A. Вторая реплика — отдельный контекст Spring того же приложения:
 * свои бины, свой провайдер блокировок со своим locked_by, но та же база в контейнере.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = "lab.replica-name=replica-a")
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("scenario")
abstract class ScenarioTestSupport {

    @Autowired
    PostgreSQLContainer postgresContainer;

    @Autowired
    JobRunLog jobRunLog;

    @Autowired
    LockTable lockTable;

    @Autowired
    JobFaults faultsOfReplicaA;

    private final List<ConfigurableApplicationContext> startedReplicas = new ArrayList<>();
    private final ExecutorService background = Executors.newVirtualThreadPerTaskExecutor();

    @BeforeEach
    void cleanDatabase() {
        faultsOfReplicaA.reset();
        jobRunLog.clear().then(lockTable.releaseAll()).block();
    }

    @AfterEach
    void stopReplicas() {
        background.shutdownNow();
        startedReplicas.forEach(ConfigurableApplicationContext::close);
    }

    ConfigurableApplicationContext startReplica(String replicaName) {
        String r2dbcUrl = "r2dbc:postgresql://%s:%d/%s".formatted(postgresContainer.getHost(),
                postgresContainer.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT),
                postgresContainer.getDatabaseName());
        /* Аргументы командной строки, а не properties(): те — значения по умолчанию, и application.yml их перекрыл бы. */
        ConfigurableApplicationContext replica = new SpringApplicationBuilder(ShedlockLabApplication.class)
                .web(WebApplicationType.NONE)
                .profiles("scenario")
                .run("--lab.replica-name=" + replicaName,
                        "--spring.r2dbc.url=" + r2dbcUrl,
                        "--spring.r2dbc.username=" + postgresContainer.getUsername(),
                        "--spring.r2dbc.password=" + postgresContainer.getPassword(),
                        "--spring.sql.init.mode=never");
        startedReplicas.add(replica);
        return replica;
    }

    <T> CompletableFuture<T> inBackground(Callable<T> task) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return task.call();
            } catch (Exception failure) {
                throw new IllegalStateException(failure);
            }
        }, background);
    }

    List<JobRun> runs() {
        return jobRunLog.runs().collectList().block();
    }

    LockRow lock(String name) {
        return lockTable.find(name).block();
    }

    /* Сколько пар запусков шли одновременно: начало одного раньше конца другого. */
    static long overlappingPairs(List<JobRun> runs) {
        long pairs = 0;
        for (int first = 0; first < runs.size(); first++) {
            for (int second = first + 1; second < runs.size(); second++) {
                JobRun one = runs.get(first);
                JobRun other = runs.get(second);
                boolean overlap = one.startedAt().isBefore(other.finishedAt())
                        && other.startedAt().isBefore(one.finishedAt());
                if (overlap) {
                    pairs++;
                }
            }
        }
        return pairs;
    }

    static Duration between(java.time.Instant from, java.time.Instant to) {
        return Duration.between(from, to);
    }
}
