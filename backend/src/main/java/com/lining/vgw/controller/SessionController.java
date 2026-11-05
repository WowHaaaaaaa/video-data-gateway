package com.lining.vgw.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lining.vgw.common.PageQuery;
import com.lining.vgw.common.PageResult;
import com.lining.vgw.common.Result;
import com.lining.vgw.domain.StreamSession;
import com.lining.vgw.mapper.StreamSessionMapper;
import com.lining.vgw.service.ChannelScopeService;
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
import java.util.List;

/**
 * 流会话查询：前端"业务台账 › 流会话"页签的数据源。
 *
 * <p>与 {@code /ledger} 的区别：这里是<b>聚合视图</b>（一次推流一行，含时长与结束原因），
 * 台账是<b>流水视图</b>（一个事件一行）。
 */
@Tag(name = "流会话")
@RestController
@RequestMapping("/api/v1/sessions")
@RequiredArgsConstructor
public class SessionController {

    private final StreamSessionMapper sessionMapper;
    private final ChannelScopeService channelScopeService;

    @Operation(summary = "分页查询流会话")
    @GetMapping
    public Result<PageResult<StreamSession>> page(
            @ParameterObject PageQuery query,
            @RequestParam(required = false) Long channelId,
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false)
            @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime beginTime,
            @RequestParam(required = false)
            @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime endTime) {

        Page<StreamSession> page = new Page<>(query.safePage(), query.safeSize());
        // 通道级隔离：null=不限，否则只查授权通道
        List<Long> scope = channelScopeService.effectiveChannelIdsOrNull();
        LambdaQueryWrapper<StreamSession> wrapper = new LambdaQueryWrapper<StreamSession>()
                .in(scope != null, StreamSession::getChannelId, scope)
                .eq(channelId != null, StreamSession::getChannelId, channelId)
                .eq(status != null, StreamSession::getStatus, status)
                .ge(beginTime != null, StreamSession::getStartTime, beginTime)
                .le(endTime != null, StreamSession::getStartTime, endTime)
                .orderByDesc(StreamSession::getStartTime)
                .orderByDesc(StreamSession::getId);

        IPage<StreamSession> result = sessionMapper.selectPage(page, wrapper);
        return Result.ok(PageResult.of(result.getTotal(), result.getCurrent(), result.getSize(), result.getRecords()));
    }
}
