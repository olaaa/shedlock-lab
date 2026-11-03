# SQL-запросы ShedLock к таблице shedlock

Какие запросы ShedLock отправляет в PostgreSQL, когда берёт, продлевает и снимает блокировку. Запросы — из исходников ShedLock 7.10.1, класс `PostgresSqlServerTimeStatementsSource`: его выбирает провайдер с `usingDbTime()`, как в этой лаборатории (в логе — `Using PostgresSqlServerTimeStatementsSource`).

Коротко:

- **Блокировка — строка таблицы.** Одна строка на имя из `@SchedulerLock`; занята, пока `lock_until` в будущем.
- **Время — у базы.** Везде `timezone('utc', CURRENT_TIMESTAMP)`: сроки считаются по часам PostgreSQL, а не реплик. Колонки — `TIMESTAMP` без зоны с временем UTC.
- **Взял или нет — по числу затронутых строк.** 1 строка — блокировка взята (продлена, снята), 0 — нет.
- **Строки не удаляются никогда.** Снять блокировку — сдвинуть `lock_until`.

## Таблица

DDL для PostgreSQL из README ShedLock (раздел JdbcTemplate; провайдер R2DBC ссылается на него), в лаборатории — `schema.sql`:

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

Первичный ключ обязателен: на нём держится `ON CONFLICT (name)`, без него две реплики вставят по своей строке.

## Параметры

| Параметр | Откуда | Пример |
|---|---|---|
| `:name` | `name` в `@SchedulerLock` | `catalog-export` |
| `:lockedBy` | `withLockedByValue(...)`; по умолчанию — имя хоста | `replica-8080` |
| `:lockAtMostForInterval` | `lockAtMostFor`, в секундах, `BigDecimal` с миллисекундами | `60.000` для `1m` |
| `:lockAtLeastForInterval` | `lockAtLeastFor`, так же | `10.000` для `10s` |

Провайдер R2DBC заменяет именованные параметры на позиционные (`$1`, `$2`, …) — так их принимает драйвер PostgreSQL.

## Взять блокировку: первая попытка

```sql
INSERT INTO shedlock (name, lock_until, locked_at, locked_by)
VALUES (:name,
        timezone('utc', CURRENT_TIMESTAMP) + make_interval(secs => :lockAtMostForInterval),
        timezone('utc', CURRENT_TIMESTAMP),
        :lockedBy)
ON CONFLICT (name) DO UPDATE
SET lock_until = timezone('utc', CURRENT_TIMESTAMP) + make_interval(secs => :lockAtMostForInterval),
    locked_at = timezone('utc', CURRENT_TIMESTAMP),
    locked_by = :lockedBy
WHERE shedlock.name = :name
  AND shedlock.lock_until <= timezone('utc', CURRENT_TIMESTAMP)
```

- **Строки нет** — обычная вставка: блокировка занята до `now + lockAtMostFor`, 1 строка.
- **Строка есть, `lock_until` прошёл** (прошлая блокировка истекла или снята) — `ON CONFLICT` превращает вставку в обновление, строка переходит к новому держателю, 1 строка.
- **Строка есть, `lock_until` в будущем** — `WHERE` не пропускает обновление, 0 строк: блокировку держит другой, метод не вызывается.

Пример с подставленными значениями — реплика 8080 берёт `catalog-export` на минуту:

```sql
INSERT INTO shedlock (name, lock_until, locked_at, locked_by)
VALUES ('catalog-export',
        timezone('utc', CURRENT_TIMESTAMP) + make_interval(secs => 60.000),
        timezone('utc', CURRENT_TIMESTAMP),
        'replica-8080')
ON CONFLICT (name) DO UPDATE
SET lock_until = timezone('utc', CURRENT_TIMESTAMP) + make_interval(secs => 60.000),
    locked_at = timezone('utc', CURRENT_TIMESTAMP),
    locked_by = 'replica-8080'
WHERE shedlock.name = 'catalog-export'
  AND shedlock.lock_until <= timezone('utc', CURRENT_TIMESTAMP)
```

## Взять блокировку: следующие попытки

По исходнику `StorageBasedLockProvider` вставку провайдер пробует, только пока не знает, что строка уже есть. Узнав (вставка прошла или упёрлась в существующую строку), он запоминает это (`LockRecordRegistry`) и дальше шлёт только обновление:

```sql
UPDATE shedlock
SET lock_until = timezone('utc', CURRENT_TIMESTAMP) + make_interval(secs => :lockAtMostForInterval),
    locked_at = timezone('utc', CURRENT_TIMESTAMP),
    locked_by = :lockedBy
WHERE shedlock.name = :name
  AND shedlock.lock_until <= timezone('utc', CURRENT_TIMESTAMP)
```

Поэтому строки руками не удаляют: `UPDATE` по отсутствующей строке даёт 0 строк, реплика считает, что блокировку держит другой, а вставку больше не пробует — до перезапуска. В лаборатории сброс (`LockRepository.releaseAll()`) освобождает блокировки `UPDATE`, без удаления.

## Снять блокировку

Вызывается в `finally` после метода — и когда метод отработал, и когда бросил исключение:

```sql
UPDATE shedlock
SET lock_until = CASE
        WHEN locked_at + make_interval(secs => :lockAtLeastForInterval) > timezone('utc', CURRENT_TIMESTAMP)
            THEN locked_at + make_interval(secs => :lockAtLeastForInterval)
        ELSE timezone('utc', CURRENT_TIMESTAMP)
    END
WHERE name = :name
  AND locked_by = :lockedBy
```

- Задача короче `lockAtLeastFor` — блокировка держится до `locked_at + lockAtLeastFor` (сценарий 1).
- Дольше — `lock_until` становится «сейчас», блокировка свободна сразу.
- `locked_by = :lockedBy` — снять можно только строку со своим `locked_by`. Это не маркер взятия, а имя: две реплики с одинаковым `locked_by` снимут блокировку друг друга (сценарий 3).

## Продлить блокировку

Шлёт `KeepAliveLockProvider` каждые `lockAtMostFor / 2`, пока задача идёт (сценарий 3):

```sql
UPDATE shedlock
SET lock_until = timezone('utc', CURRENT_TIMESTAMP) + make_interval(secs => :lockAtMostForInterval)
WHERE name = :name
  AND locked_by = :lockedBy
  AND lock_until > timezone('utc', CURRENT_TIMESTAMP)
```

Продлить можно только свою и ещё не истёкшую блокировку. 0 строк — блокировку уже заняли или срок прошёл: провайдер пишет `Can't extend lock` и перестаёт продлевать, а задача продолжает работать.

## Источники

- ShedLock 7.10.1 — исходники `PostgresSqlServerTimeStatementsSource`, `SqlStatementsSource`, `StorageBasedLockProvider`, `KeepAliveLockProvider`, `R2dbcStorageAccessor`.
- ShedLock — [README](https://github.com/lukas-krecan/ShedLock): разделы «JdbcTemplate», «R2DBC».
