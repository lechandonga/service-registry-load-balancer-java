package com.github.lechandonga.serviceregistryloadbalancerjava.support;

import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BusinessTestLoggingExtension implements BeforeEachCallback, AfterEachCallback {
    private static final Logger log = LoggerFactory.getLogger("BUSINESS_COVERAGE");

    @Override
    public void beforeEach(ExtensionContext context) {
        log.info("[业务={}] 用例={} 结果=START", business(context), caseName(context));
    }

    @Override
    public void afterEach(ExtensionContext context) {
        String result = context.getExecutionException().isPresent() ? "FAIL" : "PASS";
        log.info("[业务={}] 用例={} 结果={}", business(context), caseName(context), result);
    }

    private static String business(ExtensionContext context) {
        String className = context.getRequiredTestClass().getSimpleName();
        return switch (className) {
            case "RegistrationLifecycleTest" -> "注册生命周期";
            case "LoadBalancerTest" -> "并发选路与策略语义";
            case "HealthProbeTest" -> "探测抖动摘除与恢复";
            default -> className;
        };
    }

    private static String caseName(ExtensionContext context) {
        return context.getRequiredTestMethod().getName();
    }
}
