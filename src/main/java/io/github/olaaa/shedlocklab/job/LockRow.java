package io.github.olaaa.shedlocklab.job;

import java.time.Instant;

/* Строка таблицы shedlock. held — занята ли блокировка сейчас: lock_until ещё не наступил по часам базы. */
public record LockRow(String name, Instant lockUntil, Instant lockedAt, String lockedBy, boolean held) {
}
