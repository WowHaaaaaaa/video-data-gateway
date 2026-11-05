package com.lining.vgw.common;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 通用分页查询参数。
 *
 * <p>注意：Controller 里必须写成 {@code @ParameterObject PageQuery query}，
 * 否则 springdoc 会把它渲染成<b>一个</b>名为 {@code query} 的必填对象参数
 * （文档上就看不到 page/size，用 Apifox/Postman 调试时不知道填什么）。
 */
@Data
public class PageQuery {

    /** 页码，从 1 开始 */
    @Schema(description = "页码，从 1 开始", example = "1", defaultValue = "1")
    private long page = 1;

    /** 每页条数，最大 200 */
    @Schema(description = "每页条数，最大 200", example = "10", defaultValue = "10")
    private long size = 10;

    public long safePage() {
        return Math.max(page, 1);
    }

    public long safeSize() {
        return Math.min(Math.max(size, 1), 200);
    }
}