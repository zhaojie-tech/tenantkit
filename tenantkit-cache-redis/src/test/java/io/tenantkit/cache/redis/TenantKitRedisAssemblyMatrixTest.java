package io.tenantkit.cache.redis;

import java.util.Set;

import io.tenantkit.cache.redis.properties.TenantKitRedisProperties;
import io.tenantkit.core.TenantContext;
import io.tenantkit.core.TenantId;
import io.tenantkit.core.exception.TenantMissingException;
import io.tenantkit.test.HidingClassLoader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.CacheKeyPrefix;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Assembly matrix R01–R12 (cache-redis domain, same discipline as the frozen
 * docs/matrix/assembly-matrix.md: module switch, classpath back-off, binding,
 * opt-in semantics).
 */
@ExtendWith(OutputCaptureExtension.class)
class TenantKitRedisAssemblyMatrixTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(TenantKitRedisAutoConfiguration.class))
            .withUserConfiguration(RedisUserConfig.class);

    /**
     * The user-config mocks are static (shared across contexts in one forked
     * JVM); per-test invocation hygiene keeps every Mockito.verify honest.
     */
    @BeforeEach
    void resetSharedMocks() {
        Mockito.clearInvocations(RedisUserConfig.template(), RedisUserConfig.valueOps());
    }

    /** R01 — default assembly: template wrapper + cache prefix registered, defaults bound. */
    @Test
    void r01_defaultAssemblyRegistersBothBeans() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(TenantRedisTemplate.class);
            assertThat(context).hasSingleBean(TenantCacheKeyPrefix.class);
            TenantKitRedisProperties properties = context.getBean(TenantKitRedisProperties.class);
            assertThat(properties.isEnabled()).isTrue();
            assertThat(properties.isKeyPrefix()).isTrue();
            assertThat(properties.getNoContextPolicy()).isEqualTo(TenantKitRedisProperties.NoContextPolicy.STRICT);
            assertThat(properties.getSharedKeys()).isEmpty();
        });
    }

    /** R02 — tenantRedisTemplate is NOT @Primary (documented opt-in design). */
    @Test
    void r02_tenantRedisTemplateIsNotPrimary() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            BeanDefinition definition = context.getBeanFactory().getBeanDefinition("tenantRedisTemplate");
            assertThat(definition.isPrimary()).as("opt-in by injection, never primary").isFalse();
        });
    }

    /** R03 — spring-data-redis absent: module backs off entirely, user beans unaffected. */
    @Test
    void r03_missingSpringDataRedisBacksOff() {
        runner.withClassLoader(new HidingClassLoader(getClass().getClassLoader(),
                "org.springframework.data.redis.")).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(TenantRedisTemplate.class);
            assertThat(context).doesNotHaveBean(TenantCacheKeyPrefix.class);
            assertThat(context).as("user beans must survive the classpath back-off")
                    .hasBean("tenantKitTestRedisTemplate");
        });
    }

    /** R04 — module switch off: no kit beans, user template unaffected. */
    @Test
    void r04_moduleSwitchDisablesEverything() {
        runner.withPropertyValues("tenantkit.redis.enabled=false").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(TenantRedisTemplate.class);
            assertThat(context).doesNotHaveBean(TenantCacheKeyPrefix.class);
            assertThat(context).as("user beans must survive the module switch")
                    .hasBean("tenantKitTestRedisTemplate");
        });
    }

    /** R05 — no RedisTemplate bean at all: the module assembles nothing, context starts. */
    @Test
    void r05_noRedisTemplateBeanAssemblesNothing() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(TenantKitRedisAutoConfiguration.class))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(TenantRedisTemplate.class);
                    assertThat(context).doesNotHaveBean(TenantCacheKeyPrefix.class);
                });
    }

    /**
     * R06 — template whose key generic is not String (e.g. binary keys): not
     * covariantly assignable to {@code <String, Object>}, so the wrapper is
     * skipped with a warning and the context starts. The functional contract
     * is asserted through the ObjectProvider: no usable TenantRedisTemplate
     * is resolvable (a null-returning factory method leaves a NullBean *name*
     * behind, which type lookups must resolve to nothing).
     */
    @Test
    void r06_nonStringKeyTemplateWarnsAndSkips(CapturedOutput output) {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(TenantKitRedisAutoConfiguration.class))
                .withUserConfiguration(BinaryKeyTemplateUserConfig.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBeanProvider(TenantRedisTemplate.class).getIfAvailable()).isNull();
                });
        assertThat(output.getAll()).contains("no RedisTemplate<String, Object>");
    }

    /** R07 — STRICT/LENIENT bind from the yaml values (relaxed, case-insensitive binding). */
    @Test
    void r07_noContextPolicyBinding() {
        runner.withPropertyValues("tenantkit.redis.no-context-policy=lenient").run(context ->
                assertThat(context.getBean(TenantKitRedisProperties.class).getNoContextPolicy())
                        .isEqualTo(TenantKitRedisProperties.NoContextPolicy.LENIENT));
        runner.withPropertyValues("tenantkit.redis.no-context-policy=strict").run(context ->
                assertThat(context.getBean(TenantKitRedisProperties.class).getNoContextPolicy())
                        .isEqualTo(TenantKitRedisProperties.NoContextPolicy.STRICT));
    }

    /** R08 — sharedKeys binds as a list and reaches the assembled template. */
    @Test
    void r08_sharedKeysBinding() {
        runner.withPropertyValues("tenantkit.redis.shared-keys=app:*,config").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(TenantKitRedisProperties.class).getSharedKeys())
                    .containsExactly("app:*", "config");
            // behavioral probe through the assembled bean: shared key needs no tenant under STRICT
            TenantRedisTemplate template = context.getBean(TenantRedisTemplate.class);
            template.opsForValue().set("app:flag", "on");
            Mockito.verify(RedisUserConfig.valueOps()).set("app:flag", "on");
        });
    }

    /** R09 — key-prefix=false: assembled template degrades to a transparent delegate. */
    @Test
    void r09_keyPrefixFalseIsTransparent() {
        runner.withPropertyValues("tenantkit.redis.key-prefix=false").run(context -> {
            assertThat(context).hasNotFailed();
            TenantRedisTemplate template = context.getBean(TenantRedisTemplate.class);

            Mockito.when(RedisUserConfig.template().keys("probe")).thenReturn(Set.of());

            // no tenant bound, strict policy — transparency means no failure, no rewrite
            template.opsForValue().set("user:1", "v");
            Mockito.verify(RedisUserConfig.valueOps()).set("user:1", "v");
            assertThat(template.keys("probe")).isNotNull();

            // the cache prefix still computes a tenant-less prefix at startup time
            assertThat(context.getBean(TenantCacheKeyPrefix.class).compute("cache")).isEqualTo("cache:");
        });
    }

    /** R10 — user-provided CacheKeyPrefix wins over the kit default. */
    @Test
    void r10_userCacheKeyPrefixWins() {
        runner.withUserConfiguration(CustomPrefixConfig.class).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(CacheKeyPrefix.class))
                    .isInstanceOf(CustomPrefixConfig.StaticPrefix.class)
                    .isNotInstanceOf(TenantCacheKeyPrefix.class);
        });
    }

    /** R11 — strict policy through the assembled bean: prefixed write, TK-1001 without tenant. */
    @Test
    void r11_assembledTemplateEnforcesStrictByDefault() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            TenantRedisTemplate template = context.getBean(TenantRedisTemplate.class);

            TenantContext.runAs(TenantId.of("alpha"), () -> template.opsForValue().set("user:1", "v"));
            Mockito.verify(RedisUserConfig.valueOps()).set("t:alpha:user:1", "v");

            assertThatThrownBy(() -> template.opsForValue().get("user:1"))
                    .isInstanceOf(TenantMissingException.class);
        });
    }

    /** R12 — lenient policy through the assembled bean keeps the warn visible. */
    @Test
    void r12_lenientPolicyWarnsThroughAssembledBean(CapturedOutput output) {
        runner.withPropertyValues("tenantkit.redis.no-context-policy=lenient").run(context -> {
            assertThat(context).hasNotFailed();
            TenantRedisTemplate template = context.getBean(TenantRedisTemplate.class);

            template.opsForValue().set("user:1", "v");
            Mockito.verify(RedisUserConfig.valueOps()).set("user:1", "v");
        });
        assertThat(output.getAll()).contains("no tenant", "lenient");
    }

    @Configuration(proxyBeanMethods = false)
    static class RedisUserConfig {

        private static final RedisTemplate<String, Object> TEMPLATE = Mockito.mock(RedisTemplate.class);
        private static final ValueOperations<String, Object> VALUE_OPS = Mockito.mock(ValueOperations.class);

        static RedisTemplate<String, Object> template() {
            return TEMPLATE;
        }

        static ValueOperations<String, Object> valueOps() {
            return VALUE_OPS;
        }

        RedisUserConfig() {
            Mockito.when(TEMPLATE.opsForValue()).thenReturn(VALUE_OPS);
        }

        @Bean
        RedisTemplate<String, Object> tenantKitTestRedisTemplate() {
            return TEMPLATE;
        }
    }

    /**
     * R13 — the R4 P0-2 regression test: with Boot's REAL DataRedisAutoConfiguration
     * in the context (and no user template), our afterName ordering must let
     * {@code @ConditionalOnBean(RedisTemplate)} see Boot's template and assemble
     * {@code tenantRedisTemplate}. Pre-fix this module silently no-opped for every
     * consumer (the condition was evaluated before Boot created the template).
     * No Redis server needed: Lettuce connects lazily, the context simply must start.
     */
    @Test
    void r13_bootDefaultRedisTemplateIsWrapped() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        TenantKitRedisAutoConfiguration.class,
                        org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration.class))
                .withPropertyValues("spring.data.redis.host=localhost", "spring.data.redis.port=6379")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).as("Boot's own redisTemplate must be visible to the kit")
                            .hasBean("stringRedisTemplate");
                    assertThat(context).as("tenant template must be assembled from Boot's default template")
                            .hasBean("tenantRedisTemplate");
                });
    }

    /**
     * A template with binary keys ({@code RedisTemplate<byte[], Object>}):
     * not assignable to {@code RedisTemplate<String, Object>}, so the kit must
     * not wrap it — and must not fail startup either.
     */
    @Configuration(proxyBeanMethods = false)
    static class BinaryKeyTemplateUserConfig {

        @Bean
        @SuppressWarnings("unchecked")
        RedisTemplate<byte[], Object> tenantKitTestBinaryTemplate() {
            return (RedisTemplate<byte[], Object>) Mockito.mock(RedisTemplate.class);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class CustomPrefixConfig {

        static final class StaticPrefix implements CacheKeyPrefix {

            @Override
            public String compute(String cacheName) {
                return "static:" + cacheName + ":";
            }
        }

        @Bean
        CacheKeyPrefix tenantKitTestCacheKeyPrefix() {
            return new StaticPrefix();
        }
    }
}
