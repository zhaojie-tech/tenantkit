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
package io.tenantkit.messaging.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Messaging propagation configuration ({@code tenantkit.messaging.*},
 * DESIGN &sect;6.2/§6.3). Both policies exist to keep the poison-message
 * loop (nack requeue &rarr; redelivery &rarr; nack again) structurally
 * impossible by default.
 */
@ConfigurationProperties(prefix = "tenantkit.messaging")
public class TenantKitMessagingProperties {

    /**
     * What a publisher does when no tenant is bound to the context, e.g.
     * scheduled jobs or system messages (DESIGN &sect;6.3).
     */
    public enum PublisherMissingContext {

        /** Log a warning and send the message without the tenant header. */
        SKIP_WITH_WARN,

        /** Abort the send with {@code TenantMessagingException} (TK-3001). */
        FAIL
    }

    /**
     * What a listener does when an incoming message carries no tenant header
     * (DESIGN &sect;6.3).
     */
    public enum ListenerMissingHeader {

        /**
         * Reject the message without requeue so the broker dead-letters it
         * for manual triage &mdash; the default, which keeps poison messages
         * out of the nack/requeue loop.
         */
        REJECT_TO_DLQ,

        /** Log a warning and process the message without a tenant context. */
        LOG_AND_ACK
    }

    /** Publisher policy for sends without a tenant context. */
    private PublisherMissingContext publisherMissingContext = PublisherMissingContext.SKIP_WITH_WARN;

    /** Listener policy for messages without the tenant header. */
    private ListenerMissingHeader listenerMissingHeader = ListenerMissingHeader.REJECT_TO_DLQ;

    /**
     * Bean names of listener container factories that must NOT receive the
     * tenant advice. DLQ-triage consumers belong here: a headerless message has
     * already been rejected once, and re-attaching the advice would reject it
     * again at the DLQ entrance — where there is no further dead-letter target,
     * so the message would be lost instead of triaged.
     */
    private java.util.List<String> rawContainerFactories = new java.util.ArrayList<>();

    public PublisherMissingContext getPublisherMissingContext() {
        return this.publisherMissingContext;
    }

    public void setPublisherMissingContext(PublisherMissingContext publisherMissingContext) {
        this.publisherMissingContext = publisherMissingContext;
    }

    public ListenerMissingHeader getListenerMissingHeader() {
        return this.listenerMissingHeader;
    }

    public void setListenerMissingHeader(ListenerMissingHeader listenerMissingHeader) {
        this.listenerMissingHeader = listenerMissingHeader;
    }

    public java.util.List<String> getRawContainerFactories() {
        return rawContainerFactories;
    }

    public void setRawContainerFactories(java.util.List<String> rawContainerFactories) {
        this.rawContainerFactories = rawContainerFactories;
    }
}
