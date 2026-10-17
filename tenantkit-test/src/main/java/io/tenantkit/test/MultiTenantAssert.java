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

import java.util.function.Supplier;

import io.tenantkit.core.TenantContext;
import io.tenantkit.core.TenantId;

/**
 * Isolation assertion helper (DESIGN &sect;6.7), shared by the demo app and
 * user tests:
 *
 * <pre>{@code
 * MultiTenantAssert.assertThat(() -> repository.read(key))
 *         .forTenant("alpha").returns("alpha-value")
 *         .forTenant("beta").returns("beta-value");
 * }</pre>
 *
 * Each {@code returns} runs the operation inside that tenant's explicit scope
 * and asserts the result &mdash; the failure names both tenants, which turns
 * "the data is wrong" into "alpha sees beta's row".
 */
public final class MultiTenantAssert {

    private MultiTenantAssert() {
    }

    public static <T> TenantIsolationAssert<T> assertThat(Supplier<T> operation) {
        return new TenantIsolationAssert<>(operation);
    }

    public static final class TenantIsolationAssert<T> {

        private final Supplier<T> operation;

        private TenantIsolationAssert(Supplier<T> operation) {
            this.operation = operation;
        }

        public TenantStep forTenant(String tenant) {
            return new TenantStep(TenantId.of(tenant));
        }

        public final class TenantStep {

            private final TenantId tenant;

            private TenantStep(TenantId tenant) {
                this.tenant = tenant;
            }

            public TenantIsolationAssert<T> returns(T expected) {
                T actual = inScope(tenant, operation);
                if (!java.util.Objects.equals(expected, actual)) {
                    throw new AssertionError("tenant isolation violated: tenant '" + tenant.value()
                            + "' expected <" + expected + "> but saw <" + actual + ">");
                }
                return TenantIsolationAssert.this;
            }

            public TenantIsolationAssert<T> satisfies(java.util.function.Consumer<T> requirement) {
                T actual = inScope(tenant, operation);
                requirement.accept(actual);
                return TenantIsolationAssert.this;
            }
        }
    }

    private static <X> X inScope(TenantId tenant, Supplier<X> operation) {
        try {
            return TenantContext.callAs(tenant, operation::get);
        }
        catch (RuntimeException | Error ex) {
            throw ex;
        }
        catch (Exception ex) {
            throw new IllegalStateException("tenant-scoped operation failed", ex);
        }
    }
}
