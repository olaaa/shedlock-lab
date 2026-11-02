package io.github.olaaa.shedlocklab.config;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import io.r2dbc.spi.ConnectionFactory;
import net.javacrumbs.shedlock.provider.r2dbc.R2dbcLockProvider;
import net.javacrumbs.shedlock.support.KeepAliveLockProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration(proxyBeanMethods = false)
public class LockProviderConfiguration {

    public static final String KEEP_ALIVE_LOCK_PROVIDER = "keepAliveLockProvider";

    /*
     * Блокировки — строки таблицы shedlock в PostgreSQL, доступ через R2DBC.
     * usingDbTime(): сроки считает база по своим часам, а не реплики — часы реплик могут расходиться.
     * withLockedByValue(): что писать в locked_by. По умолчанию там имя хоста, и две реплики на одном
     * хосте для ShedLock неотличимы: снять блокировку можно только «свою», но «свои» у них общие (сценарий 3).
     * @Primary — этим провайдером пользуются все @SchedulerLock без @LockProviderToUse.
     */
    @Bean
    @Primary
    R2dbcLockProvider lockProvider(ConnectionFactory connectionFactory, LabProperties labProperties) {
        return new R2dbcLockProvider(R2dbcLockProvider.Configuration.builder(connectionFactory)
                .usingDbTime()
                .withLockedByValue(labProperties.replicaName())
                .build());
    }

    /*
     * Тот же провайдер, но с продлением: пока задача идёт, блокировка продлевается каждые lockAtMostFor / 2.
     * Минимальный lockAtMostFor для него — 30 секунд. Выбирается на методе через @LockProviderToUse.
     */
    @Bean(KEEP_ALIVE_LOCK_PROVIDER)
    KeepAliveLockProvider keepAliveLockProvider(R2dbcLockProvider lockProvider, LockExtensionScheduler scheduler) {
        return new KeepAliveLockProvider(lockProvider, scheduler.executor());
    }

    @Bean
    LockExtensionScheduler lockExtensionScheduler() {
        return new LockExtensionScheduler(Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().name("shedlock-keep-alive").daemon().factory()));
    }

    /*
     * Обёртка, а не бин типа ScheduledExecutorService: найдя такой бин, Spring Boot не создаёт свой
     * планировщик, и все @Scheduled пошли бы в этот единственный поток продления. close() Spring вызовет
     * при остановке контекста сам.
     */
    public record LockExtensionScheduler(ScheduledExecutorService executor) implements AutoCloseable {

        @Override
        public void close() {
            executor.shutdownNow();
        }
    }
}
