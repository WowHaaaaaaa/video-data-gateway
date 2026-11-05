package com.lining.vgw.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.lining.vgw.common.PageQuery;
import com.lining.vgw.common.PageResult;
import com.lining.vgw.common.Result;
import com.lining.vgw.domain.ApiClient;
import com.lining.vgw.service.ApiClientService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import org.springdoc.core.annotations.ParameterObject;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * API 客户端管理接口。
 *
 * <p>语义对齐 WVP-PRO 的 {@code /api/userApiKey} 一组动作：
 * 创建 / 列表 / 启停 / 重置密钥 / 删除。
 *
 * <p>注意：本组接口自身也受 api-key 鉴权保护（引导客户端由种子数据提供）。
 */
@Tag(name = "API 客户端")
@RestController
@RequestMapping("/api/v1/api-clients")
@RequiredArgsConstructor
public class ApiClientController {

    private final ApiClientService apiClientService;

    @Data
    public static class CreateReq {
        private String clientName;
        /** 联系人 */
        private String contact;
        private String remark;
        @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        private LocalDateTime expireTime;
        /** 单独授权的通道 ID；空表示该维度不限 */
        private List<Long> allowedChannels;
        /** 设备级授权的设备 ID：该设备名下所有通道（含以后新增）都算授权 */
        private List<Long> allowedDevices;
        /** 授权模式：ALL=不限（默认）；SCOPE=按设备/通道授权（都空则一个都不授权） */
        private String scopeMode;
        /** 管理权限（超管）：1=可调用全部接口（含客户端管理/统计）；默认 0 */
        private Integer isAdmin;
    }

    @Operation(summary = "创建客户端（返回的 appSecret 只显示这一次）")
    @PostMapping
    public Result<Map<String, Object>> create(@RequestBody CreateReq req) {
        ApiClient client = apiClientService.create(req.getClientName(), req.getContact(), req.getRemark(),
                req.getExpireTime(), req.getAllowedChannels(), req.getAllowedDevices(), req.getScopeMode(),
                req.getIsAdmin());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", client.getId());
        data.put("appKey", client.getAppKey());
        data.put("appSecret", client.getAppSecret());
        data.put("clientName", client.getClientName());
        data.put("contact", client.getContact());
        data.put("allowedChannels", client.getAllowedChannels());
        data.put("allowedDevices", client.getAllowedDevices());
        data.put("scopeMode", client.getScopeMode());
        data.put("isAdmin", client.getIsAdmin());
        data.put("expireTime", client.getExpireTime());
        data.put("tip", "appSecret 仅此一次可见，请立即保存");
        return Result.ok(data);
    }

    @Operation(summary = "分页查询客户端（不返回 appSecret）")
    @GetMapping
    public Result<PageResult<ApiClient>> page(@ParameterObject PageQuery query,
                                              @Parameter(description = "关键字：客户端名称 / appKey / 联系人")
                                              @RequestParam(required = false) String keyword) {
        IPage<ApiClient> result = apiClientService.page(query.safePage(), query.safeSize(), keyword);
        result.getRecords().forEach(c -> c.setAppSecret("******"));
        return Result.ok(PageResult.of(result.getTotal(), result.getCurrent(), result.getSize(), result.getRecords()));
    }

    @Operation(summary = "启用客户端")
    @PostMapping("/{id}/enable")
    public Result<ApiClient> enable(@PathVariable Long id) {
        ApiClient c = apiClientService.setEnabled(id, true);
        c.setAppSecret("******");
        return Result.ok(c);
    }

    @Operation(summary = "停用客户端（立即生效）")
    @PostMapping("/{id}/disable")
    public Result<ApiClient> disable(@PathVariable Long id) {
        ApiClient c = apiClientService.setEnabled(id, false);
        c.setAppSecret("******");
        return Result.ok(c);
    }

    @Operation(summary = "重置密钥（旧密钥立即失效）")
    @PostMapping("/{id}/reset")
    public Result<Map<String, Object>> reset(@PathVariable Long id) {
        String secret = apiClientService.resetSecret(id);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", id);
        data.put("appSecret", secret);
        data.put("tip", "新密钥仅此一次可见");
        return Result.ok(data);
    }

    @Data
    public static class ExpireReq {
        /** 新的失效时间；传 null 表示永久有效 */
        private LocalDateTime expireTime;
    }

    @Operation(summary = "修改失效时间（续期，传 null 表示永久有效）")
    @PostMapping("/{id}/expire")
    public Result<ApiClient> updateExpire(@PathVariable Long id, @RequestBody ExpireReq req) {
        ApiClient c = apiClientService.updateExpireTime(id, req.getExpireTime());
        c.setAppSecret("******");
        return Result.ok(c);
    }

    @Data
    public static class ChannelsReq {
        /** 单独授权的通道 ID 列表 */
        private List<Long> allowedChannels;
        /** 设备级授权的设备 ID 列表；该设备名下所有通道（含以后新增）都算授权 */
        private List<Long> allowedDevices;
        /** 授权模式：ALL=不限；SCOPE=按下列列表授权（都传空 = 一个都不授权 = 全拒） */
        private String scopeMode;
        /** 管理权限（超管）：1=可调用全部接口；0/不传=没有 */
        private Integer isAdmin;
    }

    @Operation(summary = "配置授权范围（设备级 + 通道级 + 管理权限）")
    @PostMapping("/{id}/channels")
    public Result<ApiClient> updateChannels(@PathVariable Long id, @RequestBody ChannelsReq req) {
        ApiClient c = apiClientService.updateScope(id, req.getAllowedChannels(), req.getAllowedDevices(),
                req.getScopeMode(), req.getIsAdmin());
        c.setAppSecret("******");
        return Result.ok(c);
    }

    @Operation(summary = "删除客户端")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        apiClientService.delete(id);
        return Result.ok();
    }
}
