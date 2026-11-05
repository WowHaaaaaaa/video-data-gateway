package com.lining.vgw.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lining.vgw.common.ApiKeyContext;
import com.lining.vgw.domain.Channel;
import com.lining.vgw.domain.Ledger;
import com.lining.vgw.mapper.ChannelMapper;
import com.lining.vgw.mapper.LedgerMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 业务台账服务：所有业务事件都在这里落一条流水。
 *
 * <p>台账是"账房先生"的核心：不参与业务决策，只负责如实记录，保证事后可追溯。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LedgerService {

    private static final DateTimeFormatter BIZ_NO_FMT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final LedgerMapper ledgerMapper;
    private final ChannelMapper channelMapper;
    private final ObjectMapper objectMapper;
    /** 授权范围解析（设备级授权要展开成通道 id） */
    private final ChannelScopeService channelScopeService;

    /**
     * 记录一条业务台账。
     *
     * @param eventType  事件类型，见 {@link Ledger} 常量
     * @param channelId  通道 ID（可为空，例如未注册的推流）
     * @param deviceId   设备 ID（可为空）
     * @param refId      关联 ID（会话/播放/录像）
     * @param source     来源：ZLM / API / TIMER / MANUAL
     * @param clientIp   客户端 IP
     * @param resultCode 0 成功
     * @param resultMsg  失败原因
     * @param detail     事件明细对象，会序列化成 JSON
     */
    public Ledger record(String eventType, Long deviceId, Long channelId, Long refId, String source,
                         String clientIp, int resultCode, String resultMsg, Object detail) {
        Ledger ledger = new Ledger();
        ledger.setBizNo(nextBizNo());
        ledger.setEventType(eventType);
        ledger.setEventTime(LocalDateTime.now());
        ledger.setDeviceId(deviceId);
        ledger.setChannelId(channelId);
        // 快照通道标识：台账是审计账，必须固化"事发当时"的编号与流标识，
        // 否则通道改名/删除/重名都会让历史台账失真
        if (channelId != null) {
            Channel snap = channelMapper.selectById(channelId);
            if (snap != null) {
                ledger.setChannelCode(snap.getChannelCode());
                ledger.setStreamKey(snap.streamKey());
            }
        }
        ledger.setRefId(refId);
        // 自动带上"谁调的"：经过了 api-key 鉴权的请求，上下文里就有客户端；
        // ZLM 回调 / 定时任务没有客户端，留空。
        ledger.setClientId(ApiKeyContext.currentClientId());
        // 来源判定：界面操作虽然也走 HTTP 接口，但语义上是"人工操作"，
        // 因此在入口用 X-Request-From 区分，台账记为 MANUAL 而不是 API
        String finalSource = (source == null ? "ZLM" : source);
        if ("API".equals(finalSource) && ApiKeyContext.isFromUi()) {
            finalSource = "MANUAL";
        }
        ledger.setSource(finalSource);
        ledger.setClientIp(clientIp);
        ledger.setResultCode(resultCode);
        ledger.setResultMsg(truncate(resultMsg, 255));
        ledger.setDetail(toJson(detail));
        try {
            ledgerMapper.insert(ledger);
        } catch (RuntimeException e) {
            // 台账写入失败不能影响主流程（例如重复的 biz_no），只记录日志
            log.error("台账写入失败: type={}, channelId={}", eventType, channelId, e);
        }
        return ledger;
    }

    /** 条件分页查询台账 */
    public IPage<Ledger> page(long page, long size, Long channelId, Long deviceId, Long clientId,
                              String eventType, List<String> eventTypes,
                              LocalDateTime begin, LocalDateTime end) {
        Page<Ledger> p = new Page<>(page, size);
        // 通道级隔离：null=不限，否则只查授权通道
        List<Long> scope = channelScopeService.effectiveChannelIdsOrNull();
        var wrapper = new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Ledger>()
                .in(scope != null, Ledger::getChannelId, scope)
                .eq(channelId != null, Ledger::getChannelId, channelId)
                .eq(deviceId != null, Ledger::getDeviceId, deviceId)
                .eq(clientId != null, Ledger::getClientId, clientId)
                .eq(eventType != null && !eventType.isBlank(), Ledger::getEventType, eventType)
                // 整组筛选：按前缀分组后一次性查多个事件类型
                .in(eventTypes != null && !eventTypes.isEmpty(), Ledger::getEventType, eventTypes)
                .ge(begin != null, Ledger::getEventTime, begin)
                .le(end != null, Ledger::getEventTime, end)
                .orderByDesc(Ledger::getEventTime)
                .orderByDesc(Ledger::getId);
        return ledgerMapper.selectPage(p, wrapper);
    }

    public long countByType(String eventType, LocalDateTime begin) {
        var wrapper = new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Ledger>()
                .eq(Ledger::getEventType, eventType)
                .ge(Ledger::getEventTime, begin);
        return ledgerMapper.selectCount(wrapper);
    }

    private String nextBizNo() {
        return "LG" + LocalDateTime.now().format(BIZ_NO_FMT)
                + String.format("%04d", ThreadLocalRandom.current().nextInt(10000));
    }

    private String toJson(Object o) {
        if (o == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            return "{\"error\":\"serialize failed\"}";
        }
    }

    private String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
