package com.github.lechandonga.serviceregistryloadbalancerjava.tests;

import org.junit.jupiter.api.extension.AfterTestExecutionCallback;
import org.junit.jupiter.api.extension.BeforeTestExecutionCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 让单测日志能看出覆盖了哪些业务、每个用例的名称与结果：
 * 在每个用例前后打印统一格式的业务横幅。
 */
public class TestLogExtension implements BeforeTestExecutionCallback, AfterTestExecutionCallback {

    private static final Logger log = LoggerFactory.getLogger("TEST-COVERAGE");

    @Override
    public void beforeTestExecution(ExtensionContext context) {
        log.info("");
        log.info("==================== 业务用例开始 | {}#{} ====================",
                simpleName(context), context.getDisplayName());
    }

    @Override
    public void afterTestExecution(ExtensionContext context) {
        String result = context.getExecutionException().isPresent() ? "FAILED" : "PASSED";
        log.info("-------------------- 业务用例结果 | {}#{} -> {} --------------------",
                simpleName(context), context.getDisplayName(), result);
        log.info("");
    }

    private String simpleName(ExtensionContext context) {
        return context.getTestClass().map(Class::getSimpleName).orElse("?");
    }
}
