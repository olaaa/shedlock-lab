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

## Сценарии

Подробное описание — [docs/scenarios](docs/scenarios/README.md).

### 1. Задачу выполняет одна реплика

Тест: `Scenario1OneReplicaTest`. Подробно: [docs/scenarios/01-one-replica.md](docs/scenarios/01-one-replica.md), вручную: `http/01-one-replica.http`.

Две реплики запускают выгрузку одновременно — выполнила одна, вторая пропустила. После короткой задачи блокировка держится до `locked_at + lockAtLeastFor`. Ловушка: метод под `@SchedulerLock`, возвращающий `Mono`, не защищён — блокировка снята до подписки, выгружают обе. С `@Scheduled` на таком методе контекст не поднимается.

### 2. Держатель блокировки упал

Тест: `Scenario2HolderCrashTest`. Подробно: [docs/scenarios/02-holder-crash.md](docs/scenarios/02-holder-crash.md), вручную: `http/02-holder-crash.http`.

Блокировка упавшей реплики освобождается только через `lockAtMostFor`. Исключение в задаче — другое: блокировка снимается в `finally` и держится лишь до `locked_at + lockAtLeastFor`.

### 3. Задача дольше lockAtMostFor

Тест: `Scenario3TaskLongerThanLockTest`. Подробно: [docs/scenarios/03-task-longer-than-lock.md](docs/scenarios/03-task-longer-than-lock.md), вручную: `http/03-task-longer-than-lock.http`.

Срок истёк — вторая реплика берёт блокировку, работают двое. `locked_by` по умолчанию — имя хоста: две реплики с одинаковым `locked_by` снимают блокировку друг друга, и проходит третий запуск. `KeepAliveLockProvider` продлевает блокировку каждые `lockAtMostFor / 2`.
