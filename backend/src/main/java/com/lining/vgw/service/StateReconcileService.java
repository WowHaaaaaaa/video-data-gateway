package com.lining.vgw.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lining.vgw.domain.Channel;
import com.lining.vgw.domain.Ledger;
import com.lining.vgw.domain.StreamSession;
import com.lining.vgw.integration.zlm.ZlmApiClient;
import com.lining.vgw.integration.zlm.dto.MediaInfo;
import com.lining.vgw.mapper.ChannelMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 状态对账服务（Reconciliation）：用媒体服务器的"真实流列表"校正我们的数据库状态。
 *
 * <p><b>为什么需要它：</b>
 * 通道状态是靠 ZLM 的 webhook 事件维护的（on_publish / on_stream_changed）。
 * 但事件可能丢失 —— 例如网关正在重启、ZLM 只重试一次（retry=1）就放弃、
 * 推流进程被强制杀死等。事件一丢，数据库就会永远停在"推流中"，形成僵尸状态。
 *
 * <p><b>设计原则：</b>事件驱动保证实时性，定期对账保证最终一致性。
 * 两者配合，才能让"外部系统的状态"可信。
 *
 * <p><b>两个方向都会纠正：</b>
 * <ol>
 *   <li>DB 说在推流、ZLM 实际没有 → 置离线 + 结算会话 + 记台账（source=TIMER）</li>
 *   <li>ZLM 有流、DB 却不是推流中 → 修正为推流中 + 记台账</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StateReconcileService {

    private final ChannelMapper channelMapper;
    private final ChannelService channelService;
    private final StreamSessionService sessionService;
    private final LedgerService ledgerService;
    private final ZlmApiClient zlmApiClient;

    /** 启动 12 秒后跑第一次，之后每 30 秒一次 */
    @Scheduled(initialDelay = 12000, fixedDelay = 30000)
    public void reconcile() {
        List<MediaInfo> medias;
        try {
            medias = zlmApiClient.getMediaList();
        } catch (RuntimeException e) {
            // 媒体服务器暂时不可达时不纠正，避免把全部通道误判为离线
            log.debug("对账跳过：媒体服务器不可达（{}）", e.getMessage());
            return;
        }

        // 只认源（推流 1/2、拉流 3/4/5/7），转协议产物不算
        Set<String> liveStreams = medias.stream()
                .filter(MediaInfo::isOrigin)
                .map(MediaInfo::streamKey)
                .collect(Collectors.toSet());

        int fixedOffline = fixZombiePublishing(liveStreams);
        int fixedOnline = fixMissedPublish(liveStreams);

        if (fixedOffline > 0 || fixedOnline > 0) {
            log.warn("对账完成：纠正僵尸推流 {} 个，补登在线 {} 个", fixedOffline, fixedOnline);
        }
    }

    /** 方向一：数据库说"推流中"，但媒体服务器已无此流 */
    private int fixZombiePublishing(Set<String> liveStreams) {
        List<Channel> publishing = channelMapper.selectList(new LambdaQueryWrapper<Channel>()
                .eq(Channel::getStatus, Channel.STATUS_PUBLISHING));

        int fixed = 0;
        for (Channel ch : publishing) {
            String key = ch.streamKey();
            if (liveStreams.contains(key)) {
                continue;
            }
            // 先结算会话，再改通道状态，最后记账
            StreamSession session = sessionService.closeByStreamKey(
                    key, "TIMEOUT", StreamSession.STATUS_ABNORMAL_END);
            channelService.markOffline(ch.getId());
            channelService.refreshDeviceStatus(ch.getDeviceId());

            String msg = "对账纠正：通道标记为在线，但媒体服务器已无此流（断流通知丢失），已置为离线"
                    + (session == null ? "" : "；会话结算时长 " + session.getDurationSec() + " 秒");
            ledgerService.record(Ledger.TYPE_STATE_RECONCILE, ch.getDeviceId(), ch.getId(),
                    session == null ? null : session.getId(), "TIMER", null, 0, msg, null);
            log.warn("对账纠正僵尸通道: {} （会话 {}）", key, session == null ? "无" : session.getSessionNo());
            fixed++;
        }
        return fixed;
    }

    /** 方向二：媒体服务器有流，但数据库不是"推流中"（说明 on_publish 丢了） */
    private int fixMissedPublish(Set<String> liveStreams) {
        if (liveStreams.isEmpty()) {
            return 0;
        }
        List<Channel> all = channelMapper.selectList(new LambdaQueryWrapper<Channel>());
        int fixed = 0;
        for (Channel ch : all) {
            String key = ch.streamKey();
            if (!liveStreams.contains(key)) {
                continue;
            }
            if (Objects.equals(ch.getStatus(), Channel.STATUS_PUBLISHING)) {
                continue;
            }
            channelService.markPublishing(ch.getId());
            channelService.refreshDeviceStatus(ch.getDeviceId());
            ledgerService.record(Ledger.TYPE_STATE_RECONCILE, ch.getDeviceId(), ch.getId(), null,
                    "TIMER", null, 0, "对账纠正：媒体服务器存在该流，但通道状态显示离线，已修正为在线", null);
            log.warn("对账补登在线通道: {}", key);
            fixed++;
        }
        return fixed;
    }
}