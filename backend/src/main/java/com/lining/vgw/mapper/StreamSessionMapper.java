package com.lining.vgw.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lining.vgw.domain.StreamSession;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
public interface StreamSessionMapper extends BaseMapper<StreamSession> {

    /**
     * 原子地关闭一路流的"进行中"会话。
     *
     * <p>为什么要原子：ZLM 的 on_stream_changed 可能对同一路流并发触发多次
     * （例如 rtmp 与 rtsp 两个协议事件几乎同时到达），若先 select 再 update，
     * 两个线程都会查到 running 会话并各自记一笔账，导致台账重复。
     *
     * <p>这里用 {@code WHERE status = 1} 作为乐观锁：只有一个线程能更新成功
     * （返回 1），其余返回 0，调用方据此判断"是不是我关的"，只有关成功才记账。
     *
     * @return 影响行数：1 表示本次关闭成功，0 表示已被别人关闭
     */
    @Update("UPDATE biz_stream_session "
            + "SET status = #{status}, end_time = #{endTime}, end_reason = #{reason}, "
            + "    duration_sec = TIMESTAMPDIFF(SECOND, start_time, #{endTime}) "
            + "WHERE stream_key = #{streamKey} AND status = 1")
    int closeRunning(@Param("streamKey") String streamKey,
                     @Param("status") int status,
                     @Param("reason") String reason,
                     @Param("endTime") LocalDateTime endTime);
}
