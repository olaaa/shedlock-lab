package io.github.olaaa.shedlocklab.service;

import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.stereotype.Service;

/* Сбой для опыта: следующий запуск задачи на этой реплике упадёт с исключением (сценарий 2). */
@Service
public class JobFaults {

    private final AtomicBoolean failNextRun = new AtomicBoolean();

    public void failNextRun() {
        failNextRun.set(true);
    }

    public boolean takeFailure() {
        return failNextRun.getAndSet(false);
    }

    public void reset() {
        failNextRun.set(false);
    }
}
