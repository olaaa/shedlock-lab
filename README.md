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

- `config` — провайдеры блокировок: `R2dbcLockProvider` с `usingDbTime()` и именем реплики в `locked_by`, `KeepAliveLockProvider` поверх него.
- `job` — задачи под `@SchedulerLock`, журнал запусков `job_run`, чтение таблицы `shedlock`.
- `web` — служебные ручки `/lab`.

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
