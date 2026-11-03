# shedlock-lab

Задача по расписанию на нескольких репликах и [ShedLock](https://github.com/lukas-krecan/ShedLock): выполняет одна реплика. WebFlux, R2DBC, PostgreSQL; каждый приём — отдельный тест с журналом запусков и взглядом в таблицу `shedlock`.

Связанные репозитории:

- [redis-patterns-lab](https://github.com/olaaa/redis-patterns-lab) — сценарий 3: та же задача (выгрузка каталога партнёру) под блокировкой с арендой, собранной вручную на Redis: `SET NX PX`, маркер, продление.

Здесь — готовая библиотека: ShedLock 7 (`shedlock-spring`, `shedlock-provider-r2dbc`) и таблица в PostgreSQL.

## Как запустить

Нужны Java 25 и Docker.

```bash
mvn test
```

Тесты поднимают PostgreSQL в контейнере Testcontainers. Тест сценария 3 с продлением идёт около 35 секунд: минимальный срок `KeepAliveLockProvider` — 30 секунд.

```bash
mvn spring-boot:run
mvn spring-boot:run -Dspring-boot.run.arguments=--server.port=8081
```

Spring Boot сам поднимет PostgreSQL из `compose.yaml`. Вторая команда — вторая реплика. Запросы для IntelliJ HTTP Client — в папке `http`: запуск задач `POST /lab/jobs/...`, журнал `GET /lab/runs`, таблица блокировок `GET /lab/locks`, сброс `POST /lab/reset`. Лог ShedLock — на уровне `DEBUG`.

## Устройство

Как идёт один запуск: `@Scheduled` срабатывает на каждой реплике и вызывает метод задачи, например `CatalogExportJob.export()`. Перед вызовом прокси ShedLock (его включает `@EnableSchedulerLock`) пытается занять строку в таблице `shedlock` через `R2dbcLockProvider`. Занял — метод выполняется: `JobExecutionService` пишет начало в `job_run`, «работает», пишет конец; потом прокси снимает блокировку. Не занял — метод не вызывается, прокси возвращает `Optional.empty()`.

С чего читать: `LockProviderConfiguration` → `CatalogExportJob` → `JobExecutionService` → `Scenario1OneReplicaTest`. Остальное — вспомогательное (журнал, таблица, контроллер) или вариации той же задачи для сценариев 2 и 3.

### Корень — `io.github.olaaa.shedlocklab`

- `ShedlockLabApplication` — точка входа. Включает расписание (`@EnableScheduling`), ShedLock (`@EnableSchedulerLock(defaultLockAtMostFor = "10m")`) и поиск классов настроек (`@ConfigurationPropertiesScan`).

### `config` — настройки и провайдеры блокировок

- `LockProviderConfiguration` — главная настройка ShedLock, два провайдера блокировок:
  - `lockProvider` — `R2dbcLockProvider` над таблицей `shedlock`: время базы (`usingDbTime()`), в `locked_by` — имя реплики. `@Primary`, им пользуются все задачи по умолчанию;
  - `keepAliveLockProvider` — тот же провайдер, обёрнутый в `KeepAliveLockProvider`: продлевает блокировку, пока задача идёт. Задача выбирает его через `@LockProviderToUse`.

  Вложенный `LockExtensionScheduler` — поток для продления, закрывается при остановке приложения. Бином типа `ScheduledExecutorService` он не сделан намеренно: такой бин Spring Boot принял бы за планировщик для всех `@Scheduled`.
- `LabProperties` — `lab.replica-name`, имя реплики (`replica-8080`, `replica-a`). Попадает в `locked_by` и в журнал запусков.
- `JobProperties` — сколько длится «работа» каждой задачи (`catalog-export.work-duration` и т. п.), по record'у на задачу. Сроки блокировок здесь не задаются: они прямо в `@SchedulerLock` через `${...}`.

Пакеты — по слоям: задачи по расписанию (`job`) вызывают сервисы (`service`), сервисы — репозитории (`repository`), репозитории возвращают модели (`model`); `controller` — вход по HTTP для ручной проверки.

### `job` — задачи по расписанию

- `CatalogExportJob` — выгрузка каталога партнёру, блокировка `catalog-export`. Сценарии 1 и 2. Возвращает `Optional`: так видно, выполнила ли задачу эта реплика.
- `LongReportJob` — долгий отчёт, сценарий 3. Два метода: `build()` — обычная блокировка `long-report`; `buildWithKeepAlive()` — с продлением, блокировка `long-report-keep-alive`.
- `ReactiveExportJob` — ловушка сценария 1, как не надо: метод возвращает `Mono`, и блокировка снимается раньше, чем начинается работа.

### `service` — работа задач

- `JobExecutionService` — «работа» всех задач: записать начало в журнал, подождать заданное время (имитация работы), записать конец. `run()` — блокирующий, его используют под ShedLock; `runReactive()` — реактивный вариант для ловушки.
- `JobFaults` — выключатель сбоя: следующий запуск на этой реплике бросит исключение. Для сценария 2 — показать, что исключение в задаче и падение процесса — разные вещи.

### `repository` — доступ к таблицам через `DatabaseClient`

- `JobRunRepository` — таблица `job_run`, журнал запусков: записать начало и конец запуска, прочитать все, очистить. По журналу видны дубли и наложения.
- `LockRepository` — таблица `shedlock`: только чтение, чтобы показать, что в ней лежит; пишет туда сам ShedLock. Плюс `releaseAll()` — освободить все блокировки `UPDATE`, без удаления строк: удалённую строку провайдер больше не создаст до перезапуска.

### `model` — строки таблиц

- `JobRun` — строка журнала: задача, реплика, начало, конец.
- `LockRow` — строка таблицы `shedlock` и поле `held`: занята ли блокировка сейчас.

### `controller` — ручки для ручной проверки

- `LabController` — `/lab/...`: запуск задач (`POST /lab/jobs/catalog-export`, `/long-report`, `/long-report-keep-alive`), журнал (`GET /lab/runs`), таблица блокировок (`GET /lab/locks`), сбой (`POST /lab/faults/fail-next-run`), сброс (`POST /lab/reset`). Задачи блокирующие, поэтому запускаются на `boundedElastic`, а не на потоке обработки запроса. Вложенные record'ы `LaunchResult`, `ResetResult`, `FaultResult` — JSON-ответы ручек.

### Ресурсы

- `schema.sql` — таблица `shedlock` (DDL из README ShedLock) и таблица `job_run`.
- `application.yml` — настройки для ручного запуска: выгрузка раз в минуту, сроки блокировок, лог ShedLock на уровне `DEBUG`.

### Тесты

- `TestcontainersConfiguration` — PostgreSQL в контейнере, подключение выставляется само.
- `ScenarioTestSupport` — общий родитель тестов. Тестовый контекст — реплика A; `startReplica(...)` поднимает реплику B — второй контекст того же приложения с той же базой. Ещё: очистка перед каждым тестом, запуск в фоне (`inBackground`), подсчёт одновременных запусков (`overlappingPairs`).
- `Scenario1OneReplicaTest`, `Scenario2HolderCrashTest`, `Scenario3TaskLongerThanLockTest` — тесты трёх сценариев.
- `application-scenario.yml` — короткие сроки для тестов (секунды), расписание выключено.

### Как тест поднимает вторую реплику

`ScenarioTestSupport.startReplica(...)` запускает то же приложение вторым контекстом Spring и передаёт ему свойства аргументами командной строки — теми же `String[] args`, что приходят в `main`:

```java
new SpringApplicationBuilder(ShedlockLabApplication.class)
        .web(WebApplicationType.NONE)
        .profiles("scenario")
        .run("--lab.replica-name=" + replicaName,
                "--spring.r2dbc.url=" + r2dbcUrl,
                "--spring.r2dbc.username=" + postgresContainer.getUsername(),
                "--spring.r2dbc.password=" + postgresContainer.getPassword(),
                "--spring.sql.init.mode=never");
```

Это то же самое, что `java -jar shedlock-lab.jar --lab.replica-name=replica-b ...` в терминале или `-Dspring-boot.run.arguments=--server.port=8081` при ручной проверке: каждый `--ключ=значение` Spring Boot превращает в свойство конфигурации.

| Аргумент | Кто читает | Зачем |
|---|---|---|
| `--lab.replica-name` | `LabProperties` | имя реплики: идёт в `locked_by` провайдера и в журнал `job_run` |
| `--spring.r2dbc.url` | автоконфигурация R2DBC Spring Boot | адрес той же базы, что у реплики A; по нему создаётся `ConnectionFactory` для `DatabaseClient` и `R2dbcLockProvider`. Передаётся явно: `@ServiceConnection` настраивает только тестовый контекст |
| `--spring.r2dbc.username`, `--spring.r2dbc.password` | она же | логин и пароль, которые сгенерировал контейнер Testcontainers |
| `--spring.sql.init.mode=never` | инициализация схемы Spring Boot | не выполнять `schema.sql` ещё раз: таблицы уже создал контекст реплики A |

Почему аргументы, а не `SpringApplicationBuilder.properties(...)`. У источников свойств в Spring Boot есть порядок старшинства: если ключ задан в нескольких источниках, берётся значение из самого старшего. Аргументы командной строки старше файлов `application.yml` и `application-<профиль>.yml`, а `properties(...)` задаёт значения по умолчанию — самый младший источник. С `properties(...)` строка `lab.replica-name: replica-${server.port:8080}` из `application.yml` перекрывала переданное имя, и реплика B записывалась как `replica-8080`.

Что значит «перекрывает»:

- перекрывается только этот ключ — остальные свойства из `application.yml` действуют как обычно;
- файл не меняется — Spring Boot для каждого ключа выбирает значение из старшего источника, в котором ключ есть;
- профиль подчиняется тому же правилу: `application-scenario.yml` старше `application.yml`, но младше аргументов. У реплики B сроки блокировок — из профиля, имя и адрес базы — из аргументов.

Источник: [Spring Boot — Externalized Configuration](https://docs.spring.io/spring-boot/reference/features/external-config.html).

## Сценарии

Подробное описание — [docs/scenarios](docs/scenarios/README.md).

### 1. Задачу выполняет одна реплика

Тест: `Scenario1OneReplicaTest`. Подробно: [docs/scenarios/01-one-replica.md](docs/scenarios/01-one-replica.md), вручную: `http/01-one-replica.http`.

Две реплики запускают выгрузку одновременно — выполнила одна, вторая пропустила. Блокировка работает на любом вызове метода через прокси — из `@Scheduled` или из контроллера. После короткой задачи блокировка держится до `locked_at + lockAtLeastFor`. Ловушка: метод под `@SchedulerLock`, возвращающий `Mono`, не защищён — блокировка снята до подписки, выгружают обе. С `@Scheduled` на таком методе контекст не поднимается.

### 2. Держатель блокировки упал

Тест: `Scenario2HolderCrashTest`. Подробно: [docs/scenarios/02-holder-crash.md](docs/scenarios/02-holder-crash.md), вручную: `http/02-holder-crash.http`.

Блокировка упавшей реплики освобождается только через `lockAtMostFor`. Исключение в задаче — другое: блокировка снимается в `finally` и держится лишь до `locked_at + lockAtLeastFor`.

### 3. Задача дольше lockAtMostFor

Тест: `Scenario3TaskLongerThanLockTest`. Подробно: [docs/scenarios/03-task-longer-than-lock.md](docs/scenarios/03-task-longer-than-lock.md), вручную: `http/03-task-longer-than-lock.http`.

Срок истёк — вторая реплика берёт блокировку, работают двое. `locked_by` по умолчанию — имя хоста: две реплики с одинаковым `locked_by` снимают блокировку друг друга, и проходит третий запуск. `KeepAliveLockProvider` продлевает блокировку каждые `lockAtMostFor / 2`.
