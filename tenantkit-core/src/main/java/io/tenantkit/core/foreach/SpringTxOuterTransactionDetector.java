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
package io.tenantkit.core.foreach;

/**
 * Detector for an active outer transaction, loaded only when spring-tx is on
 * the classpath (core carries spring-tx as an optional dependency; matrix K02
 * covers the absent case where detection simply never runs).
 */
final class SpringTxOuterTransactionDetector {

    private SpringTxOuterTransactionDetector() {
    }

    /** {@code true} when an actual transaction is active on this thread. */
    static boolean outerTransactionActive() {
        return org.springframework.transaction.support.TransactionSynchronizationManager
                .isActualTransactionActive();
    }

    /** {@code true} when spring-tx is loadable from the given loader. */
    static boolean isPresent(ClassLoader loader) {
        try {
            Class.forName("org.springframework.transaction.support.TransactionSynchronizationManager", false, loader);
            return true;
        }
        catch (ClassNotFoundException ex) {
            return false;
        }
    }
}
