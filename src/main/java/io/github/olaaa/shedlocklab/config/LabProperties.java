package io.github.olaaa.shedlocklab.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("lab")
public record LabProperties(String replicaName) {
}
