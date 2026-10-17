/*
 * Copyright 2026 TenantKit Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.tenantkit.test;

import java.time.Duration;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Singleton Testcontainers factories (DESIGN &sect;6.7): containers start once
 * per JVM and are reused across test classes, keeping suites inside the 20
 * minute budget (DESIGN &sect;9). Every accessor is lazy &mdash; nothing
 * touches Docker until a container is actually requested, so embedding this
 * toolkit never fails a Docker-less build.
 *
 * <p>Stop behavior follows Testcontainers Ryuk semantics: the containers are
 * registered for JVM-shutdown cleanup; tests must not call {@code stop()}.
 */
public final class TenantSupport {

    private static final Duration DEFAULT_START_TIMEOUT = Duration.ofMinutes(3);

    private static volatile MySQLContainer<?> mysql;
    private static volatile RabbitMQContainer rabbitmq;
    private static volatile GenericContainer<?> redis;

    private TenantSupport() {
    }

    /** Shared MySQL 8 container, started on first use. */
    public static MySQLContainer<?> mysql() {
        if (mysql == null) {
            synchronized (TenantSupport.class) {
                if (mysql == null) {
                    MySQLContainer<?> container = new MySQLContainer<>(DockerImageName.parse("mysql:8.4"))
                            .withDatabaseName("tenantkit")
                            .withStartupTimeout(DEFAULT_START_TIMEOUT);
                    container.start();
                    mysql = container;
                }
            }
        }
        return mysql;
    }

    /** Shared RabbitMQ 4 container with the management plugin, started on first use. */
    public static RabbitMQContainer rabbitmq() {
        if (rabbitmq == null) {
            synchronized (TenantSupport.class) {
                if (rabbitmq == null) {
                    RabbitMQContainer container = new RabbitMQContainer(
                            DockerImageName.parse("rabbitmq:4-management"))
                            .withStartupTimeout(DEFAULT_START_TIMEOUT);
                    container.start();
                    rabbitmq = container;
                }
            }
        }
        return rabbitmq;
    }

    /** Shared Redis 8 container, started on first use. */
    public static GenericContainer<?> redis() {
        if (redis == null) {
            synchronized (TenantSupport.class) {
                if (redis == null) {
                    GenericContainer<?> container = new GenericContainer<>(
                            DockerImageName.parse("redis:8-alpine")).withExposedPorts(6379)
                            .withStartupTimeout(DEFAULT_START_TIMEOUT);
                    container.start();
                    redis = container;
                }
            }
        }
        return redis;
    }

    /** Test hook: forget the instances without stopping them (forked JVMs clean up via Ryuk). */
    static void resetForTests() {
        mysql = null;
        rabbitmq = null;
        redis = null;
    }
}
