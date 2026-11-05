package com.lining.vgw.service;

import com.lining.vgw.common.AccessScope;
import com.lining.vgw.common.BizException;
import com.lining.vgw.common.ErrorCode;
import com.lining.vgw.domain.Channel;
import com.lining.vgw.domain.Ledger;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 通道级授权的统一判定点。
 *
 * <p>所有"针对某一路通道"的接口（播放地址、录像、拉流、单通道查询/修改）都先走这里，
 * 保证越权判定只有一份实现，不会因为接口新增而漏掉。
 *
 * <p>判定依据来自 {@link AccessScope}：客户端 {@code allowed_channels} 为空 = 不限，
 * 非空则只放行列出的通道 ID。
 *
 * <p>越权不是静默失败：一律记一笔台账（resultCode=2003）。审计时"被拒绝的访问"
 * 往往比"成功的访问"更能说明问题。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChannelAccessService {

    /** 台账里的结果码：无权访问该通道 */
    public static final int RESULT_FORBIDDEN = 2003;

    private final LedgerService ledgerService;

    /**
     * 断言当前客户端可以操作该通道，否则记台账并抛 401。
     *
     * @param action 动作名，用于拼提示语，如 "访问" / "操作"
     */
    public void assertAccess(Channel channel, String action) {
        if (channel == null || AccessScope.canAccess(channel.getId(), channel.getDeviceId())) {
            return;
        }
        String msg = "没有权限" + action + "这个通道（" + channel.getChannelCode() + "）";
        ledgerService.record(Ledger.TYPE_API_CALL, channel.getDeviceId(), channel.getId(), null,
                "API", null, RESULT_FORBIDDEN, msg, null);
        log.warn("通道级越权被拒: client={} channel={}", com.lining.vgw.common.ApiKeyContext.currentAppKey(),
                channel.getChannelCode());
        throw new BizException(ErrorCode.UNAUTHORIZED, msg);
    }
}