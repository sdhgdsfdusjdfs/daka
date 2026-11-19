# 同仓库日志配置

项目仓库为 `sdhgdsfdusjdfs/daka`，日志分支为 `device-logs`，预设目录为 `devices/honor8-main` 和 `devices/vivo-test`。

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

状态页显示最近上报、总开关、无障碍状态、下一次任务和最近四次结果。本月日志链接位于页末。任务开始和结束时安排同步，平时约半小时一次心跳；安卓休眠和网络状况可能造成延迟。

GitHub 页面本身为静态文件，需要刷新。最后上报超过约 90 分钟时应人工检查，无法仅凭旧的“已连接”文字判断手机当前在线。正式机尚未上报之前，初始化状态明确显示“尚未连接”。

## 网络和故障

手机必须能访问 `api.github.com`。现场用“测试连接与写入”确认。网络不通时本地日志保留，失败后按退避规则重试。

401 常见于令牌失效；403 常见于权限或限流；404 需检查仓库、分支和令牌授权。修正后保存设置，点击“立即同步远程日志”。

仓库若为公开，执行日志也将公开可见，连接测试会显示这一点。建议在 GitHub 将本仓库设为私有，以限制日志阅读范围。

## 参考

- [限定权限令牌](https://docs.github.com/en/authentication/keeping-your-account-and-data-secure/managing-your-personal-access-tokens)
- [Contents API](https://docs.github.com/en/rest/repos/contents)
