package com.lining.vgw.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lining.vgw.domain.Channel;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ChannelMapper extends BaseMapper<Channel> {

    /**
     * 按流标识查询【包含已逻辑删除的行】。
     *
     * uk_stream_key(stream_app, stream_id) 是物理唯一索引，逻辑删除的行仍然占着它。
     * 普通查询会被 @TableLogic 自动过滤掉 deleted=1 的行，于是"删掉再登记同名流标识"
     * 会撞唯一键并抛异常。这里先把那条历史行找出来，再由业务决定"复用"。
     */
    @Select("SELECT * FROM biz_channel WHERE stream_app = #{app} AND stream_id = #{streamId} LIMIT 1")
    Channel selectAnyByStreamKey(@Param("app") String app, @Param("streamId") String streamId);

    /** 按通道编码查询（同样包含已删除行） */
    @Select("SELECT * FROM biz_channel WHERE channel_code = #{code} LIMIT 1")
    Channel selectAnyByChannelCode(@Param("code") String code);

    /** 复活一条已逻辑删除的通道：用新参数覆盖并置 deleted=0（复用原 ID，历史台账引用不失效） */
    @Update("UPDATE biz_channel SET device_id = #{deviceId}, channel_code = #{channelCode}, "
            + "channel_name = #{channelName}, stream_app = #{streamApp}, stream_id = #{streamId}, "
            + "pull_url = #{pullUrl}, enabled = #{enabled}, status = 0, last_online_time = NULL, "
            + "deleted = 0, update_time = NOW() WHERE id = #{id}")
    int revive(Channel channel);
}