# Termux 局域网内测部署

本部署只面向 `192.168.1.0/24` 局域网，入口为
`http://192.168.1.13:8080`。账号凭据、验证码和 Session Token 通过
HTTP 传输时不具备链路加密，因此该环境不得当作生产 HTTPS 验收或公开服务。

## 触发边界与发布链

- 本地 `git commit` 只创建提交；仓库未配置本地 Git Hook，不会自动运行
  GitHub CI 或部署。
- `git push origin master` 触发 GitHub 托管 Runner 上的完整 CI，不发布
  Release，也不部署 Termux。
- 只有推送新的 `vMAJOR.MINOR.PATCH` 标签才触发 Release 工作流。三个字段
  均为非负整数，例如 `v0.1.0` 的下一个修复版本是 `v0.1.1`。
- 已发布标签和资产不可覆盖；发布前必须确认标签指向已经通过 `master` CI 的
  目标提交。

版本标签推送后：

1. Release 工作流复用完整 CI。
2. CI 通过后生成后端 Java 17 分发包、签名 APK、manifest 和 SHA-256 文件，
   并发布为 GitHub prerelease。
3. Termux watcher 每 300 秒读取最新 prerelease；发现更高版本后执行校验、
   PostgreSQL 备份、原子切换、后端重启和健康检查。

发布任务使用 GitHub Environment `internal-termux`：

- Secrets：`ANDROID_RELEASE_KEYSTORE_BASE64`、`RELEASE_STORE_PASSWORD`、
  `RELEASE_KEY_ALIAS`、`RELEASE_KEY_PASSWORD`。
- Variables：`INTERNAL_BACKEND_URL=http://192.168.1.13:8080`、
  `INTERNAL_ALLOW_HTTP_LEDGER_SYNC=true`；微信 AppID 可按需添加。

当前公开仓库使用匿名 GitHub API 和 Release 下载，不需要 PAT。若仓库以后
重新设为私有，可使用仅授予该仓库 `Contents: read` 的 fine-grained PAT；
令牌通过 `configure-github-token.sh` 的交互提示写入本机 `0600` 配置，
不得写入仓库、命令参数或日志。

## 首次部署

将 `deploy/termux/` 复制到服务器后，先运行：

```sh
./bootstrap.sh --inspect
```

确认 PostgreSQL、端口、路径和启动方式后，才运行：

```sh
./bootstrap.sh --provision
```

若检测到 `auto_accounting` 角色或数据库已经存在，provision 必须停止。
不得通过删除、覆盖或改名绕过检查；应先单独盘点现有数据。
公开仓库在 provision 完成后自动启用 Release watcher；私有仓库需再运行
`~/.local/lib/auto-accounting-deploy/configure-github-token.sh`。

部署使用独立的 `auto-accounting-nginx` runit 服务和
`~/.config/auto-accounting/nginx.conf`，不会修改或重载 Termux 的主
`$PREFIX/etc/nginx/nginx.conf`，也不会接管已有 Nginx 服务。

配置 Termux:Boot 时还需安装并打开该应用一次，并在 Android 系统中允许
Termux 后台运行。boot 脚本只启动 services、PostgreSQL 和本项目的独立
Nginx，不执行发布。
若设备已有其他项目，安装 boot 脚本前必须先复核其 PostgreSQL 启动方式；
未获批准时可暂不复制 `start-auto-accounting-boot.sh`。

## 运维与回滚

查看状态：

```sh
~/.local/lib/auto-accounting-deploy/status.sh
```

仅回滚后端程序：

```sh
~/.local/lib/auto-accounting-deploy/rollback.sh v0.1.0
```

每次部署和程序回滚前都会创建 PostgreSQL custom-format 备份。自动回滚只切换
程序符号链接，不恢复数据库；任何数据库恢复都必须先停止服务并另行确认。
手动回滚会停止 Release watcher，完成验收后需显式运行
`sv up auto-accounting-release-watcher` 才会恢复自动更新。

## 故障排查

`sv restart` / `sv up` 报 `fail: <service>: runsv not running`：

- 根因通常是 runsvdir（service-daemon）未运行，backend 的 runsv 退出后无人拉起；后端 java 进程可能仍作为孤儿进程占用 `18080`。
- 恢复：先 `export SVDIR=$PREFIX/var/service`，再执行 `service-daemon start`，等待 runsvdir 补齐各服务目录的 runsv；若 `18080` 被孤儿进程占用，先终止该进程再重启服务。
- 部署脚本 `deploy-release.sh` 已在切换与回滚两处调用 `ensure_service_runsv`（`lib.sh`），runsv 缺失时自动恢复，最多等待 15 秒；同步前旧脚本会以 `.bak-<时间戳>` 保留。
