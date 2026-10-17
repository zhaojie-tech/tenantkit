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
package io.tenantkit.benchmarks;

import java.util.Arrays;
import java.util.concurrent.ThreadLocalRandom;

import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import io.tenantkit.cache.redis.TenantKeyCodec;
import io.tenantkit.core.TenantContext;
import io.tenantkit.core.TenantId;
import io.tenantkit.web.TenantResolutionFilter;
import io.tenantkit.web.properties.TenantKitResolutionProperties;
import io.tenantkit.web.resolver.FixedTenantResolver;

/**
 * Micro-benchmark runner for the three DESIGN &sect;7 overhead items:
 * Redis key rewriting (length delta + codec throughput), the resolution
 * filter per request, and the RabbitMQ listener advice per delivery.
 *
 * <p><strong>Methodology.</strong> Plain {@code System.nanoTime} loops with a
 * warmup phase, median of {@value #ROUNDS} rounds, single JVM, single thread.
 * This is an indicative harness, not JMH: no dead-code elimination guards
 * beyond consuming results, no fork-per-measurement. Numbers are published in
 * {@code docs/benchmarks.md} with the machine and date they were measured on
 * and are explicitly not a cross-machine comparison.
 */
public final class BenchmarkMain {

    private static final int ROUNDS = 11;
    private static final int WARMUP = 8;
    private static final int ITERATIONS = 500_000;

    private BenchmarkMain() {
    }

    public static void main(String[] args) throws Throwable {
        System.out.println("tenantkit benchmarks — JDK " + System.getProperty("java.version")
                + " / " + System.getProperty("os.name") + " " + System.getProperty("os.arch"));

        keyCodecBench();
        filterBench();
        adviceBench();
    }

    // ---------------------------------------------------------------- codec

    private static void keyCodecBench() {
        TenantKeyCodec codec = new TenantKeyCodec(true, java.util.List.of("app:*"));
        TenantId tenant = TenantId.of("tenant-0123456789abcdef");
        String logical = "session:user:1234567890:profile:preferences";

        String physical = codec.encode(logical, tenant);
        System.out.println("\n[redis-key]");
        System.out.println("  logical  key length : " + logical.length());
        System.out.println("  physical key length : " + physical.length()
                + "  (delta = " + (physical.length() - logical.length()) + " bytes; prefix '"
                + codec.tenantPrefix(tenant) + "')");

        TenantKeyCodec noShared = new TenantKeyCodec(true, java.util.List.of());
        long encodeNoSharedNs = measure(() -> {
            String out = null;
            for (int i = 0; i < ITERATIONS; i++) {
                out = noShared.encode(logical, tenant);
            }
            return out;
        });
        long encodeNs = measure(() -> {
            String out = null;
            for (int i = 0; i < ITERATIONS; i++) {
                out = codec.encode(logical, tenant);
            }
            return out;
        });
        long decodeNs = measure(() -> {
            String out = null;
            for (int i = 0; i < ITERATIONS; i++) {
                out = codec.decode(physical, tenant);
            }
            return out;
        });
        System.out.printf("  encode (with shared-key glob 'app:*'): %.1f ns/op%n",
                encodeNs / (double) ITERATIONS);
        System.out.printf("  encode (no shared-key globs):          %.1f ns/op%n",
                encodeNoSharedNs / (double) ITERATIONS);
        System.out.printf("  decode (with shared-key glob):         %.1f ns/op%n",
                decodeNs / (double) ITERATIONS);
    }

    // ---------------------------------------------------------------- filter

    private static void filterBench() throws Exception {
        TenantKitResolutionProperties properties = new TenantKitResolutionProperties();
        properties.setStrategies(java.util.List.of(TenantKitResolutionProperties.Strategy.FIXED));
        properties.setFixed("tenant-a");
        TenantResolutionFilter filter = new TenantResolutionFilter(
                java.util.List.of(new FixedTenantResolver(properties, 0)), properties, null, null);

        MockHttpServletRequest template = new MockHttpServletRequest("GET", "/api/orders/42");
        long perRequestNs = measure(() -> {
            int checksum = 0;
            for (int i = 0; i < ITERATIONS / 10; i++) {
                MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/orders/42");
                MockHttpServletResponse response = new MockHttpServletResponse();
                try {
                    filter.doFilter(request, response, (req, res) -> { });
                }
                catch (Exception ex) {
                    throw new IllegalStateException(ex);
                }
                checksum += response.getStatus();
            }
            return checksum;
        });
        System.out.println("\n[resolution-filter]");
        System.out.printf("  %.0f ns/request (fixed strategy, request+response constructed per iteration)%n",
                perRequestNs / (double) (ITERATIONS / 10));

        // variant with a pre-built request to isolate the filter cost itself
        long filterOnlyNs = measure(() -> {
            int checksum = 0;
            MockHttpServletResponse response = new MockHttpServletResponse();
            for (int i = 0; i < ITERATIONS; i++) {
                try {
                    filter.doFilter(template, response, (req, res) -> { });
                }
                catch (Exception ex) {
                    throw new IllegalStateException(ex);
                }
                checksum += response.getStatus();
            }
            return checksum;
        });
        System.out.printf("  %.0f ns/request (pre-built request/response: filter + MDC + resolver)%n",
                filterOnlyNs / (double) ITERATIONS);
    }

    // ---------------------------------------------------------------- advice

    private static void adviceBench() throws Throwable {
        io.tenantkit.messaging.TenantContextAdvice advice = new io.tenantkit.messaging.TenantContextAdvice(
                io.tenantkit.messaging.properties.TenantKitMessagingProperties.ListenerMissingHeader.REJECT_TO_DLQ);
        org.springframework.amqp.core.Message message = org.springframework.amqp.core.MessageBuilder
                .withBody(new byte[] { 1 })
                .setHeader(io.tenantkit.messaging.TenantMessagingHeaders.TENANT_HEADER, "tenant-b")
                .build();
        org.aopalliance.intercept.MethodInvocation invocation = new Invocation(message);

        long ns = measure(() -> {
            Object last = null;
            try {
                for (int i = 0; i < ITERATIONS / 10; i++) {
                    last = advice.invoke(invocation);
                }
            }
            catch (Throwable ex) {
                throw new IllegalStateException(ex);
            }
            return last;
        });
        System.out.println("\n[rabbit-advice]");
        System.out.printf("  %.0f ns/delivery (header parse + runAs + MDC restore + proceed)%n",
                ns / (double) (ITERATIONS / 10));
    }

    // ---------------------------------------------------------------- harness

    private interface Body {
        Object run() throws Throwable;
    }

    private static long measure(Body body) {
        long[] samples = new long[ROUNDS];
        Object sink = null;
        for (int round = 0; round < WARMUP + ROUNDS; round++) {
            long start = System.nanoTime();
            try {
                sink = body.run();
            }
            catch (Throwable ex) {
                throw new IllegalStateException(ex);
            }
            long elapsed = System.nanoTime() - start;
            if (round >= WARMUP) {
                samples[round - WARMUP] = elapsed;
            }
        }
        if (sink == null) {
            throw new IllegalStateException("benchmark result was discarded");
        }
        Arrays.sort(samples);
        return samples[samples.length / 2];
    }

    /** Minimal aopalliance invocation: one Message argument, no-op proceed. */
    private static final class Invocation implements org.aopalliance.intercept.MethodInvocation {

        private final Object[] arguments;

        Invocation(Object message) {
            this.arguments = new Object[] { message };
        }

        @Override
        public java.lang.reflect.Method getMethod() {
            return null;
        }

        @Override
        public Object[] getArguments() {
            return arguments;
        }

        @Override
        public Object proceed() {
            return "handled-" + ThreadLocalRandom.current().nextInt(2);
        }

        @Override
        public Object getThis() {
            return null;
        }

        @Override
        public java.lang.reflect.AccessibleObject getStaticPart() {
            return null;
        }
    }
}
