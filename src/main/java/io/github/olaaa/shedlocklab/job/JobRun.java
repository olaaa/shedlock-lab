package io.github.olaaa.shedlocklab.job;

import java.time.Instant;

import org.jspecify.annotations.Nullable;

public record JobRun(long id, String job, String replica, Instant startedAt, @Nullable Instant finishedAt) {
}
