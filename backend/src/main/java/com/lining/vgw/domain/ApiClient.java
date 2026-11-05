package com.lining.vgw.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * API 客户端：对外发放的调用凭证。
 *
 * <p>设计参考 WVP-PRO 的 UserApiKey，但去掉了"用户"这一层 ——
 * 我们的调用方是<b>业务系统</b>而不是人，所以客户端自身就是主体。
 *
 * <p>鉴权链路：请求头 {@code api-key: <appKey>} → 查本表 → 校验 enabled/expire_time → 放行。
 * appSecret 不参与接口鉴权（避免明文传输），只在<b>签发播放地址签名</b>时作为 HMAC 密钥使用。
 */
@Data
@TableName("biz_api_client")
public class ApiClient {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 客户端标识，请求头 api-key 传这个值（唯一） */
    private String appKey;

    /** 客户端密钥，仅创建/重置时展示一次；用作播放地址签名密钥 */
    private String appSecret;

    /** 客户端名称，如 "XX业务平台" */
    private String clientName;

    /** 联系人：凭证发给谁、出问题找谁 */
    private String contact;

    /** 0 停用 / 1 启用 */
    private Integer enabled;

    /** 失效时间，NULL 表示长期有效 */
    private LocalDateTime expireTime;

    /** 允许访问的通道 ID，逗号分隔；NULL 表示该维度不限 */
    private String allowedChannels;

    /**
     * 允许访问的设备 ID，逗号分隔；NULL 表示该维度不限。
     *
     * <p><b>设备级授权</b>：列出的设备，其名下<b>所有</b>通道（含以后新增的）都视为已授权。
     * 与 {@link #allowedChannels}（单通道授权）取<b>并集</b>；两者都空 = 完全不限。
     */
    private String allowedDevices;

    /** 每分钟调用上限，0 表示不限（本期不做限流，字段预留） */
    private Integer rateLimit;

    @TableLogic
    private Integer deleted;

    private String remark;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    /** 是否已过期 */
    public boolean isExpired() {
        return expireTime != null && expireTime.isBefore(LocalDateTime.now());
    }

    /**
     * 授权模式：{@code ALL} 不限 / {@code SCOPE} 按设备+通道授权。
     *
     * <p>为什么需要它：光看两个列表"空不空"分不清两种意图 ——
     * "压根不限"和"一个都没授权（应当全拒）"。
     */
    private String scopeMode;

    /**
     * 是否有<b>管理权限（超管）</b>：1=有，0=没有。
     *
     * <p>和"通道范围"是两件不同的事：
     * <ul>
     *   <li>通道范围（scope_mode + 两个列表）决定<b>能看哪些通道的视频</b></li>
     *   <li>管理权限决定<b>能不能调管理类接口</b>
（客户端管理 / 统计 / 设备写操作）</li>
     * </ul>
     * 典型反例：某平台可以看全部通道，但绝不该能查到别人的 appKey。
     */
    private Integer isAdmin;

    /** 是否有管理权限 */
    public boolean admin() {
        return isAdmin != null && isAdmin == 1;
    }

    /** ALL=不限 */
    public static final String MODE_ALL = "ALL";
    /** SCOPE=按设备+通道授权 */
    public static final String MODE_SCOPE = "SCOPE";

    /** 是否是"按授权范围限制"的客户端 */
    public boolean isScopeLimited() {
        return MODE_SCOPE.equalsIgnoreCase(scopeMode);
    }

    /** 是否配了任何维度的授权（否则=不限） */
    public boolean hasAnyScope() {
        return (allowedChannels != null && !allowedChannels.isBlank())
                || (allowedDevices != null && !allowedDevices.isBlank());
    }

    /** 是否允许访问某通道 */
    public boolean canAccessChannel(Long channelId) {
        if (allowedChannels == null || allowedChannels.isBlank()) {
            return true;   // 未配置 = 不限
        }
        for (String s : allowedChannels.split(",")) {
            if (s.trim().equals(String.valueOf(channelId))) {
                return true;
            }
        }
        return false;
    }
}
