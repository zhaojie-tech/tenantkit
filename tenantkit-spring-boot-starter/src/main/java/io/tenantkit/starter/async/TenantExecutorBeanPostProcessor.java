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
package io.tenantkit.starter.async;

import java.util.List;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.core.task.TaskDecorator;
import org.springframework.core.task.support.CompositeTaskDecorator;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import io.tenantkit.core.async.TenantPropagationTaskDecorator;

/**
 * Wraps Spring-managed task executors with the
 * {@link TenantPropagationTaskDecorator} on
 * <strong>{@code postProcessBeforeInitialization}</strong> &mdash;
 * {@code ThreadPoolTaskExecutor} builds its internal pool inside
 * {@code afterPropertiesSet}, so a decorator set after that phase would never
 * take effect (R1 correction, DESIGN &sect;6.3).
 *
 * <p>Coverage boundary, stated as-is: only {@code ThreadPoolTaskExecutor} and
 * {@code SimpleAsyncTaskExecutor} beans. Raw {@code ExecutorService} beans and
 * {@code ForkJoinPool.commonPool} need {@code TenantExecutors.wrap}.
 *
 * <p>{@code decorateUserPools=false} (default) decorates only the bean named
 * {@code applicationTaskExecutor}; {@code true} wraps every matching executor
 * bean. Spring 7 removed the executor decorator readers, so the kit cannot
 * <em>merge</em> with a previously set decorator; when a single user
 * {@link TaskDecorator} bean exists, it is composed
 * ({@link CompositeTaskDecorator}, tenant decorator innermost / user decorator
 * outermost) so neither is lost (R4 P2). More than one user decorator bean:
 * register them combined yourself &mdash; the kit then applies only its own.
 *
 * <p>Processing is idempotent by construction ({@code setTaskDecorator}
 * replaces; re-application is a no-op), so prototype/recreated executor
 * instances are decorated too (R4 P2: the previous per-bean-name guard
 * silently skipped them).
 */
public class TenantExecutorBeanPostProcessor implements BeanPostProcessor {

    /** The Boot auto-configured application executor, always decorated. */
    public static final String APPLICATION_TASK_EXECUTOR = "applicationTaskExecutor";

    private final boolean decorateUserPools;
    private final ObjectProvider<TaskDecorator> userDecorators;
    private final TaskDecorator tenantDecorator = new TenantPropagationTaskDecorator();

    public TenantExecutorBeanPostProcessor(boolean decorateUserPools) {
        this(decorateUserPools, null);
    }

    public TenantExecutorBeanPostProcessor(boolean decorateUserPools, ObjectProvider<TaskDecorator> userDecorators) {
        this.decorateUserPools = decorateUserPools;
        this.userDecorators = userDecorators;
    }

    @Override
    public Object postProcessBeforeInitialization(Object bean, String beanName) {
        if ((bean instanceof ThreadPoolTaskExecutor || bean instanceof SimpleAsyncTaskExecutor)
                && (decorateUserPools || APPLICATION_TASK_EXECUTOR.equals(beanName))) {
            TaskDecorator effective = effectiveDecorator();
            if (bean instanceof ThreadPoolTaskExecutor executor) {
                executor.setTaskDecorator(effective);
            }
            else {
                ((SimpleAsyncTaskExecutor) bean).setTaskDecorator(effective);
            }
        }
        return bean;
    }

    private TaskDecorator effectiveDecorator() {
        if (userDecorators == null) {
            return tenantDecorator;
        }
        List<TaskDecorator> user = userDecorators.orderedStream()
                .filter(candidate -> !(candidate instanceof TenantPropagationTaskDecorator))
                .toList();
        if (user.isEmpty()) {
            return tenantDecorator;
        }
        if (user.size() == 1) {
            return new CompositeTaskDecorator(List.of(tenantDecorator, user.get(0)));
        }
        // ambiguous composition: keep the kit decorator and let the user compose explicitly
        return tenantDecorator;
    }
}
