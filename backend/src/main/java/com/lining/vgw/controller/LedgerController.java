package com.lining.vgw.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.lining.vgw.common.PageQuery;
import com.lining.vgw.common.PageResult;
import com.lining.vgw.common.Result;
import com.lining.vgw.domain.Ledger;
import com.lining.vgw.service.LedgerService;
import io.swagger.v3.oas.annotations.Operation;
import org.springdoc.core.annotations.ParameterObject;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 业务台账查询接口（核心）。
 */
@Tag(name = "业务台账")
@RestController
@RequestMapping("/api/v1/ledger")
@RequiredArgsConstructor
public class LedgerController {

    private final LedgerService ledgerService;

    @Operation(summary = "分页查询台账（支持通道、设备、客户端、事件类型、时间范围）")
    @GetMapping
    public Result<PageResult<Ledger>> page(
            @ParameterObject PageQuery query,
            @RequestParam(required = false) Long channelId,
            @RequestParam(required = false) Long deviceId,
            @RequestParam(required = false) Long clientId,
            @RequestParam(required = false) String eventType,
            // 整组筛选：逗号分隔的事件类型列表，如 PUBLISH_START,PUBLISH_STOP
            @RequestParam(required = false) String eventTypes,
            @RequestParam(required = false)
            @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime beginTime,
            @RequestParam(required = false)
            @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime endTime) {
        List<String> typeList = (eventTypes == null || eventTypes.isBlank())
                ? List.of()
                : Arrays.stream(eventTypes.split(",")).map(String::trim)
                        .filter(s -> !s.isEmpty()).collect(Collectors.toList());

        IPage<Ledger> result = ledgerService.page(query.safePage(), query.safeSize(),
                channelId, deviceId, clientId, eventType, typeList, beginTime, endTime);
        return Result.ok(PageResult.of(result.getTotal(), result.getCurrent(), result.getSize(), result.getRecords()));
    }
}
