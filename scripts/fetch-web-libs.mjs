// 下载前端依赖到 web/vendor/（国内源，无需构建工具）
// 用法: node scripts/fetch-web-libs.mjs
import fs from "node:fs";
import path from "node:path";
import https from "node:https";
import { spawnSync } from "node:child_process";
import { fileURLToPath } from "node:url";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const ROOT = path.resolve(__dirname, "..");
const VENDOR = path.join(ROOT, "frontend", "vendor");
const TMP = path.join(ROOT, "tools", "_download", "web");

const LIBS = [
  {
    name: "vue",
    versionUrl: "https://registry.npmmirror.com/vue",
    tarball: (v) => `https://registry.npmmirror.com/vue/-/vue-${v}.tgz`,
    // 从包内复制出来的文件映射：包内路径 -> vendor 文件名
    files: { "package/dist/vue.global.prod.js": "vue.global.prod.js" },
  },
  {
    name: "element-plus",
    versionUrl: "https://registry.npmmirror.com/element-plus",
    tarball: (v) => `https://registry.npmmirror.com/element-plus/-/element-plus-${v}.tgz`,
    files: {
      "package/dist/index.full.min.js": "element-plus.full.min.js",
      "package/dist/index.css": "element-plus.css",
    },
  },
  {
    name: "flv.js",
    versionUrl: "https://registry.npmmirror.com/flv.js",
    tarball: (v) => `https://registry.npmmirror.com/flv.js/-/flv.js-${v}.tgz`,
    files: { "package/dist/flv.min.js": "flv.min.js" },
  },
];

function get(url, depth = 0) {
  return new Promise((resolve, reject) => {
    const req = https.get(
      url,
      { timeout: 30000, rejectUnauthorized: false, headers: { "User-Agent": "Mozilla/5.0" } },
      (res) => {
        if (res.statusCode >= 300 && res.statusCode < 400 && res.headers.location && depth < 5) {
          res.resume();
          const next = new URL(res.headers.location, url).toString();
          return resolve(get(next, depth + 1));
        }
        if (res.statusCode !== 200) {
          res.resume();
          return reject(new Error("HTTP " + res.statusCode));
        }
        const chunks = [];
        res.on("data", (c) => chunks.push(c));
        res.on("end", () => resolve(Buffer.concat(chunks)));
      }
    );
    req.on("timeout", () => req.destroy(new Error("timeout")));
    req.on("error", reject);
  });
}

async function latestVersion(metaUrl) {
  const buf = await get(metaUrl);
  const meta = JSON.parse(buf.toString("utf8"));
  return meta["dist-tags"].latest;
}

function download(url, out) {
  fs.mkdirSync(path.dirname(out), { recursive: true });
  // 用已有的 dl.mjs 来完成下载（它带重试与重定向处理）
  const r = spawnSync(process.execPath, [path.join(__dirname, "dl.mjs"), url, out], {
    stdio: "inherit",
  });
  if (r.status !== 0) {
    throw new Error("download failed: " + url);
  }
}

fs.mkdirSync(VENDOR, { recursive: true });

let missing = 0;

for (const lib of LIBS) {
  console.log(`\n=== ${lib.name}`);
  const v = await latestVersion(lib.versionUrl);
  console.log(`  最新版本: ${v}`);

  // 若目标文件都已存在，跳过
  const targets = Object.values(lib.files).map((f) => path.join(VENDOR, f));
  if (targets.every((t) => fs.existsSync(t) && fs.statSync(t).size > 1000)) {
    console.log("  已存在，跳过");
    continue;
  }

  const tgz = path.join(TMP, `${lib.name}-${v}.tgz`);
  if (!fs.existsSync(tgz)) {
    download(lib.tarball(v), tgz);
  }

  const extractDir = path.join(TMP, `${lib.name}-${v}`);
  fs.rmSync(extractDir, { recursive: true, force: true });
  fs.mkdirSync(extractDir, { recursive: true });
  const r = spawnSync("tar", ["-xzf", tgz, "-C", extractDir], { stdio: "inherit" });
  if (r.status !== 0) {
    console.error(`  ✘ 解压失败: ${lib.name}`);
    missing++;
    continue;
  }

  for (const [inner, outName] of Object.entries(lib.files)) {
    const src = path.join(extractDir, inner);
    if (!fs.existsSync(src)) {
      console.error(`  ✘ 包内找不到 ${inner}`);
      missing++;
      continue;
    }
    const dest = path.join(VENDOR, outName);
    fs.copyFileSync(src, dest);
    const kb = (fs.statSync(dest).size / 1024).toFixed(0);
    console.log(`  ✔ ${outName}  (${kb} KB)`);
  }
}

console.log("\n=== 结果 ===");
for (const f of fs.readdirSync(VENDOR)) {
  console.log(`  ${f}  ${(fs.statSync(path.join(VENDOR, f)).size / 1024).toFixed(0)} KB`);
}
process.exit(missing ? 1 : 0);
