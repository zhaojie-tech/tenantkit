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
package io.tenantkit.demo;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.tenantkit.cache.redis.TenantRedisTemplate;
import io.tenantkit.core.TenantContext;
import io.tenantkit.core.TenantId;
import io.tenantkit.demo.async.DemoAsyncWorker;
import io.tenantkit.provisioning.ProvisionOptions;
import io.tenantkit.provisioning.SeedProfile;
import io.tenantkit.provisioning.TenantProvisioner;
import io.tenantkit.web.TenantPublic;

/**
 * The three demo endpoints (DESIGN &sect;11 M3 收敛形态):
 * {@code GET /public/health}, {@code GET /api/echo}, {@code POST /api/provision}.
 */
@RestController
@RequestMapping
public class DemoController {

    private final JdbcTemplate jdbc;
    private final TenantRedisTemplate redis;
    private final DemoAsyncWorker worker;
    private final TenantProvisioner provisioner;

    public DemoController(JdbcTemplate jdbc, TenantRedisTemplate redis, DemoAsyncWorker worker,
            TenantProvisioner provisioner) {
        this.jdbc = jdbc;
        this.redis = redis;
        this.worker = worker;
        this.provisioner = provisioner;
    }

    /** Public endpoint: still resolved, but a missing tenant does not fail. */
    @TenantPublic
    @GetMapping("/public/health")
    public Map<String, String> health() {
        return Map.of("status", "ok", "tenant", String.valueOf(TenantContext.currentOrNull()));
    }

    /** Tenant-bound: fixed strategy in the demo binds every request to the configured tenant. */
    @GetMapping("/api/echo")
    public Map<String, Object> echo() {
        TenantId tenant = TenantContext.required();
        String key = "demo:last-echo";
        redis.opsForValue().set(key, tenant.value());
        Object fromRedis;
        try {
            fromRedis = TenantContext.callAs(tenant, () -> redis.opsForValue().get(key));
        }
        catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
        String asyncSeen = worker.echoFromAsyncPool().join();
        Integer rows = jdbc.queryForObject("select count(*) from demo_notes", Integer.class);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("tenant", tenant.value());
        body.put("redisEcho", fromRedis);
        body.put("asyncEcho", asyncSeen);
        body.put("seedRows", rows == null ? 0 : rows);
        return body;
    }

    /** Idempotent provisioning with the seed ledger (second call skips, not replays). */
    @TenantPublic
    @PostMapping("/api/provision")
    public Map<String, Object> provision(@RequestParam("tenant") String tenant) {
        var report = provisioner.provision(TenantId.of(tenant), new SeedProfile("demo"),
                new ProvisionOptions(null, null));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("tenant", report.tenant().value());
        body.put("executed", report.executed().size());
        body.put("skipped", report.skipped().size());
        body.put("failed", report.failed().size());
        body.put("duplicateKeyShortCircuit", report.duplicateKeyShortCircuit());
        return body;
    }
}
