# Git 版本管理与协作手册

> 本项目（视频数据网关）的 Git 使用说明。
> 写给：不熟悉 Git 的自己和后续接手的人。看完能独立完成「改代码 → 提交 → 推送 → 开分支 → 合并」。
> 原则：**够用就好**，不追求讲全 Git 的所有功能。

---

## 0. 一分钟上手

日常 90% 的操作就这四条命令：

```powershell
cd C:\Users\lining\video-data-gateway

git pull                      # ① 开工前先同步（多人协作时必做）
git status                    # ② 看改了哪些文件
git add -A                    # ③ 把改动放进暂存区
git commit -m "fix: 改了什么"  # ④ 存成一个版本
git push                      # ⑤ 推到 GitHub
```

其余内容都是这四条的变体和补充。

---

## 1. 心智模型：先搞懂四个"区"

把 Git 想成**寄快递**：

```
① 工作区            ② 暂存区            ③ 本地仓库           ④ 远端仓库
你正在编辑的文件  →  放进纸箱的东西  →  已封箱贴单的包裹  →  送到云上的仓库
（项目的目录）       （.git/index）      （.git 文件夹）      （GitHub）

                 git add           git commit          git push
```

| 区 | 是什么 | 怎么看 |
|---|---|---|
| ① 工作区 | 你眼前这些文件（`backend/`、`frontend/`…） | 直接看目录 |
| ② 暂存区 | 已选中、等待提交的改动清单 | `git status` |
| ③ 本地仓库 | `.git` 文件夹，所有历史版本都在里面 | `git log` |
| ④ 远端仓库 | GitHub 上的那份，别人能看 | 浏览器打开仓库页 |

**两个最容易混淆的概念：**

- `git commit` 只是"存版本到本地"，**此时 GitHub 上还没有**
- `git push` 才是"传上去"。所以 `commit` 之后忘记 `push`，别人看不到你的改动

**分支是什么**：一条独立的开发线。`main` 是主线；从 main 切出 `feat/xxx` 后，你在分支上的提交不影响 main，直到你把它合并回去。合并前随时可以丢弃整个分支，main 一点不脏。

---

## 2. 本仓库的现状

| 项 | 值 |
|---|---|
| 远端地址 | `https://github.com/WowHaaaaaaa/video-data-gateway.git` |
| 主分支 | `main` |
| 版本库 | 项目根目录下的 `.git`（约 1 MB，不要手动删） |
| 纳入版本管理的文件 | 122 个（源码 / 脚本 / 文档 / 前端）；可用 `git ls-files` 查看全部 |
| **不**纳入的文件 | `tools/`（1.6 GB 运行时）、`.m2repo/`、`backend/target/`、`*.zip`、`.idea/`、录像 mp4、日志 |
| 忽略规则 | `.gitignore`（行为规则）、`.gitattributes`（换行符规则） |

> **为什么 `tools/` 不入库**：它是 ZLMediaKit / MySQL / Maven / ffmpeg 的二进制，加起来 1.6 GB，
> 属于"机器环境"而不是"源码"。克隆后执行 `node scripts/fetch-deps.mjs all` 即可重新获得。
> 录像 mp4 同理 —— 那是业务数据，不是代码。

---

## 3. 每日工作流

### 3.1 改完代码后提交

```powershell
git status
# 输出示例：
#   modified:   frontend/app.js          ← 红字，还没进暂存区
#   new file:   docs/16-Git手册.md       ← 新文件，git 会问你要不要管
```

```powershell
git add frontend/app.js        # 只提交这一个文件
# 或
git add -A                     # 提交所有改动（.gitignore 会自动挡住 tools/ 等）
```

```powershell
git commit -m "style: 实时视频页去掉推流提示文案"
git push
```

### 3.2 只想提交一部分改动

```powershell
git add frontend/app.js backend/src/main/java/com/lining/vgw/service/StreamPullService.java
git commit -m "fix: 拉流失败提示统一为一句中文"
```

### 3.3 提交前先自查（强烈建议）

```powershell
git status          # 确认清单里没有意外文件
git diff            # 逐行看自己到底改了什么（最有用的一条命令）
git diff --staged   # 看已经 add 进去的内容
```

### 3.4 多条提交一次推送

```powershell
git commit -m "feat: 新增录像筛选"
git commit -m "docs: 补录像筛选的接口说明"
git push                # 一次把两个提交都推上去
```

---

## 4. Commit 信息规范

格式：`类型: 简短描述`（中文描述完全可以）

| 类型 | 用于 | 例子 |
|---|---|---|
| `feat` | 新功能 | `feat: 新增录像按通道筛选` |
| `fix` | 修 bug | `fix: 修掉 page=abc 报英文的问题` |
| `docs` | 只改文档 | `docs: 补充克隆后的启动说明` |
| `style` | 样式/格式，不改逻辑 | `style: 表格恢复原始样式` |
| `refactor` | 重构，行为不变 | `refactor: 权限判定抽成 AccessScope` |
| `test` | 测试相关 | `test: 补充拉流失败分支的单测` |
| `chore` | 杂项（构建、脚本） | `chore: 更新依赖拉取脚本` |

**好**：

```
fix: 通道列表状态只显示在线/离线
feat: 平台发起拉流支持 rtsp 与 rtmp 源
```

**差**（过一个月自己都想不起来改了什么）：

```
update
改了
1
提交
```

---

## 5. 分支：什么时候开、怎么用

### 5.1 不是必须开分支

| 情况 | 建议 |
|---|---|
| 改一行文案、修错别字、调样式 | **直接在 main 上提交** |
| 做一个小功能（1~2 小时） | 可以直接在 main，也可以开分支 |
| 做较大功能（跨多天、动多个文件） | **开分支** |
| 试不确定的方案（可能失败） | **开分支**（失败了直接删，main 不受影响） |
| 两人以上同时改 | **各开各的分支 + PR 合并** |

### 5.2 分支基本操作

```powershell
git branch                          # 列出本地分支（* 表示当前分支）
git branch -a                       # 含远端分支

git checkout -b feat/play-sign      # 从当前分支切出新分支并切过去
git checkout main                   # 切回 main
git switch main                     # 新版写法，等价于上面（git 2.23+）

git push -u origin feat/play-sign   # 首次推送分支（-u 记住对应关系，以后 git push 即可）

git branch -d feat/play-sign        # 删除已合并的本地分支
git branch -D feat/play-sign        # 强制删除（还没合并也要删，改动会丢）
```

### 5.3 命名约定

```
feat/播放地址签名      feat/play-sign
fix/拉流超时提示       fix/pull-timeout-msg
docs/git手册          docs/git-guide
```

英文更保险（避免部分工具对中文分支名的处理问题），但中文也能用。

### 5.4 开分支前必须同步

**分支是从"你切出来那一刻的 main"复制出去的。** 别人之后往 main 提交，你的分支就落后了。
所以标准动作是：

```powershell
git checkout main
git pull                # 先让 main 变成最新的
git checkout -b feat/xxx
```

---

## 6. 多人协作：Pull Request 流程

### 6.1 完整时序

```
          GitHub 远端 main
                 │ ① git pull（同步最新）
                 ▼
      你的本地 main ──② git checkout -b feat/xxx──> 你的分支
                                                        │ ③ 改代码 + commit
                                                        │ ④ git push -u origin feat/xxx
                                                        ▼
                                            GitHub 上的 feat/xxx 分支
                                                        │ ⑤ 网页开 Pull Request
                                                        │ ⑥ 别人在 Files changed 里 review
                                                        │ ⑦ 点 Merge pull request
                                                        ▼
                                              main 被合并、分支可删除
                                                        │ ⑧ 本地 git checkout main && git pull
                                                        ▼
                                                  本地跟上最新
```

### 6.2 具体操作

**第一步：自己电脑上**

```powershell
git checkout main
git pull
git checkout -b feat/ledger-export
# ……改代码……
git add -A
git commit -m "feat: 台账支持导出 CSV"
git push -u origin feat/ledger-export
```

**第二步：GitHub 网页上**

1. 打开仓库首页，会出现绿色提示条 **Compare & pull request**，点它
   （没看到就在 **Pull requests** 标签页 → **New pull request** → 选 `base: main` ← `compare: feat/ledger-export`）
2. 填标题（通常用 commit 信息）和说明（改了什么、为什么、怎么验证）
3. 点 **Create pull request**
4. 别人在 **Files changed** 页逐行看代码、留评论
5. 确认没问题 → **Merge pull request** → **Confirm merge**
6. 点 **Delete branch**（分支用完就删，否则越堆越多）

**第三步：其他协作者同步**

```powershell
git checkout main
git pull                        # 拉到刚合并的成果
git branch -d feat/ledger-export   # 删掉自己本地已完成的分支
```

### 6.3 为什么用 PR 而不是本地 merge

| 做法 | 命令 | 特点 |
|---|---|---|
| 本地合并 | `git checkout main` → `git merge feat/x` → `git push` | 快，但没有评审记录，出错难追溯 |
| **PR 合并**（推荐） | 推到远端后网页点 Merge | 有评审、有讨论、有记录、能一键回退 |

### 6.4 保护 main（可选）

如果要强制"必须走 PR 才能进 main"：

```
仓库 → Settings → Branches → Add branch protection rule
→ Branch name pattern: main
→ 勾选 Require a pull request before merging
```

一个人开发**不建议开**（每次都得自己给自己开 PR，很啰嗦）。

---

## 7. 冲突（Conflict）

### 7.1 冲突是怎么来的

**两个人改了同一个文件的同一片区域**，Git 不知道该听谁的，就停下来问你。
改不同文件、或同一文件的不同位置，Git 能自动合并，不会冲突。

### 7.2 冲突长什么样

`git pull` 或 PR 页面会提示冲突，打开文件能看到：

```
<<<<<<< HEAD
if (channel.getStatus() > 0) {          // 你本地（或当前目标分支）的版本
=======
if (channel.getStatus() === 2) {        // 对方分支的版本
>>>>>>> feat/ledger-export
```

### 7.3 怎么解决

1. **手动编辑**该文件，保留你要的代码
2. **删掉三行标记**：`<<<<<<<`、`=======`、`>>>>>>>`（一行都不能留）
3. 保存，然后：

```powershell
git add 冲突的文件
git commit -m "merge: 解决前端状态判断的冲突"
git push
```

### 7.4 怎么减少冲突（比会解决更重要）

| 做法 | 说明 |
|---|---|
| **按文件/模块分工** | 你改后端 Java，他改前端 → 天然不冲突 |
| **勤同步** | 每天开工前 `git pull`，别攒一周 |
| **小步提交** | 每次提交只做一件事，冲突范围就小 |
| **提前打招呼** | 都要改 `ChannelController`？先说一声，别同时动同一个方法 |

> ⚠️ **本项目的冲突高发区**：`frontend/app.js`（近 2000 行，8 个页面全在这一个文件里）。
> 两人同时改不同页面也很可能撞车。要么错开时间改，要么先把它拆成多个文件（这本身是个优化项）。

---

## 8. 撤销与回退

分三档，**越往后越危险**：

### 8.1 改动还没 commit —— 随便撤

```powershell
git restore frontend/app.js       # 单个文件退回上次提交的样子（改动丢失）
git restore .                     # 全部退回（危险）
git restore --staged frontend/app.js   # 只是从暂存区拿出来，改动还在
```

### 8.2 已 commit，但还没 push —— 可以重来

```powershell
# 撤销最后一次提交，但保留改动（最常见：commit 信息写错了想重写）
git reset --soft HEAD~1
git commit -m "改正后的提交信息"

# 撤销最后一次提交，并把改动也丢掉（相当于回到提交前）
git reset --hard HEAD~1
```

### 8.3 已经 push 了 —— 尽量别用强推

```powershell
git push --force        # ⚠️ 会覆盖远端，别人基于旧提交的工作会乱掉
```

正确姿势通常是**再提交一次来修正**，而不是改写历史：

```powershell
git revert HEAD         # 生成一个"反向提交"，把上一次的效果抵消掉（安全，不改历史）
git push
```

### 8.4 误删文件、想找回

```powershell
git restore 被删的文件         # 还没 commit 时，直接从上次提交取回
git checkout HEAD -- 文件路径   # 同上，旧写法
```

### 8.5 记不住这么多？只记这两条保命规则

1. **`git restore` 撤销未提交的改动**（安全）
2. **`git revert` 抵消已推送的提交**（安全）；**永远别 `git push -f`**

---

## 9. 查看与排查

```powershell
git log --oneline -10                # 最近 10 条提交（一行一条）
git log --oneline --graph --decorate # 带分支图形，看合并关系
git show ba35f6f                     # 看某个提交具体改了什么
git diff                             # 工作区 vs 上次提交（最常用）
git diff --staged                    # 暂存区 vs 上次提交
git diff main feat/xxx               # 两个分支的差异
git blame frontend/app.js            # 每一行是谁、哪个提交改的（排查"这行谁写的"）
git log -p -- frontend/app.js        # 这个文件的历史改动
```

---

## 10. `.gitignore` 与仓库体积

### 10.1 本项目的规则

`.gitignore` 排除了：

```
tools/            # 运行时二进制（1.6 GB）
.m2repo/          # Maven 本地仓库
backend/target/   # 构建产物（含 39 MB jar）
*.zip *.tgz       # 离线包、参考源码包
logs/ *.log       # 日志
.idea/            # IDE 配置
tools/zlm/www/record/   # 录像 mp4（业务数据）
```

### 10.2 关键限制：`.gitignore` 只对"未被跟踪"的文件生效

如果某个文件**已经被提交过**，之后再把它写进 `.gitignore` 也没用 —— Git 会继续管它。补救：

```powershell
git rm --cached backend/target/video-data-gateway.jar   # 从版本库移除，本地文件保留
git commit -m "chore: 把构建产物移出版本库"
```

### 10.3 千万别做的事

| 别做 | 原因 |
|---|---|
| `git add -f tools/` | 强制添加被忽略的文件，1.6 GB 会进历史，**很难彻底清掉**（要重写历史） |
| 提交超过 100 MB 的单个文件 | GitHub 直接拒收 |
| 提交录像 mp4、日志 | 仓库会迅速膨胀，且这些是运行产物 |
| `git add .` 之前不看 `git status` | 容易把临时文件、密钥一起提交上去 |

---

## 11. 常见报错对照表

| 报错 | 原因 | 解决 |
|---|---|---|
| `Everything up-to-date` | 没有已提交的新版本可推 | 先 `git commit`，再 `git push` |
| `! [rejected] main -> main (fetch first)` | 远端有你本地没有的提交（别人推过 / 网页上改过） | `git pull --rebase` 然后 `git push` |
| `fatal: not a git repository` | 当前目录不是仓库 | `cd` 到 `C:\Users\lining\video-data-gateway` |
| `Please tell me who you are` | 没配提交者身份 | 见 11.1 |
| `Authentication failed` | 凭据失效 | 重跑 `git push`，弹窗里重新授权；或 `git credential-manager github logout` |
| `remote: Repository not found` | 地址错 / 无权限（Private 仓库） | 核对地址；确认账号已被加为协作者 |
| `error: Your local changes would be overwritten` | 切分支/pull 时目标文件有未提交改动 | 先 `git stash`（暂存改动）或 `git commit`，再操作 |
| `fatal: refusing to merge unrelated histories` | 两个仓库历史无关（比如远端建仓时勾了 README） | `git pull --rebase --allow-unrelated-histories` |
| `fatal: unable to access ... schannel: AcquireCredentialsHandle failed` | 执行环境的 Windows 证书存储不可用 | 换一个普通终端窗口运行；沙箱/受限环境里跑不通 HTTPS |

### 11.1 首次配置提交者身份

```powershell
git config --global user.name  "你的名字"
git config --global user.email "你的邮箱"
```

### 11.2 中文文件名显示成转义

```powershell
git config --global core.quotepath false    # 之后 git status 正常显示中文名
```

### 11.3 换行符（Windows 特有）

```powershell
git config core.autocrlf input   # 提交时转 LF，检出时不转换
```

本项目已在 `.gitattributes` 里规定：源码统一 LF，`.bat/.ps1` 保持 CRLF。

---

## 12. 命令总索引（一页纸）

| 我想…… | 命令 |
|---|---|
| 看当前状态 | `git status` |
| 看自己改了什么 | `git diff` |
| 把改动放进暂存区 | `git add -A` / `git add 文件` |
| 存成一个版本 | `git commit -m "feat: xxx"` |
| 推到 GitHub | `git push` |
| 拉最新 | `git pull` |
| 看历史 | `git log --oneline -10` |
| 看某次提交改了什么 | `git show <提交号>` |
| 撤销未提交的改动 | `git restore 文件` |
| 撤销已提交未推送的 | `git reset --soft HEAD~1` |
| 抵消已推送的提交 | `git revert HEAD` |
| 开分支 | `git checkout -b feat/xxx` |
| 切分支 | `git switch main` |
| 看所有分支 | `git branch -a` |
| 推新分支 | `git push -u origin feat/xxx` |
| 删本地分支 | `git branch -d feat/xxx` |
| 删远端分支 | `git push origin --delete feat/xxx`（或网页上点 Delete branch） |
| 暂时收起改动去切分支 | `git stash` → `git stash pop` |

---

## 13. 本项目的几条约定

1. **`push` 在本地终端执行**。某些受限制的执行环境（沙箱）里无法完成 HTTPS 握手，
   会报 `schannel: AcquireCredentialsHandle failed`，这不代表网络或仓库有问题。
2. **不要强推**（`git push --force`）。要修正已推送的内容，用 `git revert` 再提交。
3. **不提交 `tools/`、`target/`、录像、日志**。需要运行时依赖就跑 `node scripts/fetch-deps.mjs all`。
4. **提交前扫一眼 `git status`**。仓库里只有"源码 + 脚本 + 文档 + 前端"，出现别的东西就要警惕。
5. **凭据走环境变量**：`VGW_DB_PASSWORD`、`VGW_ZLM_SECRET`。`application.yml` 里的默认值仅用于本地开发。

---

## 附：三个最实用的操作组合

**场景 A：改完一个 bug，提交推送**

```powershell
git status && git diff          # 确认改动
git add -A
git commit -m "fix: 通道状态只显示在线/离线"
git push
```

**场景 B：做一个新功能，走分支 + PR**

```powershell
git checkout main && git pull
git checkout -b feat/录制计划
git add -A && git commit -m "feat: 新增录制计划配置"
git push -u origin feat/录制计划
# → 网页开 PR → 合并 → 本地 git checkout main && git pull
```

**场景 C：改坏了想全退回去（还没提交）**

```powershell
git restore .
git status        # 应该显示 nothing to commit, working tree clean
```