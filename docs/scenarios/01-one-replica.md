# Сценарий 1. Задачу по расписанию выполняет одна реплика

Тест: `src/test/java/io/github/olaaa/shedlocklab/scenario/Scenario1OneReplicaTest.java`.
Задача: `CatalogExportJob` — выгрузка каталога партнёру, блокировка `catalog-export`. Провайдер блокировок: `LockProviderConfiguration` (`R2dbcLockProvider`, таблица `shedlock`). Журнал запусков: `JobRunRepository` (таблица `job_run`). Настройки в `application.yml`: расписание — каждую минуту, `lock-at-most-for` = 1 минута, `lock-at-least-for` = 10 секунд, работа — 5 секунд. В тестах (`application-scenario.yml`): 6 секунд, 3 секунды и 1 секунда, расписание выключено.
Ручная проверка: `http/01-one-replica.http` на двух репликах.

Коротко:

- **Что делаем.** Выгрузку каталога партнёру запускает расписание `@Scheduled` на каждой реплике, а выполнить её должна одна. В [redis-patterns-lab](https://github.com/olaaa/redis-patterns-lab) (сценарий 3) такая блокировка собрана вручную на Redis; здесь — готовая библиотека ShedLock и таблица в PostgreSQL.
- **Как.** Над методом — `@SchedulerLock(name = "catalog-export")`. Перед вызовом прокси ShedLock пытается занять строку `catalog-export` в таблице `shedlock`. Занял — метод выполняется; не занял — вызов пропускается, без ожидания.
- **Сроки.** `lockAtMostFor` — страховка, если держатель упал (сценарий 2). `lockAtLeastFor` — блокировка держится не меньше этого срока, даже если задача кончилась раньше: иначе короткую задачу успела бы выполнить ещё и реплика, у которой расписание сработало чуть позже.
- **Ловушка WebFlux.** Метод с `@SchedulerLock` должен делать работу сам, а не возвращать `Mono`. Прокси снимает блокировку, как только метод вернул управление, а работа `Mono` начинается при подписке — уже без блокировки.

## Зачем блокировка

`@Scheduled` срабатывает на каждой реплике: три реплики — три выгрузки, причём одновременно. Партнёр получит три копии, а одновременные выгрузки перемешают файлы.

README ShedLock: «ShedLock гарантирует, что ваши задачи по расписанию выполняются не больше одного раза в одно и то же время. Если задача выполняется на одном узле, он берёт блокировку, которая не даёт выполнить ту же задачу на другом узле (или в другом потоке). Если задача уже выполняется на одном узле, на других её выполнение не ждёт, а просто пропускается».

И оговорка из того же README: «ShedLock — не полноценный планировщик и никогда им не будет, это просто блокировка». Расписание остаётся на каждой реплике; ShedLock только решает, кто из сработавших в этот раз выполняет.

## Как подключить

Зависимости — `shedlock-spring` (аннотации и прокси) и провайдер хранилища, здесь `shedlock-provider-r2dbc`. Стартера Spring Boot нет: всё включает аннотация.

```java
@SpringBootApplication
@EnableScheduling
@EnableSchedulerLock(defaultLockAtMostFor = "10m")
public class ShedlockLabApplication {
}
```

Провайдер — бин `LockProvider`:

```java
@Bean
@Primary
R2dbcLockProvider lockProvider(ConnectionFactory connectionFactory, LabProperties labProperties) {
    return new R2dbcLockProvider(R2dbcLockProvider.Configuration.builder(connectionFactory)
            .usingDbTime()
            .withLockedByValue(labProperties.replicaName())
            .build());
}
```

- **`usingDbTime()`** — сроки считает база по своим часам. README: «с `usingDbTime()` провайдер использует время UTC по часам сервера базы. Без этой опции используются часы сервера приложения (часы на серверах приложения могут быть не синхронизированы, что ведёт к разным проблемам с блокировками)», и эту опцию автор «настоятельно рекомендует». В README она показана для JDBC; конфигурация R2DBC наследует тот же построитель, и с ней ShedLock пишет в лог `Using PostgresSqlServerTimeStatementsSource`.
- **`withLockedByValue()`** — что писать в колонку `locked_by`. По умолчанию — имя хоста; почему это важно, — в сценарии 3.
- **R2DBC-провайдер** README называет экспериментальным: «возможности этого провайдера сильно ограничены». По исходнику (`R2dbcStorageAccessor`) он внутри блокирующий: запрос отправляется через R2DBC, а результат ждётся `CompletableFuture.get` с тайм-аутом 30 секунд. Для ShedLock это не помеха: задача и так блокирующая (см. «Ловушка»).

Таблица — DDL для PostgreSQL из README (раздел JdbcTemplate, на него ссылается провайдер R2DBC):

```sql
CREATE TABLE shedlock
(
    name       VARCHAR(64)  NOT NULL,
    lock_until TIMESTAMP    NOT NULL,
    locked_at  TIMESTAMP    NOT NULL,
    locked_by  VARCHAR(255) NOT NULL,
    PRIMARY KEY (name)
);
```

Задача:

```java
@Scheduled(cron = "${catalog-export.cron}")
@SchedulerLock(name = LOCK_NAME,
        lockAtMostFor = "${catalog-export.lock-at-most-for}",
        lockAtLeastFor = "${catalog-export.lock-at-least-for}")
public Optional<Long> export() {
    LockAssert.assertLocked();
    return Optional.of(jobRunner.run(LOCK_NAME, properties.workDuration()));
}
```

- Метод `public` и не `final`: прокси Spring AOP перехватывает только такие.
- `Optional` вместо `void` — для наглядности. По исходнику прокси (`MethodProxyScheduledLockAdvisor`) для метода, возвращающего `Optional`, при невзятой блокировке возвращается `Optional.empty()`; для остальных типов — `null`. Планировщик возвращаемое значение отбрасывает.
- `LockAssert.assertLocked()` бросает исключение, если метод вызван не под блокировкой: прокси не сработал, аннотация не та. README советует его как защиту от ошибок настройки AOP.

## Что лежит в таблице

ShedLock пишет в таблицу сам. По исходнику (`PostgresSqlServerTimeStatementsSource`) с `usingDbTime()` запросы такие — в сокращённой записи, `now` здесь `timezone('utc', CURRENT_TIMESTAMP)`; полностью, с параметрами и всеми вариантами — в [docs/shedlock-sql.md](../shedlock-sql.md):

```sql
INSERT INTO shedlock (name, lock_until, locked_at, locked_by)
VALUES (:name, now + :lockAtMostFor, now, :lockedBy)
ON CONFLICT (name) DO UPDATE
SET lock_until = now + :lockAtMostFor,
    locked_at = now,
    locked_by = :lockedBy
WHERE shedlock.name = :name
  AND shedlock.lock_until <= now
```

- **Взять.** Строки нет — вставка. Строка есть, но `lock_until` уже прошёл — обновление. Строка есть и `lock_until` в будущем — `WHERE` не пропускает, 0 строк: блокировку держит другой.
- **Снять** — не удалить строку, а сдвинуть `lock_until`:

```sql
UPDATE shedlock
SET lock_until = CASE
        WHEN locked_at + :lockAtLeastFor > now THEN locked_at + :lockAtLeastFor
        ELSE now
    END
WHERE name = :name
  AND locked_by = :lockedBy
```

Отсюда `lockAtLeastFor`: задача кончилась раньше — блокировка всё равно занята до `locked_at + lockAtLeastFor`.

Строка после выгрузки в тесте `sameTickOnTwoReplicasExportsOnce` (работа 1 секунда, `lockAtLeastFor` 3 секунды):

```text
name=catalog-export
locked_at  = 00:34:37.387
lock_until = 00:34:40.387   ← locked_at + 3 с
locked_by  = replica-a
```

## Что проверяют тесты

`sameTickOnTwoReplicasExportsOnce`: реплики A и B вызывают выгрузку одновременно — выполнила одна, вторая получила `Optional.empty()`; в журнале один запуск, `locked_by` — та, что выполнила; после работы `lock_until − locked_at` = 3 секунды, то есть `lockAtLeastFor`.

`lockAtLeastForKeepsLockAfterShortTask`: A выполнила выгрузку за 1 секунду, B сразу после — пропуск: блокировка держится до `locked_at + 3 с`. Второй запуск у B прошёл только после этого момента — в прогоне `lock_until` 00:34:36.095, начало второго запуска 00:34:36.224.

`methodReturningMonoIsNotProtected` — ловушка, см. ниже: обе реплики выполнили выгрузку, одновременно.

Вторая реплика в тестах — отдельный контекст Spring того же приложения со своим `lab.replica-name` и той же базой в Testcontainers.

## Ловушка: метод возвращает Mono

В проекте на WebFlux и R2DBC хочется написать задачу реактивно:

```java
@SchedulerLock(name = LOCK_NAME, lockAtMostFor = "1m")
public Mono<Long> export() {
    return jobRunner.runReactive(LOCK_NAME, properties.workDuration());
}
```

По исходнику прокси ShedLock (`MethodProxyScheduledLockAdvisor`) делает так: берёт блокировку, вызывает метод, снимает блокировку в `finally` и возвращает результат. Для реактивных типов особой обработки нет. Метод вернул `Mono` — описание работы, — и блокировка снята; сама работа начнётся при подписке. Это ровно различие момента сборки и момента подписки.

Тест `methodReturningMonoIsNotProtected`: сразу после вызова `export()` на реплике A строка блокировки уже свободна (`lock_until` = `locked_at` + 3 мс), хотя работа не начиналась; реплика B тоже получает `Mono`, обе подписки выполняют выгрузку, интервалы пересекаются.

С `@Scheduled` ещё хуже. Документация Spring Framework: для реактивного метода «Spring один раз получает `Publisher` аннотированного метода и планирует `Runnable`, который подписывается на этот `Publisher`» по расписанию. То есть метод — а с ним и прокси ShedLock — вызывается один раз, при создании бина, а каждое срабатывание расписания подписывается на уже полученный `Publisher`, мимо блокировки. В лаборатории `@Scheduled` с `@SchedulerLock` на таком методе не дал подняться контексту: «Cannot obtain a Publisher-convertible value from the @Scheduled reactive method». Почему вызов при старте не вернул `Publisher`, по логу не видно, — но и успешный вызов защищал бы только этот первый раз.

Вывод: метод под `@SchedulerLock` делает работу сам и возвращает управление, когда работа закончена. Здесь работа — `block()` внутри `JobExecutionService`: метод выполняется в потоке планировщика, блокировать его можно. Если запуск идёт из обработчика WebFlux, его уносят с потока обработки запросов (`LabController` — `subscribeOn(Schedulers.boundedElastic())`).

## Не только расписание: прямой вызов, например из контроллера

README ShedLock: «по умолчанию блокировка применяется и тогда, когда метод вызывают напрямую, а не только через планировщик». Аннотация `@SchedulerLock` срабатывает на любом вызове метода бина через прокси — из `@Scheduled`, из контроллера, из другого сервиса. В лаборатории так устроена ручная проверка: `LabController` вызывает тот же `CatalogExportJob.export()`, и вызов на второй реплике, пока первая работает, получает `{"executed":false}`.

Аннотация стоит не на методе контроллера, а на методе бина задачи: контроллер вызывает его через прокси. Так можно защитить любой публичный метод любого бина — например, ручку запуска долгого пересчёта из админки, чтобы два администратора не запустили его одновременно.

Что помнить при вызове из обработчика запроса:

- **Вызов изнутри того же бина не защищён.** `this.export()` идёт мимо прокси, блокировки нет. `LockAssert.assertLocked()` поймает это исключением.
- **Отказ — не ожидание.** Второй вызов не ждёт первого, а сразу пропускается и сам уже не выполнится. Клиенту нужен понятный ответ: в лаборатории — `{"executed":false}`, в настоящем API — `409 Conflict` или `202 Accepted` с пояснением.
- **В WebFlux — не на потоке обработки запросов.** И задача, и провайдер R2DBC блокирующие: по исходнику провайдер ждёт ответа базы до 30 секунд. Поэтому `LabController` уносит вызов на `boundedElastic`.
- **Метод заканчивает работу до возврата** — `Mono` блокировка не накрывает (см. «Ловушка»).

Чего ShedLock не заменяет: защиту запроса клиента от повтора. Он отбрасывает только одновременные вызовы и не помнит, что уже выполнено: повтор оплаты после завершения первой пройдёт. Для этого нужен ключ идемпотентности.

## Попробовать вручную

Две реплики в двух терминалах:

```bash
mvn spring-boot:run
mvn spring-boot:run -Dspring-boot.run.arguments=--server.port=8081
```

Почти одновременно запустить выгрузку на обеих — она длится 5 секунд:

```bash
curl -s -X POST localhost:8080/lab/reset
curl -s -X POST localhost:8080/lab/jobs/catalog-export & curl -s -X POST localhost:8081/lab/jobs/catalog-export & wait
curl -s localhost:8080/lab/locks
curl -s localhost:8080/lab/runs
```

Результат такого прогона:

```text
{"replica":"replica-8080","executed":true}
{"replica":"replica-8081","executed":false}
[{"name":"catalog-export","lockUntil":"2026-11-02T00:45:20.212580Z","lockedAt":"2026-11-02T00:45:10.212580Z","lockedBy":"replica-8080","held":true}]
[{"id":8,"job":"catalog-export","replica":"replica-8080","startedAt":"2026-11-02T00:45:10.215591Z","finishedAt":"2026-11-02T00:45:15.222826Z"}]
```

Выгрузила 8080, реплика 8081 пропустила запуск. Выгрузка шла 5 секунд (с 10.215 по 15.222), а блокировка занята до 20.212: `locked_at + 10 с`, это `lockAtLeastFor`.

По расписанию за две минуты — два запуска, в 00:46:00 на 8080 и в 00:47:00 на 8081: кто первым занял строку, тот и выполняет. В логах реплик:

```text
Locked 'catalog-export', lock will be held at most until 2026-11-02T00:47:00.001Z
Not executing 'catalog-export'. It's locked.
```

## Что важно заметить

- ShedLock — блокировка, а не планировщик: расписание срабатывает на каждой реплике, выполняет та, что заняла строку; остальные пропускают запуск, не ждут.
- Блокировка — строка таблицы с `lock_until`; снять — сдвинуть `lock_until`, а не удалить строку.
- `usingDbTime()` — сроки по часам базы, а не реплик.
- `lockAtLeastFor` не даёт короткой задаче выполниться повторно на реплике, где расписание сработало чуть позже.
- Метод под `@SchedulerLock` — блокирующий; метод, возвращающий `Mono`, не защищён.
- Блокировка работает на любом вызове метода через прокси, не только из `@Scheduled`: например, из контроллера. Второй одновременный вызов пропускается, а не ждёт.

## Как сказать на собеседовании

«Чтобы задачу по расписанию выполняла одна реплика, беру ShedLock: `@EnableSchedulerLock` и над методом `@SchedulerLock(name = ...)`. Это не планировщик, а блокировка, и привязана она не к расписанию, а к вызову метода через прокси: `@Scheduled` срабатывает везде — или ручку запуска вызывают на двух репликах, — прокси ShedLock пытается занять строку в таблице `shedlock` — `INSERT ... ON CONFLICT DO UPDATE ... WHERE lock_until <= now`; не занял — запуск пропускается. Ставлю `usingDbTime()`, чтобы сроки считала база, а не расходящиеся часы реплик. `lockAtMostFor` — с запасом относительно обычного времени работы, это страховка на случай падения; `lockAtLeastFor` — чтобы короткая задача не выполнилась ещё раз на реплике, где расписание сработало позже. В реактивном проекте помню: метод под `@SchedulerLock` должен закончить работу до возврата — `Mono` блокировка не накрывает. Тем же способом можно защитить ручку запуска долгой операции из контроллера: второй одновременный вызов не ждёт, а получает отказ. Но от повтора запроса клиента — например, оплаты — ShedLock не спасает: он не помнит, что уже выполнено, для этого ключ идемпотентности».

## Источники

- ShedLock — [README](https://github.com/lukas-krecan/ShedLock): разделы «ShedLock is not a distributed scheduler», «Annotate your scheduled tasks», «JdbcTemplate», «R2DBC», «Troubleshooting»; исходники 7.10.1: `MethodProxyScheduledLockAdvisor`, `DefaultLockingTaskExecutor`, `StorageBasedLockProvider`, `PostgresSqlServerTimeStatementsSource`, `R2dbcStorageAccessor`.
- Spring Framework — [Task Execution and Scheduling](https://docs.spring.io/spring-framework/reference/integration/scheduling.html), раздел «The @Scheduled annotation on Reactive methods or Kotlin suspending functions».
