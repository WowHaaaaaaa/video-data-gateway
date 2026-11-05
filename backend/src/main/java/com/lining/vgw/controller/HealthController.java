package com.lining.vgw.controller;

import com.lining.vgw.common.Result;
import com.lining.vgw.integration.zlm.ZlmApiClient;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 健康检查：同时探测数据库与媒体服务器。
 */
@Tag(name = "健康检查")
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class HealthController {

    private final JdbcTemplate jdbcTemplate;
    private final ZlmApiClient zlmApiClient;

    @Operation(summary = "健康检查")
    @GetMapping("/health")
    public Result<Map<String, Object>> health() {
        Map<String, Object> data = new LinkedHashMap<>();
        boolean dbOk;
        try {
            jdbcTemplate.queryForObject("select 1", Integer.class);
            dbOk = true;
        } catch (RuntimeException e) {
            dbOk = false;
        }
        boolean zlmOk = zlmApiClient.ping();
        data.put("database", dbOk);
        data.put("zlm", zlmOk);
        data.put("status", dbOk && zlmOk ? "UP" : "DEGRADED");
        return Result.ok(data);
    }
}
