package io.tenantkit.web;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import io.tenantkit.core.TenantId;
import io.tenantkit.core.TenantResolutionRequest;
import io.tenantkit.core.TenantResolver;

/** Test resolver that records every request it saw; used by the behavior matrix. */
final class RecordingResolver implements TenantResolver {

    private final Optional<String> value;
    private final int order;
    private final List<TenantResolutionRequest> requests = new ArrayList<>();

    RecordingResolver(Optional<String> value, int order) {
        this.value = value;
        this.order = order;
    }

    @Override
    public Optional<TenantId> resolve(TenantResolutionRequest request) {
        requests.add(request);
        return value.map(TenantId::of);
    }

    @Override
    public int order() {
        return order;
    }

    List<TenantResolutionRequest> requests() {
        return requests;
    }

    /** URIs this resolver was asked about, in call order. */
    List<String> called() {
        return requests.stream().map(r -> r.requestUri().toString()).toList();
    }
}
