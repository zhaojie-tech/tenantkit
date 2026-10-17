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
package io.tenantkit.core.async;

import java.util.Map;

import io.tenantkit.core.TenantContext;
import io.tenantkit.core.TenantId;
import org.slf4j.MDC;
import org.springframework.core.task.TaskDecorator;

/**
 * Copies tenant and MDC to the worker thread and clears both afterwards
 * (DESIGN &sect;6.3). When {@code io.micrometer:context-propagation} is on the
 * classpath the runnable is additionally wrapped in a {@code ContextSnapshot}
 * so reactive bridges see the same context; the manual tenant+MDC restore
 * always runs too, which makes the two paths <em>observationally identical</em>
 * whether or not the accessor has been installed (matrix K03/K04).
 */
public class TenantPropagationTaskDecorator implements TaskDecorator {

    @Override
    public Runnable decorate(Runnable task) {
        TenantId tenant = TenantContext.currentOrNull();
        Map<String, String> mdc = TenantMdc.capture();
        Runnable manual = () -> {
            TenantMdc.restore(mdc);
            try {
                if (tenant != null) {
                    TenantContext.runAs(tenant, task);
                }
                else {
                    task.run();
                }
            }
            finally {
                MDC.clear();
            }
        };
        if (TenantContextPropagationSupport.micrometerPresent()) {
            return MicrometerDelegate.wrapWithSnapshot(manual);
        }
        return manual;
    }

    /** Presence check isolated so this class stays loadable without micrometer. */
    static final class TenantContextPropagationSupport {

        private TenantContextPropagationSupport() {
        }

        static boolean micrometerPresent() {
            try {
                Class.forName("io.micrometer.context.ContextRegistry", false,
                        TenantPropagationTaskDecorator.class.getClassLoader());
                return true;
            }
            catch (ClassNotFoundException ex) {
                return false;
            }
        }
    }

    /** Only class referencing micrometer types; verified when micrometer is present. */
    private static final class MicrometerDelegate {

        static Runnable wrapWithSnapshot(Runnable manual) {
            io.micrometer.context.ContextSnapshotFactory factory =
                    io.micrometer.context.ContextSnapshotFactory.builder().build();
            io.micrometer.context.ContextSnapshot snapshot = factory.captureAll();
            return snapshot.wrap(manual);
        }
    }
}
