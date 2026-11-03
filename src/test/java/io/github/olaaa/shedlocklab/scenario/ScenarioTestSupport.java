package io.github.olaaa.shedlocklab.scenario;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import io.github.olaaa.shedlocklab.ShedlockLabApplication;
import io.github.olaaa.shedlocklab.TestcontainersConfiguration;
import io.github.olaaa.shedlocklab.model.JobRun;
import io.github.olaaa.shedlocklab.model.LockRow;
import io.github.olaaa.shedlocklab.repository.JobRunRepository;
import io.github.olaaa.shedlocklab.repository.LockRepository;
import io.github.olaaa.shedlocklab.service.JobFaults;
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
    JobRunRepository jobRunRepository;

    @Autowired
    LockRepository lockRepository;

    @Autowired
    JobFaults faultsOfReplicaA;

    private final List<ConfigurableApplicationContext> startedReplicas = new ArrayList<>();
    private final ExecutorService background = Executors.newVirtualThreadPerTaskExecutor();

    @BeforeEach
    void cleanDatabase() {
        faultsOfReplicaA.reset();
        jobRunRepository.clear().then(lockRepository.releaseAll()).block();
    }

    @AfterEach
    void stopReplicas() {
        background.shutdownNow();
        startedReplicas.forEach(ConfigurableApplicationContext::close);
    }

    /**
     * Поднимает вторую реплику сервиса — отдельный контекст Spring того же приложения в этой же JVM.
     * <p>
     * Тестовый контекст — реплика A. Реплика B получает свои бины: задачи со своим прокси ShedLock и свой
     * {@code R2dbcLockProvider} с {@code locked_by = replicaName}, — но работает с той же базой, что и A.
     * Поэтому обе соревнуются за одни и те же строки таблицы {@code shedlock}.
     * <ul>
     *     <li>Подключение к контейнеру PostgreSQL передаётся явно: {@code @ServiceConnection} настраивает только
     *     тестовый контекст.</li>
     *     <li>HTTP-сервер не поднимается ({@link WebApplicationType#NONE}): тестам нужны только бины задач.</li>
     *     <li>Профиль {@code scenario} — те же короткие сроки и выключенное расписание, что у реплики A.</li>
     *     <li>{@code schema.sql} не выполняется: таблицы уже создал контекст реплики A.</li>
     *     <li>Свойства передаются аргументами командной строки, а не через
     *     {@link SpringApplicationBuilder#properties(String...)}: те становятся значениями по умолчанию,
     *     и {@code lab.replica-name} из {@code application.yml} их перекрыл бы.</li>
     * </ul>
     * Контекст закрывается после теста в {@link #stopReplicas()}.
     * <p>
     * Пример: {@code startReplica("replica-b").getBean(CatalogExportJob.class)} — задача реплики B; её вызов
     * берёт блокировку от имени {@code replica-b}. Одинаковое с репликой A имя имитирует два процесса на одном
     * хосте с {@code locked_by} по умолчанию.
     *
     * @param replicaName имя реплики: значение {@code lab.replica-name}, попадает в {@code locked_by} и в журнал
     *                    запусков {@code job_run}
     * @return запущенный контекст реплики; бины задач берут из него через {@code getBean}
     */
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
        return jobRunRepository.runs().collectList().block();
    }

    LockRow lock(String name) {
        return lockRepository.find(name).block();
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
