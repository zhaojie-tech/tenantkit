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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessagePostProcessor;

import io.tenantkit.core.TenantContext;
import io.tenantkit.core.TenantId;
import io.tenantkit.core.exception.TenantMessagingException;
import io.tenantkit.messaging.properties.TenantKitMessagingProperties.PublisherMissingContext;

/**
 * Publisher side of the RabbitMQ tenant propagation (DESIGN &sect;6.3):
 * stamps {@link TenantMessagingHeaders#TENANT_HEADER} from the current
 * {@link TenantContext} onto every outgoing message.
 *
 * <p>Sends without a bound tenant (scheduled jobs, system messages) follow the
 * configured policy: {@link PublisherMissingContext#SKIP_WITH_WARN} logs a
 * warning and sends the message unchanged &mdash; the default, because
 * tenant-less system messages are legitimate; {@link PublisherMissingContext#FAIL}
 * aborts the send with {@link TenantMessagingException} TK-3001 for pipelines
 * where an untenantable message is always a bug.
 */
public class TenantHeaderMessagePostProcessor implements MessagePostProcessor {

    private static final Logger LOGGER = LoggerFactory.getLogger(TenantHeaderMessagePostProcessor.class);

    private final PublisherMissingContext missingContextPolicy;

    public TenantHeaderMessagePostProcessor(PublisherMissingContext missingContextPolicy) {
        this.missingContextPolicy = missingContextPolicy;
    }

    /** The policy applied when no tenant is bound to the publishing context. */
    public PublisherMissingContext missingContextPolicy() {
        return this.missingContextPolicy;
    }

    @Override
    public Message postProcessMessage(Message message) throws AmqpException {
        TenantId tenant = TenantContext.currentOrNull();
        if (tenant == null) {
            if (this.missingContextPolicy == PublisherMissingContext.FAIL) {
                throw new TenantMessagingException(TenantMessagingErrorCodes.PUBLISHER_NO_TENANT,
                        "cannot publish: no tenant bound to the publisher context; "
                                + "bind one with TenantContext.runAs or set "
                                + "tenantkit.messaging.publisher-missing-context=skip-with-warn "
                                + "for legitimate system messages");
            }
            LOGGER.warn("publishing without tenant header: no tenant bound to the publisher context "
                    + "(tenantkit.messaging.publisher-missing-context=skip-with-warn)");
            return message;
        }
        message.getMessageProperties().setHeader(TenantMessagingHeaders.TENANT_HEADER, tenant.value());
        return message;
    }
}
