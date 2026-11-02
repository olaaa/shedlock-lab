package io.github.olaaa.shedlocklab;

import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/*
 * @EnableScheduling включает @Scheduled. @EnableSchedulerLock включает ShedLock: вокруг каждого
 * метода с @SchedulerLock ставится прокси, который перед вызовом берёт блокировку, а после — снимает.
 * defaultLockAtMostFor — срок блокировки для методов, где lockAtMostFor не указан.
 */
@SpringBootApplication
@EnableScheduling
@EnableSchedulerLock(defaultLockAtMostFor = "10m")
@ConfigurationPropertiesScan
public class ShedlockLabApplication {

    public static void main(String[] args) {
        SpringApplication.run(ShedlockLabApplication.class, args);
    }
}
