package com.lining.vgw.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lining.vgw.common.Result;
import com.lining.vgw.domain.Channel;
import com.lining.vgw.domain.Ledger;
import com.lining.vgw.dto.OverviewVO;
import com.lining.vgw.integration.zlm.ZlmApiClient;
import com.lining.vgw.mapper.ChannelMapper;
import com.lining.vgw.mapper.LedgerMapper;
import com.lining.vgw.service.LedgerService;
import com.lining.vgw.service.StreamSessionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 统计看板接口：数据全部来自业务台账，直观展示"网关干了多少活"。
 */
@Tag(name = "统计看板")
@RestController
@RequestMapping("/api/v1/stats")
@RequiredArgsConstructor
public class StatsController {

    private final ChannelMapper channelMapper;
    private final LedgerMapper ledgerMapper;
    private final LedgerService ledgerService;
    private final StreamSessionService sessionService;
    private final ZlmApiClient zlmApiClient;

    @Operation(summary = "总览统计")
    @GetMapping("/overview")
    public Result<OverviewVO> overview() {
        LocalDateTime todayStart = LocalDate.now().atStartOfDay();

        long channelTotal = channelMapper.selectCount(new LambdaQueryWrapper<>());
        long channelOnline = channelMapper.selectCount(new LambdaQueryWrapper<Channel>()
                .ne(Channel::getStatus, Channel.STATUS_OFFLINE));
        long ledgerTotal = ledgerMapper.selectCount(new LambdaQueryWrapper<Ledger>());

        OverviewVO vo = OverviewVO.builder()
                .channelTotal(channelTotal)
                .channelOnline(channelOnline)
                .runningSessions(sessionService.countRunning())
                .todayPublishCount(sessionService.countStartedSince(todayStart))
                .todayPlayCount(ledgerService.countByType(Ledger.TYPE_PLAY_START, todayStart))
                .todayRejectCount(ledgerService.countByType(Ledger.TYPE_PUBLISH_REJECT, todayStart))
                .ledgerTotal(ledgerTotal)
                .zlmAvailable(zlmApiClient.ping())
                .build();
        return Result.ok(vo);
    }
}
