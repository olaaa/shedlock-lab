/*
 * Таблица блокировок ShedLock — DDL для PostgreSQL из README ShedLock (раздел JdbcTemplate;
 * провайдер R2DBC ссылается на него). Одна строка — одна блокировка: name — имя из @SchedulerLock,
 * lock_until — до какого момента блокировка занята, locked_at — когда взята, locked_by — кто взял.
 * С usingDbTime() время в колонках — UTC по часам базы. Первичный ключ обязателен: без него
 * две реплики вставят по своей строке, и блокировки не будет.
 */
CREATE TABLE IF NOT EXISTS shedlock
(
    name       VARCHAR(64)  NOT NULL,
    lock_until TIMESTAMP    NOT NULL,
    locked_at  TIMESTAMP    NOT NULL,
    locked_by  VARCHAR(255) NOT NULL,
    PRIMARY KEY (name)
);

/* Журнал запусков задач: какая реплика, когда начала и когда закончила. По нему тесты видят дубли и наложения. */
CREATE TABLE IF NOT EXISTS job_run
(
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    job         TEXT        NOT NULL,
    replica     TEXT        NOT NULL,
    started_at  TIMESTAMPTZ NOT NULL,
    finished_at TIMESTAMPTZ
);
