package com.lining.vgw.service;

import com.lining.vgw.common.BizException;
import com.lining.vgw.common.ErrorCode;
import com.lining.vgw.integration.zlm.ZlmProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 录像文件在磁盘上的定位器。
 *
 * <p>为什么单独抽一个类：录像的「下载」和「清理」两个功能都要把台账里的
 * {@code file_path}（一个 http URL）还原成磁盘路径，如果各写一份，
 * 迟早会出现"下载能下、清理删不掉"这种诡异的不一致。
 *
 * <p>两个必须处理的现实问题：
 * <ol>
 *   <li><b>www 根目录不确定</b>：配置里写的是相对路径 {@code ./tools/zlm/www}，
 *       而相对谁取决于进程的工作目录（项目根 / backend 都可能），所以要按候选顺序探测</li>
 *   <li><b>路径穿越</b>：URL 里如果被塞进 {@code ../}，就能指向 www 之外的任意文件，
 *       所以还原后必须校验结果仍在 www 目录下</li>
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RecordingFileService {

    private final ZlmProperties zlmProperties;

    /** 解析 ZLM 的 www 根目录：配置优先，其次按常见工作目录兜底 */
    public Path wwwDir() {
        String[] candidates = {
                zlmProperties.getWwwDir(),      // 配置优先，如 ./tools/zlm/www
                "../tools/zlm/www",             // 工作目录为 backend 时
                "./tools/zlm/www"               // 工作目录为项目根时
        };
        for (String c : candidates) {
            if (c == null || c.isBlank()) {
                continue;
            }
            Path p = Paths.get(c).toAbsolutePath().normalize();
            if (Files.isDirectory(p)) {
                return p;
            }
        }
        return Paths.get(zlmProperties.getWwwDir()).toAbsolutePath().normalize();
    }

    /**
     * 把台账里的 file_path 映射为磁盘路径。
     *
     * @return 磁盘路径；file_path 为空时返回 null
     * @throws BizException 路径越界（试图访问 www 之外的文件）
     */
    public Path resolveDiskPath(String filePath) {
        if (filePath == null || filePath.isBlank()) {
            return null;
        }
        // 兜底：历史数据里可能存的是本地绝对路径，直接用
        if (!filePath.startsWith("http://") && !filePath.startsWith("https://")) {
            return Paths.get(filePath).toAbsolutePath().normalize();
        }
        String relative;
        try {
            relative = URI.create(filePath).getPath();
        } catch (RuntimeException e) {
            return null;
        }
        Path www = wwwDir();
        Path target = www.resolve(relative.replaceFirst("^/+", "")).normalize();
        if (!target.startsWith(www)) {
            log.warn("录像路径越界被拒: {}", filePath);
            throw new BizException(ErrorCode.PARAM_ERROR, "文件路径不合法，已阻止这次操作");
        }
        return target;
    }
}