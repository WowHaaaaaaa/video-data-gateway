// 依赖批量下载（国内源，无需访问 GitHub）
// 用法: node scripts/fetch-deps.mjs [zlm|maven|mysql|ffmpeg|all]
// 每个依赖配置了多个候选源，按顺序尝试，前一个失败自动换下一个
import { spawnSync } from "node:child_process";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const ROOT = path.resolve(__dirname, "..");
const TOOLS = path.join(ROOT, "tools");
const DOWNLOADS = path.join(TOOLS, "_download");

const TASKS = {
  zlm: {
    title: "ZLMediaKit 流媒体服务器 (Windows x64)",
    out: path.join(TOOLS, "zlm", "MediaServer.exe"),
    minSize: 3 * 1024 * 1024,
    urls: [
      "https://gitee.com/beixiaocai/zlm/raw/master/V2024.9.14.windows/MediaServer.exe",
      "https://gitee.com/shanewfx/zlm/raw/master/V2024.9.14.windows/MediaServer.exe",
      "https://gitee.com/beixiaocai/zlm/raw/master/V2023.3.25.windows/MediaServer.exe",
    ],
  },
  maven: {
    title: "Apache Maven 3.9.16 (阿里云镜像)",
    out: path.join(DOWNLOADS, "apache-maven-3.9.16-bin.zip"),
    minSize: 8 * 1024 * 1024,
    urls: [
      "https://mirrors.aliyun.com/apache/maven/maven-3/3.9.16/binaries/apache-maven-3.9.16-bin.zip",
      "https://mirrors.huaweicloud.com/apache/maven/maven-3/3.9.16/binaries/apache-maven-3.9.16-bin.zip",
    ],
  },
  mysql: {
    title: "MySQL 8.0.28 免安装版 (阿里云镜像)",
    out: path.join(DOWNLOADS, "mysql-8.0.28-winx64.zip"),
    minSize: 200 * 1024 * 1024,
    urls: [
      "https://mirrors.aliyun.com/mysql/MySQL-8.0/mysql-8.0.28-winx64.zip",
      "https://mirrors.tuna.tsinghua.edu.cn/mysql/downloads/MySQL-8.0/mysql-8.0.28-winx64.zip",
    ],
  },
  ffmpeg: {
    title: "ffmpeg (npm 包内自带 win32-x64 二进制)",
    out: path.join(DOWNLOADS, "ffmpeg-win32-x64.tgz"),
    minSize: 15 * 1024 * 1024,
    urls: [
      "https://registry.npmmirror.com/@ffmpeg-installer/win32-x64/-/win32-x64-4.1.0.tgz",
    ],
  },
};

const which = process.argv[2] || "all";
const names = which === "all" ? Object.keys(TASKS) : [which];
const wrong = names.filter((n) => !TASKS[n]);
if (wrong.length) {
  console.error("未知依赖: " + wrong.join(", ") + "\n可用: " + Object.keys(TASKS).join(", ") + ", all");
  process.exit(2);
}

const MB = 1024 * 1024;
let failed = 0;

for (const name of names) {
  const t = TASKS[name];
  console.log(`\n=== ${name}: ${t.title}`);
  if (fs.existsSync(t.out) && fs.statSync(t.out).size >= t.minSize) {
    console.log(`  已存在，跳过 (${(fs.statSync(t.out).size / MB).toFixed(1)} MB)`);
    continue;
  }
  let done = false;
  for (const url of t.urls) {
    console.log(`  尝试: ${url}`);
    const r = spawnSync(
      process.execPath,
      [path.join(__dirname, "dl.mjs"), url, t.out],
      { stdio: "inherit" }
    );
    if (r.status === 0 && fs.existsSync(t.out) && fs.statSync(t.out).size >= t.minSize) {
      done = true;
      break;
    }
    // 下载到了但大小不对（比如拿到了错误页面），删掉重试下一个源
    if (fs.existsSync(t.out) && fs.statSync(t.out).size < t.minSize) {
      fs.rmSync(t.out, { force: true });
      console.log(`  ✘ 文件大小异常，已删除，换下一个源`);
    }
  }
  if (!done) {
    failed++;
    console.log(`  ✘✘ ${name} 所有源均失败`);
  }
}

console.log(`\n完成：${names.length - failed}/${names.length} 成功`);
process.exit(failed ? 1 : 0);
