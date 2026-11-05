package com.lining.vgw.config;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateTimeSerializer;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * 时间格式统一配置。
 *
 * <p><b>问题背景：</b>Java 的 {@code LocalDateTime} 默认只接受 ISO-8601（{@code 2026-11-02T09:40:00}，
 * 中间是字母 T），而前端日期控件普遍输出 {@code 2026-11-02 09:40:00}（中间是空格）。
 * 两者不匹配时，Jackson 会在第 10 个字符处报
 * {@code Text '...' could not be parsed at index 10}。
 *
 * <p><b>处理方式：</b>
 * <ul>
 *   <li><b>输出</b>：统一序列化成 {@code yyyy-MM-dd HH:mm:ss}，接口更友好（不带字母 T）</li>
 *   <li><b>输入</b>：宽容解析，空格/ISO/带毫秒/只有日期 四种写法都接受</li>
 * </ul>
 * 这样前端、curl、Swagger 调试、第三方系统四种来源都不会因为时间格式被卡住。
 */
@Configuration
public class JacksonConfig {

    /** 对外输出的统一格式 */
    private static final DateTimeFormatter OUTPUT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 输入时依次尝试的格式（宽容解析） */
    private static final DateTimeFormatter[] INPUT_FORMATS = {
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS")
    };

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer vgwDateTimeCustomizer() {
        return builder -> {
            SimpleModule module = new SimpleModule("vgw-datetime");

            module.addSerializer(LocalDateTime.class, new LocalDateTimeSerializer(OUTPUT));
            module.addDeserializer(LocalDateTime.class, new FlexibleLocalDateTimeDeserializer());
            module.addDeserializer(LocalDate.class, new FlexibleLocalDateDeserializer());

            builder.modulesToInstall(module);
        };
    }

    /** 宽容的 LocalDateTime 反序列化 */
    static class FlexibleLocalDateTimeDeserializer extends JsonDeserializer<LocalDateTime> {
        @Override
        public LocalDateTime deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
            String text = p.getText();
            if (text == null || text.isBlank()) {
                return null;
            }
            text = text.trim();
            for (DateTimeFormatter fmt : INPUT_FORMATS) {
                try {
                    return LocalDateTime.parse(text, fmt);
                } catch (DateTimeParseException ignored) {
                    // 继续尝试下一个格式
                }
            }
            // 兼容只传日期的情况：2026-11-02 → 当天 00:00:00
            try {
                return LocalDate.parse(text).atStartOfDay();
            } catch (DateTimeParseException ignored) {
                // 落到下面统一报错
            }
            throw new IOException("时间格式无法解析: " + text
                    + "，支持 yyyy-MM-dd HH:mm:ss 或 2026-11-02T09:40:00 或 yyyy-MM-dd");
        }
    }

    /** 宽容的 LocalDate 反序列化（兼容带时间部分的写法） */
    static class FlexibleLocalDateDeserializer extends JsonDeserializer<LocalDate> {
        @Override
        public LocalDate deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
            String text = p.getText();
            if (text == null || text.isBlank()) {
                return null;
            }
            text = text.trim();
            try {
                return LocalDate.parse(text);
            } catch (DateTimeParseException ignored) {
                for (DateTimeFormatter fmt : INPUT_FORMATS) {
                    try {
                        return LocalDateTime.parse(text, fmt).toLocalDate();
                    } catch (DateTimeParseException ignored2) {
                        // 继续
                    }
                }
            }
            throw new IOException("日期格式无法解析: " + text + "，支持 yyyy-MM-dd");
        }
    }
}