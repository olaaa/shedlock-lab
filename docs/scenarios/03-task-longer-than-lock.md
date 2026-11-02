# Сценарий 3. Задача работает дольше lockAtMostFor

Тест: `src/test/java/io/github/olaaa/shedlocklab/scenario/Scenario3TaskLongerThanLockTest.java`.
Задача: `LongReportJob` — долгий отчёт. `build()` — обычная блокировка `long-report`; `buildWithKeepAlive()` — блокировка `long-report-keep-alive` через `KeepAliveLockProvider` (бин `keepAliveLockProvider`, выбирается `@LockProviderToUse`). Сроки в тестах: `lockAtMostFor` 2 секунды при работе 4 секунды; с продлением — 30 секунд при работе 35 секунд. В `application.yml` — 5 и 10 секунд, 30 и 40 секунд; по расписанию отчёт не запускается.
Ручная проверка: `http/03-task-longer-than-lock.http` на двух репликах.

Коротко:

- **Что случилось.** Задача обычно укладывается в `lockAtMostFor`, но в этот раз шла дольше: данных больше, база медленнее, пауза сборки мусора. Срок блокировки истёк, а держатель жив и работает.
- **Что делает ShedLock.** Ничего: для него блокировка свободна. Следующая реплика, у которой сработало расписание, её занимает, и задачу выполняют двое одновременно. README прямо пишет: если задача идёт дольше `lockAtMostFor`, «поведение может быть непредсказуемым (блокировку фактически будет держать больше одного процесса)».
- **Снять чужую.** Когда первая закончит, она снимает блокировку — но только строку со своим `locked_by`. В `locked_by` по умолчанию — имя хоста, не уникальный маркер: две реплики на одном хосте снимут блокировку друг друга, и войдёт третья.
- **Продление.** `KeepAliveLockProvider` продлевает блокировку каждые `lockAtMostFor / 2`, пока задача идёт. Минимальный `lockAtMostFor` для него — 30 секунд. Продление сужает окно, но не закрывает: остановленный процесс не продлевает.

## Двое после истечения lockAtMostFor

Хронология теста `secondReplicaTakesLockAfterLockAtMostForAndBothWork` (`lockAtMostFor` 2 с, работа 4 с):

1. 00:34:46.682 — A берёт блокировку и начинает отчёт.
2. 00:34:48.70 — `lock_until` прошёл; B вызывает отчёт — ShedLock видит свободную строку, B берёт блокировку (`locked_by = replica-b`) и тоже начинает.
3. 00:34:50.691 — A заканчивает и снимает «свою» блокировку:

```sql
UPDATE shedlock
SET lock_until = ...
WHERE name = 'long-report'
  AND locked_by = 'replica-a'
```

В строке уже `replica-b` — 0 строк, блокировку B A не тронула.
4. 00:34:52.716 — B заканчивает.

Итог: два запуска, интервалы пересекаются (с 48.706 по 50.691 работали оба). Для отчёта это лишняя работа; для выгрузки партнёру — перемешанные файлы.

## locked_by — имя хоста, а не маркер

В ручной блокировке на Redis из [redis-patterns-lab](https://github.com/olaaa/redis-patterns-lab) (сценарий 3) у каждого взятия свой случайный маркер, и снять можно только своё взятие. У ShedLock роль маркера играет `locked_by`, и по исходнику (`SqlConfiguration`) по умолчанию это `Utils.getHostname()` — одно значение на весь процесс и на все процессы хоста. Снятие и продление сравнивают именно его:

```sql
UPDATE shedlock
SET lock_until = timezone('utc', CURRENT_TIMESTAMP) + make_interval(secs => :lockAtMostForInterval)
WHERE name = :name
  AND locked_by = :lockedBy
  AND lock_until > timezone('utc', CURRENT_TIMESTAMP)
```

(это продление; снятие — в сценарии 1).

Тест `sameLockedByLetsLateHolderReleaseForeignLock`: вторая реплика запущена с тем же `lab.replica-name`, как два процесса на одном хосте при значении по умолчанию.

1. 00:34:38.542 — первая начинает отчёт.
2. 00:34:40.652 — блокировка истекла, «двойник» её взял.
3. 00:34:42.550 — первая закончила и сняла блокировку: `locked_by` совпал, `lock_until` стал «сейчас» — хотя держит её двойник.
4. 00:34:42.558 — третий запуск проходит, пока двойник работает.

Три запуска, две пары пересекаются. Поэтому в лаборатории `withLockedByValue(replicaName)`: у каждой реплики своё имя. В Kubernetes имя хоста пода уникально, и значение по умолчанию работает; опасны несколько процессов на одной машине или в одном контейнере.

## Продление: KeepAliveLockProvider

README: `KeepAliveLockProvider` «продлевает блокировку в середине интервала `lockAtMostFor`. Например, если `lockAtMostFor` — 10 минут, блокировка продлевается каждые 5 минут на 10 минут, пока её не снимут. Минимальный `lockAtMostFor` для этого провайдера — 30 секунд». И мнение автора: использовать его «только в особых случаях»: он добавляет сложности, и поведение труднее понять.

Подключение — провайдер-обёртка и выбор на методе:

```java
@Bean(KEEP_ALIVE_LOCK_PROVIDER)
KeepAliveLockProvider keepAliveLockProvider(R2dbcLockProvider lockProvider, LockExtensionScheduler scheduler) {
    return new KeepAliveLockProvider(lockProvider, scheduler.executor());
}
```

```java
@Scheduled(cron = "${long-report.cron}")
@LockProviderToUse(LockProviderConfiguration.KEEP_ALIVE_LOCK_PROVIDER)
@SchedulerLock(name = KEEP_ALIVE_LOCK_NAME, lockAtMostFor = "${long-report.keep-alive-lock-at-most-for}")
public Optional<Long> buildWithKeepAlive() {
    ...
}
```

- Два бина `LockProvider` — значит, один `@Primary`, а второй выбирается `@LockProviderToUse` по имени бина (по исходнику `BeanNameSelectingLockProviderSupplier`).
- Поток продления — свой `ScheduledExecutorService`, завёрнутый в `LockExtensionScheduler`. Бином типа `ScheduledExecutorService` его делать нельзя: найдя такой бин, Spring Boot не создаёт свой планировщик задач, и `@Scheduled` пошли бы в единственный поток продления.

По исходнику (`KeepAliveLockProvider`) продление — тот же `UPDATE ... WHERE locked_by = :lockedBy AND lock_until > now`. Если продлить не удалось (строку уже заняли или срок прошёл), провайдер останавливает продление и пишет `Can't extend lock`; задача при этом продолжает работать.

Тест `keepAliveExtendsLockWhileTaskRuns` (`lockAtMostFor` 30 с, работа 35 с):

```text
взята:        locked_at 00:34:52.824, lock_until 00:35:22.824   ← + 30 с
через 31 с:   locked_at 00:34:52.824, lock_until 00:35:52.835   ← продлена дважды, на 15-й и 30-й секунде
реплика B:    пропуск
запусков:     1
```

Тест ждёт 31 секунду по-настоящему: меньше 30 секунд `KeepAliveLockProvider` не принимает (`IllegalArgumentException`).

## Чего не даёт ни один вариант

Продление закрывает «задача медленная», но не «процесс завис»: пауза сборки мусора, остановленная виртуальная машина, обрыв связи с базой — продлевать некому, срок истекает, работают двое. Клеппман разбирает это для любых блокировок с арендой: проверка срока прямо перед записью не помогает, потому что пауза может случиться между проверкой и записью. Защита для корректности — маркер ограждения: хранилище, куда пишет задача, отвергает запись устаревшего держателя. В ShedLock его нет. README так и формулирует назначение: задачи, которые «не готовы выполняться параллельно, но могут безопасно выполняться повторно».

## Что проверяют тесты

`secondReplicaTakesLockAfterLockAtMostForAndBothWork`: после `lockAtMostFor` B берёт блокировку, отчёт строят двое одновременно; A по окончании не трогает строку B.

`sameLockedByLetsLateHolderReleaseForeignLock`: при одинаковом `locked_by` первая снимает блокировку двойника, и проходит третий запуск.

`keepAliveExtendsLockWhileTaskRuns`: с продлением блокировка занята и после исходного срока, B пропускает запуск, запуск один.

## Попробовать вручную

Две реплики, как в сценарии 1:

```bash
curl -s -X POST localhost:8080/lab/reset
curl -s -X POST localhost:8080/lab/jobs/long-report & sleep 6; curl -s -X POST localhost:8081/lab/jobs/long-report; wait
curl -s localhost:8080/lab/runs
```

Результат такого прогона:

```text
{"replica":"replica-8081","executed":true}
{"replica":"replica-8080","executed":true}
[{"id":9,"job":"long-report","replica":"replica-8080","startedAt":"2026-11-02T00:45:25.218772Z","finishedAt":"2026-11-02T00:45:35.230773Z"},
 {"id":10,"job":"long-report","replica":"replica-8081","startedAt":"2026-11-02T00:45:31.243088Z","finishedAt":"2026-11-02T00:45:41.259536Z"}]
```

`lockAtMostFor` — 5 секунд, отчёт — 10: через 6 секунд реплика 8081 заняла истёкшую блокировку, и с 31.243 по 35.230 отчёт строили обе.

## Что важно заметить

- Задача дольше `lockAtMostFor` — и блокировку занимает вторая реплика; ShedLock этого не замечает.
- Снять и продлить можно только строку со своим `locked_by`, а по умолчанию это имя хоста: процессы одного хоста неотличимы. Своё имя реплики — `withLockedByValue`.
- `KeepAliveLockProvider` продлевает каждые `lockAtMostFor / 2`, минимум 30 секунд; от зависшего процесса не спасает.
- ShedLock — про эффективность, не про корректность: задача под ним должна переживать повторное выполнение.

## Как сказать на собеседовании

«Блокировка ShedLock — аренда: `lock_until = now + lockAtMostFor`. Если задача шла дольше, срок истёк, и следующая реплика её возьмёт — работают двое; ShedLock это прямо оговаривает. Поэтому `lockAtMostFor` ставлю с большим запасом, а для заведомо долгих задач есть `KeepAliveLockProvider`: продлевает каждые `lockAtMostFor / 2`, но не меньше 30 секунд и не спасает зависший процесс. Ещё нюанс: снять или продлить можно строку со своим `locked_by`, а это имя хоста, не маркер взятия, — два процесса на одном хосте снимут блокировку друг друга, поэтому задаю `withLockedByValue`. И главное: ShedLock — для задач, которые не должны идти параллельно, но переживут повтор. Если двойное выполнение портит данные, нужен маркер ограждения в самом хранилище или идемпотентная задача».

## Источники

- ShedLock — [README](https://github.com/lukas-krecan/ShedLock): разделы «Annotate your scheduled tasks», «KeepAliveLockProvider», «Caveats», «ShedLock is not a distributed scheduler»; исходники 7.10.1: `KeepAliveLockProvider`, `SqlConfiguration`, `PostgresSqlServerTimeStatementsSource`, `BeanNameSelectingLockProviderSupplier`.
- Martin Kleppmann — [How to do distributed locking](https://martin.kleppmann.com/2016/02/08/how-to-do-distributed-locking.html), 2016.
