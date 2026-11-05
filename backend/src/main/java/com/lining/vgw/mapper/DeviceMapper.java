package com.lining.vgw.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lining.vgw.domain.Device;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface DeviceMapper extends BaseMapper<Device> {

    /** 按设备编码查询【包含已逻辑删除的行】（uk_device_code 同理占着唯一键） */
    @Select("SELECT * FROM biz_device WHERE device_code = #{code} LIMIT 1")
    Device selectAnyByDeviceCode(@Param("code") String code);

    /** 复活一条已逻辑删除的设备：用新参数覆盖并置 deleted=0（复用原 ID） */
    @Update("UPDATE biz_device SET device_name = #{deviceName}, device_type = #{deviceType}, "
            + "manufacturer = #{manufacturer}, ip_address = #{ipAddress}, enabled = #{enabled}, "
            + "remark = #{remark}, status = 0, deleted = 0, update_time = NOW() WHERE id = #{id}")
    int revive(Device device);
}