package com.lining.vgw.integration.zlm.dto;

import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * ZLMediaKit 统一响应：{ code, msg, data }
 *
 * <p>注意：不同接口的 {@code data} 类型不同 ——
 * <ul>
 *   <li>{@code getServerConfig}：对象</li>
 *   <li>{@code getMediaList}：数组（形如 {@code {"code":0,"data":[{...}]}}）</li>
 *   <li>{@code openRtpServer}：对象（含 port）</li>
 * </ul>
 * 因此这里声明为 Object，由 {@link #dataList()} / {@link #dataMap()} 统一适配。
 */
@Data
public class ZlmResponse {

    private int code;

    private String msg;

    private Object data;

    public boolean isSuccess() {
        return code == 0;
    }

    /** 把 data 当作数组读取（兼容 {"data":[...]} 与 [...] 两种包法） */
    public List<?> dataList() {
        if (data instanceof List<?> list) {
            return list;
        }
        if (data instanceof Map<?, ?> map && map.get("data") instanceof List<?> inner) {
            return inner;
        }
        return List.of();
    }

    /** 把 data 当作对象读取 */
    @SuppressWarnings("unchecked")
    public Map<String, Object> dataMap() {
        if (data instanceof Map<?, ?> map) {
            Object inner = map.get("data");
            if (inner instanceof Map<?, ?> innerMap) {
                return (Map<String, Object>) innerMap;
            }
            return (Map<String, Object>) map;
        }
        return Map.of();
    }
}
