// 通用下载器：node dl.mjs <url> <输出文件>
// 支持：自动跟随 302 跳转、3 次重试、进度显示、断点清理
// 说明：本机 curl.exe 走 Windows Schannel 无法完成 TLS 握手，node 自带的 HTTPS 可以，所以统一用 node 下载
import fs from "node:fs";
import path from "node:path";
import https from "node:https";
import http from "node:http";

const [, , url, out] = process.argv;
if (!url || !out) {
  console.error("用法: node dl.mjs <url> <输出文件>");
  process.exit(2);
}

const UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36";

function fetchTo(targetUrl, outFile, depth = 0, attempt = 1) {
  return new Promise((resolve, reject) => {
    const mod = targetUrl.startsWith("https:") ? https : http;
    const req = mod.get(
      targetUrl,
      {
        timeout: 180000,
        rejectUnauthorized: false,
        headers: { "User-Agent": UA, Accept: "*/*" },
      },
      (res) => {
        // 跟随重定向
        if (res.statusCode >= 300 && res.statusCode < 400 && res.headers.location && depth < 6) {
          res.resume();
          const next = new URL(res.headers.location, targetUrl).toString();
          return resolve(fetchTo(next, outFile, depth + 1, attempt));
        }
        if (res.statusCode !== 200 && res.statusCode !== 206) {
          res.resume();
          return reject(new Error("HTTP " + res.statusCode));
        }
        const total = Number(res.headers["content-length"] || 0);
        const tmp = outFile + ".part";
        fs.mkdirSync(path.dirname(outFile), { recursive: true });
        const ws = fs.createWriteStream(tmp);
        let got = 0;
        let lastLog = 0;
        res.on("data", (chunk) => {
          got += chunk.length;
          if (got - lastLog >= 16 * 1024 * 1024) {
            lastLog = got;
            const pct = total ? ((got / total) * 100).toFixed(1) : "?";
            process.stdout.write(
              `    … ${path.basename(outFile)} ${(got / 1048576).toFixed(1)}MB ${pct}%\n`
            );
          }
        });
        res.pipe(ws);
        ws.on("finish", () => {
          try {
            fs.renameSync(tmp, outFile);
          } catch (e) {
            return reject(e);
          }
          resolve({ size: got });
        });
        ws.on("error", reject);
      }
    );
    req.on("timeout", () => req.destroy(new Error("timeout")));
    req.on("error", (e) => {
      try {
        fs.rmSync(outFile + ".part", { force: true });
      } catch {}
      if (attempt < 3) {
        console.log(`    重试 ${attempt}/2 (${path.basename(outFile)}): ${e.message}`);
        setTimeout(() => resolve(fetchTo(url, outFile, 0, attempt + 1)), 1500 * attempt);
      } else {
        reject(e);
      }
    });
  });
}

try {
  const r = await fetchTo(url, out);
  console.log(`  ✔ ${path.basename(out)} ${(r.size / 1048576).toFixed(2)} MB`);
} catch (e) {
  console.error(`  ✘ 下载失败 ${url}\n    ${e.message}`);
  process.exit(1);
}
