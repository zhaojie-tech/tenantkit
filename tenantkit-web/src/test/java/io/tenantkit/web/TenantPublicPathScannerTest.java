package io.tenantkit.web;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import static org.assertj.core.api.Assertions.assertThat;

/** W36 (unit half): the scanner reads @TenantPublic paths off the handler mapping at startup. */
class TenantPublicPathScannerTest {

    @Test
    void scanCollectsAnnotatedPathsOnly() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(MvcConfig.class)) {
            RequestMappingHandlerMapping mapping = context.getBean(RequestMappingHandlerMapping.class);
            TenantResolutionFilter.TenantPublicPaths paths = TenantPublicPathScanner.scan(mapping);
            assertThat(paths.isPublic("/public/echo")).isTrue();
            assertThat(paths.isPublic("/api/x")).isFalse();
            assertThat(paths.isPublic("/public/other")).isFalse();
        }
    }

    @RestController
    static class PublicController {

        @TenantPublic
        @GetMapping("/public/echo")
        String echo() {
            return "echo";
        }

        @GetMapping("/api/x")
        String api() {
            return "api";
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class MvcConfig {

        @Bean
        RequestMappingHandlerMapping handlerMapping() {
            return new RequestMappingHandlerMapping();
        }

        @Bean
        PublicController publicController() {
            return new PublicController();
        }
    }
}
