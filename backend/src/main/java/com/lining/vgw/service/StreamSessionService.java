package com.lining.vgw.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.lining.vgw.domain.Channel;
import com.lining.vgw.domain.StreamSession;
import com.lining.vgw.mapper.StreamSessionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 流会话服务：管理一次推流的完整生命周期，并自动计算持续时长。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StreamSessionService {

    private final StreamSessionMapper sessionMapper;

    /** 推流开始 → 开启会话 */
    public StreamSession open(Channel channel, String clientIp, Integer trackCount) {
        // 幂等：如果已有进行中的会话（ZLM 重推/回调重试），先关掉旧的
        closeByStreamKey(channel.streamKey(), "TIMEOUT", StreamSession.STATUS_ABNORMAL_END);

        StreamSession session = new StreamSession();
        session.setSessionNo("SS" + LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
                + String.format("%04d", ThreadLocalRandom.current().nextInt(10000)));
        session.setChannelId(channel.getId());
        session.setStreamKey(channel.streamKey());
        session.setClientIp(clientIp);
        session.setTrackCount(trackCount == null ? 1 : trackCount);
        session.setStartTime(LocalDateTime.now());
        session.setStatus(StreamSession.STATUS_RUNNING);
        sessionMapper.insert(session);
        log.info("流会话开启: {} channelId={} ip={}", session.getSessionNo(), channel.getId(), clientIp);
        return session;
    }

    /**
     * 结束指定流的所有进行中会话。
     *
     * <p>采用"先取最后一次会话 + 原子更新"的方式：只有真正把 status 从 1 改成结束态的那次调用
     * 才返回会话对象，其余并发调用返回 null，避免 ZLM 多协议事件造成台账重复记账。
     */
    public StreamSession closeByStreamKey(String streamKey, String endReason, int status) {
        StreamSession last = sessionMapper.selectOne(new LambdaQueryWrapper<StreamSession>()
                .eq(StreamSession::getStreamKey, streamKey)
                .eq(StreamSession::getStatus, StreamSession.STATUS_RUNNING)
                .orderByDesc(StreamSession::getId)
                .last("limit 1"));
        if (last == null) {
            return null;
        }
        LocalDateTime end = LocalDateTime.now();
        int affected = sessionMapper.closeRunning(streamKey, status, endReason, end);
        if (affected == 0) {
            // 并发情况下已被别的回调关闭，本次不重复记账
            return null;
        }
        last.setEndTime(end);
        last.setDurationSec((int) Duration.between(last.getStartTime(), end).getSeconds());
        last.setEndReason(endReason);
        last.setStatus(status);
        log.info("流会话结束: {} 时长={}s 原因={}", last.getSessionNo(), last.getDurationSec(), endReason);
        return last;
    }

    /** 查某一路流是否有进行中的会话（判断这条流是否已经开过户口） */
    public StreamSession findRunningByStreamKey(String streamKey) {
        return sessionMapper.selectOne(new LambdaQueryWrapper<StreamSession>()
                .eq(StreamSession::getStreamKey, streamKey)
                .eq(StreamSession::getStatus, StreamSession.STATUS_RUNNING)
                .orderByDesc(StreamSession::getId)
                .last("limit 1"));
    }

    /** ZLM 重启等场景：把所有进行中的会话标记为异常结束 */
    public int markAllRunningAbnormal(String endReason) {
        List<StreamSession> running = sessionMapper.selectList(new LambdaQueryWrapper<StreamSession>()
                .eq(StreamSession::getStatus, StreamSession.STATUS_RUNNING));
        LocalDateTime now = LocalDateTime.now();
        for (StreamSession s : running) {
            s.setEndTime(now);
            s.setDurationSec((int) Duration.between(s.getStartTime(), now).getSeconds());
            s.setEndReason(endReason);
            s.setStatus(StreamSession.STATUS_ABNORMAL_END);
            sessionMapper.updateById(s);
        }
        if (!running.isEmpty()) {
            log.warn("已将 {} 个进行中会话标记为异常结束: {}", running.size(), endReason);
        }
        return running.size();
    }

    /** 兜底扫描：长时间无心跳的会话判定为异常断流 */
    public int scanStale(int staleSeconds) {
        LocalDateTime deadline = LocalDateTime.now().minusSeconds(staleSeconds);
        List<StreamSession> stale = sessionMapper.selectList(new LambdaQueryWrapper<StreamSession>()
                .eq(StreamSession::getStatus, StreamSession.STATUS_RUNNING)
                .lt(StreamSession::getUpdateTime, deadline));
        for (StreamSession s : stale) {
            s.setEndTime(LocalDateTime.now());
            s.setDurationSec((int) Duration.between(s.getStartTime(), LocalDateTime.now()).getSeconds());
            s.setEndReason("TIMEOUT");
            s.setStatus(StreamSession.STATUS_ABNORMAL_END);
            sessionMapper.updateById(s);
        }
        return stale.size();
    }

    public long countRunning() {
        return sessionMapper.selectCount(new LambdaQueryWrapper<StreamSession>()
                .eq(StreamSession::getStatus, StreamSession.STATUS_RUNNING));
    }

    public long countStartedSince(LocalDateTime since) {
        return sessionMapper.selectCount(new LambdaQueryWrapper<StreamSession>()
                .ge(StreamSession::getStartTime, since));
    }

    public List<StreamSession> recent(int limit) {
        return sessionMapper.selectList(new LambdaQueryWrapper<StreamSession>()
                .orderByDesc(StreamSession::getStartTime)
                .last("limit " + Math.max(1, Math.min(limit, 100))));
    }
}
