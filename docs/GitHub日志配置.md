# 同仓库日志配置

项目仓库为 `sdhgdsfdusjdfs/daka`，日志分支为 `device-logs`，预设目录为 `devices/honor8-main` 和 `devices/vivo-test`。

用户于 2026-11-19 明确选择保留公开，接受运行日志公开可见。手机凭据保存在本地，上传内容为计划、执行时间、结果与简短状态。

## 电脑和手机的连接方式

电脑通过用户已授权的 SSH 连接提交源码。手机使用 GitHub HTTPS API 上传文本文件，因此手机需要单独配置限定仓库的访问令牌。电脑上的 SSH 私钥保留在电脑中。

## 创建手机令牌

在 GitHub 账号 Settings → Developer settings → Personal access tokens → Fine-grained tokens 创建令牌：

1. Resource owner 选择仓库所有者。
2. Repository access 仅选择 `daka`。
3. Repository permissions 中将 Contents 设为 Read and write，Metadata 使用默认读取权限。
4. 选择合适的有效期，并记下需要续期的日期。
5. 在手机工具设置中输入令牌，保存并测试连接与写入。

分支已由电脑初始化。如果账号组织策略要求审批，需要等仓库所有者的实际授权生效。若 `device-logs` 设置了禁止直接写入的保护规则，应为手机日志选择允许直接更新的分支。

令牌存入手机本地加密配置。源码、安装包和远程日志均采用动态配置读取令牌。

## 查看

日常手机登录 GitHub，打开：

- [荣耀状态](https://github.com/sdhgdsfdusjdfs/daka/blob/device-logs/devices/honor8-main/status.md)
- [vivo 状态](https://github.com/sdhgdsfdusjdfs/daka/blob/device-logs/devices/vivo-test/status.md)

状态页显示最近上报、总开关、无障碍状态、下一次任务和最近四次结果。当天日志链接位于页末。任务开始和结束时安排同步，平时约半小时一次心跳；安卓休眠和网络状况可能造成延迟。

GitHub 页面本身为静态文件，需要刷新。最后上报超过约 90 分钟时应人工检查，无法仅凭旧的“已连接”文字判断手机当前在线。正式机尚未上报之前，初始化状态明确显示“尚未连接”。

## 网络和故障

手机必须能访问 `api.github.com`。现场用“测试连接与写入”确认。网络不通时本地日志保留，失败后按退避规则重试。

401 常见于令牌失效；403 常见于权限或限流；404 需检查仓库、分支和令牌授权。修正后保存设置，点击“立即同步远程日志”。

仓库为公开时，执行日志公开可见，连接测试会提示这一点。未来如需限制阅读，可由用户将整个仓库改为私有。

## 参考

- [限定权限令牌](https://docs.github.com/en/authentication/keeping-your-account-and-data-secure/managing-your-personal-access-tokens)
- [Contents API](https://docs.github.com/en/rest/repos/contents)

## 每日文件

自 0.2.0 起，日志目录每天生成一个 `YYYY-MM-DD.md` 文件，状态页链接到当天记录，`logs/README.md` 提供日期入口。表格包含尝试次数、最终结果、退出和熄屏状态，展开执行过程可看每次异常和重试。

旧月度文件保留为存档，已有远程记录可通过 `scripts/split-monthly-logs.py` 拆分。手机安装新版后按日期自动写入，在统一安装前旧客户端仍可能继续更新旧月度文件。
