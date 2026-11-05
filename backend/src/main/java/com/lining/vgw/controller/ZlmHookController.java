package com.lining.vgw.controller;

import com.lining.vgw.service.ZlmHookService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * ZLMediaKit Webhook 入口。
 *
 * <p>ZLM 在 config.ini 的 [hook] 段配置了回调地址，事件发生时 POST 到这里。
 * 返回体必须是 {@code {"code":0,"msg":"success"}}；返回非 0 会让 ZLM 拒绝该操作
 * （例如拒绝未注册的推流）。
 *
 * <p>该接口不经过统一的 Result 包装，也不做鉴权（ZLM 直连本机）。
 */
@Slf4j
@RestController
@RequestMapping("/hook/zlm")
@RequiredArgsConstructor
public class ZlmHookController {

    private final ZlmHookService hookService;

    @PostMapping("/{event}")
    public Map<String, Object> handle(@PathVariable("event") String event,
                                      @RequestBody(required = false) Map<String, Object> body) {
        log.debug("收到 ZLM 事件 {}: {}", event, body);
        return hookService.handle(event, body == null ? Map.of() : body);
    }
}
