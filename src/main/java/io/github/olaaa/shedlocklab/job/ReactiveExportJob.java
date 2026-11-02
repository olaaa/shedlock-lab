package io.github.olaaa.shedlocklab.job;

import io.github.olaaa.shedlocklab.config.JobProperties;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/*
 * Ловушка сценария 1 — так делать НЕ надо. Метод с @SchedulerLock возвращает Mono.
 * Прокси ShedLock берёт блокировку, вызывает метод и снимает блокировку, как только метод вернул
 * управление. Но метод вернул только описание работы: сама работа начнётся при подписке, когда
 * блокировка уже снята.
 *
 * @Scheduled здесь нет намеренно. Для реактивного метода Spring вызывает его один раз, ещё при создании
 * бина, чтобы получить Publisher, и потом на каждом срабатывании расписания подписывается на тот же
 * Publisher. Значит, прокси ShedLock сработал бы один раз при старте, а не на каждом запуске. С @Scheduled
 * на этом методе контекст в лаборатории не поднялся: «Cannot obtain a Publisher-convertible value from
 * the @Scheduled reactive method».
 */
@Component
public class ReactiveExportJob {

    public static final String LOCK_NAME = "reactive-export";

    private final JobRunner jobRunner;
    private final JobProperties.ReactiveExport properties;

    public ReactiveExportJob(JobRunner jobRunner, JobProperties.ReactiveExport properties) {
        this.jobRunner = jobRunner;
        this.properties = properties;
    }

    @SchedulerLock(name = LOCK_NAME, lockAtMostFor = "1m")
    public Mono<Long> export() {
        return jobRunner.runReactive(LOCK_NAME, properties.workDuration());
    }
}
