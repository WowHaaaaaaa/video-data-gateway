package com.lining.vgw.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lining.vgw.common.BizException;
import com.lining.vgw.common.ErrorCode;
import com.lining.vgw.common.PageQuery;
import com.lining.vgw.common.PageResult;
import com.lining.vgw.common.Result;
import com.lining.vgw.domain.Device;
import com.lining.vgw.dto.DeviceSaveReq;
import com.lining.vgw.mapper.DeviceMapper;
import com.lining.vgw.domain.Ledger;
import com.lining.vgw.service.ApiClientService;
import com.lining.vgw.service.ChannelScopeService;
import com.lining.vgw.service.LedgerService;
import io.swagger.v3.oas.annotations.Operation;
import org.springdoc.core.annotations.ParameterObject;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 设备管理接口。
 */
@Tag(name = "设备管理")
@RestController
@RequestMapping("/api/v1/devices")
@RequiredArgsConstructor
public class DeviceController {

    private final DeviceMapper deviceMapper;
    private final ChannelScopeService channelScopeService;
    private final ApiClientService apiClientService;
    private final LedgerService ledgerService;

    @Operation(summary = "分页查询设备")
    @GetMapping
    public Result<PageResult<Device>> page(@ParameterObject PageQuery query,
                                           @RequestParam(required = false) String keyword,
                                           @RequestParam(required = false) Integer status) {
        Page<Device> page = new Page<>(query.safePage(), query.safeSize());
        // 设备级隔离：受限客户端只能看到"名下有被授权通道"的设备
        List<Long> scope = channelScopeService.effectiveDeviceIdsOrNull();
        LambdaQueryWrapper<Device> wrapper = new LambdaQueryWrapper<Device>()
                .in(scope != null, Device::getId, scope)
                .like(StringUtils.hasText(keyword), Device::getDeviceName, keyword)
                .eq(status != null, Device::getStatus, status)
                .orderByDesc(Device::getId);
        IPage<Device> result = deviceMapper.selectPage(page, wrapper);
        return Result.ok(PageResult.of(result.getTotal(), result.getCurrent(), result.getSize(), result.getRecords()));
    }

    @Operation(summary = "查询单个设备")
    @GetMapping("/{id}")
    public Result<Device> get(@PathVariable Long id) {
        Device device = deviceMapper.selectById(id);
        if (device == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "设备不存在，可能已被删除");
        }
        // 设备级隔离：受限客户端看不了未授权设备（否则能枚举出全公司的设备清单）
        List<Long> scope = channelScopeService.effectiveDeviceIdsOrNull();
        if (scope != null && !scope.contains(id)) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "没有权限查看这台设备：" + device.getDeviceCode());
        }
        return Result.ok(device);
    }

    @Operation(summary = "新增设备（同编码设备若曾被逻辑删除则自动复用）")
    @PostMapping
    public Result<Device> create(@RequestBody @Valid DeviceSaveReq req) {
        Long active = deviceMapper.selectCount(new LambdaQueryWrapper<Device>()
                .eq(Device::getDeviceCode, req.getDeviceCode()));
        if (active != null && active > 0) {
            throw new BizException(ErrorCode.DUPLICATE, "设备编码 " + req.getDeviceCode() + " 已存在，请换一个");
        }

        // 复用已逻辑删除的同编码设备（唯一索引保持不动）
        Device deleted = deviceMapper.selectAnyByDeviceCode(req.getDeviceCode());
        if (deleted != null) {
            deleted.setDeviceName(req.getDeviceName());
            deleted.setDeviceType(req.getDeviceType());
            deleted.setManufacturer(req.getManufacturer());
            deleted.setIpAddress(req.getIpAddress());
            deleted.setEnabled(req.getEnabled());
            deleted.setRemark(req.getRemark());
            deviceMapper.revive(deleted);
            return Result.ok(deviceMapper.selectById(deleted.getId()));
        }

        Device device = new Device();
        device.setDeviceCode(req.getDeviceCode());
        device.setDeviceName(req.getDeviceName());
        device.setDeviceType(req.getDeviceType());
        device.setManufacturer(req.getManufacturer());
        device.setIpAddress(req.getIpAddress());
        device.setStatus(0);
        device.setEnabled(req.getEnabled());
        device.setRemark(req.getRemark());
        deviceMapper.insert(device);
        return Result.ok(device);
    }

    @Operation(summary = "修改设备")
    @PutMapping("/{id}")
    public Result<Device> update(@PathVariable Long id, @RequestBody @Valid DeviceSaveReq req) {
        Device exists = deviceMapper.selectById(id);
        if (exists == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "设备不存在，可能已被删除");
        }
        Device device = new Device();
        device.setId(id);
        device.setDeviceName(req.getDeviceName());
        device.setDeviceType(req.getDeviceType());
        device.setManufacturer(req.getManufacturer());
        device.setIpAddress(req.getIpAddress());
        device.setEnabled(req.getEnabled());
        device.setRemark(req.getRemark());
        deviceMapper.updateById(device);
        return Result.ok(deviceMapper.selectById(id));
    }

    @Operation(summary = "删除设备（并从各客户端设备级授权中自动移除）")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        Device exists = deviceMapper.selectById(id);
        deviceMapper.deleteById(id);
        if (exists != null) {
            // 级联清理：设备没了，客户端的"设备级授权"里不该再留着它
            List<String> affected = apiClientService.removeDeviceFromScopes(id);
            if (!affected.isEmpty()) {
                ledgerService.record(Ledger.TYPE_AUTH_CHANGED, id, null, null,
                        "API", null, 0,
                        "设备已删除，自动从 " + affected.size() + " 个客户端的设备授权中移除：" + String.join("、", affected), null);
            }
        }
        return Result.ok();
    }
}
