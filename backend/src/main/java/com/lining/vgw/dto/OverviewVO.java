package com.lining.vgw.dto;

import lombok.Builder;
import lombok.Data;

/**
 * 统计看板数据。
 */
@Data
@Builder
public class OverviewVO {

    /** 通道总数 */
    private long channelTotal;

    /** 在线通道数 */
    private long channelOnline;

    /** 进行中的流会话数 */
    private long runningSessions;

    /** 今日推流次数 */
    private long todayPublishCount;

    /** 今日播放次数 */
    private long todayPlayCount;

    /** 今日被拒绝的推流次数 */
    private long todayRejectCount;

    /** 业务台账总条数 */
    private long ledgerTotal;

    /** 媒体服务器是否可用 */
    private boolean zlmAvailable;
}
