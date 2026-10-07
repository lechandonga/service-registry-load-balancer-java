package com.github.lechandonga.serviceregistryloadbalancerjava.tests;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 业务四：HTTP 入口端到端
 * 覆盖：注册、重复注册 409、续约、选路命中、注销后不可选、状态历史查询、错误原因码可区分。
 */
@SpringBootTest(properties = {
        "slb.lease-ttl-millis=60000",
        "slb.lease-sweep-millis=600000",
        "slb.probe-interval-millis=600000"
})
@AutoConfigureMockMvc
@ExtendWith(TestLogExtension.class)
class RegistryApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("REST 全链路：注册->选路->重复注册拒绝->注销->状态可查")
    void fullHttpLifecycle() throws Exception {
        String body = "{\"instanceId\":\"web-1\",\"host\":\"127.0.0.1\",\"port\":19001,\"weight\":1}";
        mockMvc.perform(post("/api/services/web/instances")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.registered").value(true))
                .andExpect(jsonPath("$.leaseTtlMillis").value(60000));

        String body2 = "{\"instanceId\":\"web-2\",\"host\":\"127.0.0.1\",\"port\":19002,\"weight\":1}";
        mockMvc.perform(post("/api/services/web/instances")
                        .contentType(MediaType.APPLICATION_JSON).content(body2))
                .andExpect(status().isOk());

        // 重复注册：409 + ALREADY_REGISTERED
        mockMvc.perform(post("/api/services/web/instances")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("ALREADY_REGISTERED"));

        // 续约
        mockMvc.perform(post("/api/services/web/instances/web-1/renew")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.renewed").value(true));

        // 轮询选路命中两个实例之一
        mockMvc.perform(post("/api/services/web/route")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"strategy\":\"ROUND_ROBIN\",\"maxAttempts\":3}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.selected").exists())
                .andExpect(jsonPath("$.explanation").exists());

        // 一致性哈希需要 key
        mockMvc.perform(post("/api/services/web/route")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"strategy\":\"CONSISTENT_HASH\",\"key\":\"user-42\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.selected.instanceId").exists());

        // 注销 web-1
        mockMvc.perform(post("/api/services/web/instances/web-1/deregister"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deregistered").value(true));

        // 再注销：404 INSTANCE_NOT_FOUND
        mockMvc.perform(post("/api/services/web/instances/web-1/deregister"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("INSTANCE_NOT_FOUND"));

        // 可路由列表只剩 web-2
        mockMvc.perform(get("/api/services/web/instances"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.snapshotVersion").isNumber())
                .andExpect(jsonPath("$.eligibleInstances.length()").value(1))
                .andExpect(jsonPath("$.eligibleInstances[0].instanceId").value("web-2"));

        // 历史仍可查：状态 DEREGISTERED，且含 REGISTERED/RENEWED/DEREGISTERED 事件
        mockMvc.perform(get("/api/services/web/instances/web-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lifecycle").value("DEREGISTERED"))
                .andExpect(jsonPath("$.serving").value(false))
                .andExpect(jsonPath("$.history[0].type").value("REGISTERED"));

        // 未知服务选路：空结论而非异常
        mockMvc.perform(post("/api/services/ghost/route")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.selected").doesNotExist());
    }
}
