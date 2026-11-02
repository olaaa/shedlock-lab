package io.github.olaaa.shedlocklab.job;

import java.time.Instant;

import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/*
 * Чтение таблицы shedlock — чтобы показать, что в ней лежит. Сам ShedLock в неё пишет без нас.
 * Колонки — TIMESTAMP без зоны с временем UTC, поэтому AT TIME ZONE 'UTC' превращает их в момент времени.
 */
@Component
public class LockTable {

    private final DatabaseClient databaseClient;

    public LockTable(DatabaseClient databaseClient) {
        this.databaseClient = databaseClient;
    }

    public Flux<LockRow> rows() {
        return databaseClient.sql("""
                        SELECT name,
                               lock_until AT TIME ZONE 'UTC' AS lock_until,
                               locked_at AT TIME ZONE 'UTC' AS locked_at,
                               locked_by,
                               lock_until > timezone('utc', clock_timestamp()) AS held
                        FROM shedlock
                        ORDER BY name
                        """)
                .map(row -> new LockRow(
                        row.get("name", String.class),
                        row.get("lock_until", Instant.class),
                        row.get("locked_at", Instant.class),
                        row.get("locked_by", String.class),
                        Boolean.TRUE.equals(row.get("held", Boolean.class))))
                .all();
    }

    public Mono<LockRow> find(String name) {
        return rows().filter(row -> row.name().equals(name)).next();
    }

    /*
     * Освободить все блокировки, не удаляя строки. Удалять строки руками нельзя: провайдер запоминает,
     * что строку блокировки уже создал (LockRecordRegistry в ShedLock), и дальше только обновляет её.
     * Строки нет — UPDATE меняет 0 строк, и реплика не может взять блокировку, пока не перезапустится.
     */
    public Mono<Void> releaseAll() {
        return databaseClient.sql("""
                        UPDATE shedlock
                        SET lock_until = timezone('utc', clock_timestamp())
                        """)
                .then();
    }
}
