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
package io.tenantkit.messaging;

import java.nio.charset.StandardCharsets;

import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.util.StringUtils;

import io.tenantkit.core.TenantContext;
import io.tenantkit.core.TenantId;
import io.tenantkit.core.async.TenantMdc;
import io.tenantkit.core.exception.TenantIdFormatException;
import io.tenantkit.core.exception.TenantMessagingException;
import io.tenantkit.messaging.properties.TenantKitMessagingProperties.ListenerMissingHeader;

/**
 * Listener side of the RabbitMQ tenant propagation (DESIGN &sect;6.3): a
 * before-handle {@link MethodInterceptor} that restores
 * {@link TenantContext} <em>and</em> MDC around the listener invocation, so a
 * consumer handler sees exactly the tenant the publisher bound &mdash; the
 * anti-pattern this prevents is a message from tenant A being processed while
 * a stale or missing context lets it touch tenant B's data.
 *
 * <p>Spring AMQP applies the advice chain through a Spring AOP proxy over the
 * listener, so the incoming {@link Message} is one of the invocation
 * arguments; the first {@code Message} argument is used. Missing-header and
 * invalid-header behaviour follows the configured policy:
 * <ul>
 * <li>{@link ListenerMissingHeader#REJECT_TO_DLQ} (default) rejects without
 * requeue (TK-3002/TK-3003 wrapped in {@link AmqpRejectAndDontRequeueException})
 * so the broker dead-letters the message for manual triage instead of looping
 * it forever;</li>
 * <li>{@link ListenerMissingHeader#LOG_AND_ACK} warns and processes the
 * message without a tenant context.</li>
 * </ul>
 *
 * <p>Cleanup is doubled in {@code finally}: the tenant MDC key is removed (other
 * keys an outer observer placed stay untouched) plus the
 * context restore performed by {@link TenantContext#callAs}, so neither
 * observation channel leaks into the next delivery.
 */
public class TenantContextAdvice implements MethodInterceptor {

    private static final Logger LOGGER = LoggerFactory.getLogger(TenantContextAdvice.class);

    private final ListenerMissingHeader missingHeaderPolicy;

    public TenantContextAdvice(ListenerMissingHeader missingHeaderPolicy) {
        this.missingHeaderPolicy = missingHeaderPolicy;
    }

    /** The policy applied when an incoming message carries no tenant header. */
    public ListenerMissingHeader missingHeaderPolicy() {
        return this.missingHeaderPolicy;
    }

    @Override
    public Object invoke(MethodInvocation invocation) throws Throwable {
        Message message = firstMessage(invocation.getArguments());
        if (message == null) {
            // Not a listener invocation shape we know (e.g. user composed the
            // advice onto something else): stay transparent.
            return invocation.proceed();
        }
        String header = headerValue(message);
        if (!StringUtils.hasText(header)) {
            if (this.missingHeaderPolicy == ListenerMissingHeader.REJECT_TO_DLQ) {
                TenantMessagingException cause = new TenantMessagingException(
                        TenantMessagingErrorCodes.LISTENER_MISSING_HEADER,
                        "cannot process message: missing tenant header ('"
                                + TenantMessagingHeaders.TENANT_HEADER
                                + "'); the message is rejected without requeue so the broker dead-letters it "
                                + "instead of looping");
                throw new AmqpRejectAndDontRequeueException(cause.getMessage(), cause);
            }
            LOGGER.warn("processing message without tenant: header '"
                    + TenantMessagingHeaders.TENANT_HEADER
                    + "' absent (tenantkit.messaging.listener-missing-header=log-and-ack)");
            return invocation.proceed();
        }
        TenantId tenant;
        try {
            tenant = TenantId.of(header);
        }
        catch (TenantIdFormatException ex) {
            // never echo the raw value: an invalid header failed the charset check, so it
            // may carry log-injection payloads (newlines, ANSI) straight into DLQ reasons
            TenantMessagingException cause = new TenantMessagingException(
                    TenantMessagingErrorCodes.LISTENER_INVALID_HEADER,
                    "cannot process message: the '" + TenantMessagingHeaders.TENANT_HEADER
                            + "' header value (length " + header.length()
                            + ") fails the TenantId charset; the message is rejected without requeue so the "
                            + "broker dead-letters it instead of looping",
                    ex);
            throw new AmqpRejectAndDontRequeueException(cause.getMessage(), cause);
        }
        // runAs + holder (not callAs): aopalliance proceed() throws Throwable,
        // and the listener's original exception type must reach the container
        // untouched for its requeue/dlq decisions
        Object[] result = new Object[1];
        Throwable[] failure = new Throwable[1];
        try {
            MdcDelegate.put(TenantMdc.TENANT_KEY, tenant.value());
            TenantContext.runAs(tenant, () -> {
                try {
                    result[0] = invocation.proceed();
                }
                catch (Throwable t) {
                    failure[0] = t;
                }
            });
            if (failure[0] != null) {
                throw failure[0];
            }
            return result[0];
        }
        finally {
            // double cleanup, both observation channels: tenant MDC key removed,
            // context restored to its previous binding by runAs
            MdcDelegate.clear();
        }
    }

    private static Message firstMessage(Object[] arguments) {
        if (arguments == null) {
            return null;
        }
        for (Object argument : arguments) {
            if (argument instanceof Message message) {
                return message;
            }
        }
        return null;
    }

    /**
     * Header values arrive as {@code String} on this side of the wire but as
     * broker long-string or byte-array representations after a round trip
     * (e.g. a dead-letter return); all three are accepted here.
     */
    private static String headerValue(Message message) {
        Object raw = message.getMessageProperties().getHeader(TenantMessagingHeaders.TENANT_HEADER);
        if (raw == null) {
            return null;
        }
        if (raw instanceof String value) {
            return value;
        }
        if (raw instanceof byte[] bytes) {
            return new String(bytes, StandardCharsets.UTF_8);
        }
        return raw.toString();
    }

    /**
     * Isolated MDC bridge so this class stays loadable with no logging
     * backend on the compile classpath and never statically references
     * anything beyond slf4j.
     */
    private static final class MdcDelegate {

        static void put(String key, String value) {
            org.slf4j.MDC.put(key, value);
        }

        static void clear() {
            org.slf4j.MDC.remove(io.tenantkit.core.async.TenantMdc.TENANT_KEY);
        }
    }
}
