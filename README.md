# jenkins-atom-ssh

一台机器上的两个动作：**把文件推过去**，**在那边跑命令**。

ssh-publish 和 ssh-exec 原本是两个原子、两个产品（publish-over-ssh 和 ssh-steps），这只是历史上任务碰巧先用到哪个插件的差别：
连接本身——host、port、user、私钥、known_hosts——两种情况是同一个问题。QuickBuild 把它当成一个集成，这个原子也是。

`action` 选动作，默认 `publish`；**两个动作各自保留旧原子的字段、错误码和输出**，已有任务只需加一个 `action`：

| action | 干什么 | 旧原子 |
| --- | --- | --- |
| `publish`（默认） | 把文件按目录结构推到远端 | jenkins-atom-ssh-publish |
| `exec` | 在远端跑一段 shell，回报退出码与耗时 | jenkins-atom-ssh-exec |

## publish

publish-over-ssh 插件驱动 scp/rsync 并带一大堆传输选项；有用的核心是「这些文件要原样出现在那边」。这个动作在内存里打一个 tar，
通过一次 `ssh host 'tar -xf -'` 送过去——**远端只需要 sshd 和 tar**，而且是一次连接，不是每个文件一次。

- 模式匹配相对 `baseDir`（默认 workspace），`exclude` 再减一层；`sourceFiles` 也接受 `patterns` 这个写法
- `deleteRemote` 用 `find -mindepth 1 -delete`，不会删掉目录本身，拒绝 `/` 和 `*` 这种路径
- 运行器自己的 `.atoms/`、`.secrets.json` 永远不进包里，哪怕模式写的是 `**/*`
- 退出码非 0 才算失败；`Permission denied`/`Host key verification`/`IDENTIFICATION HAS CHANGED` 归 `USER`，其余归 `ENVIRONMENT`

输出：`filesUploaded`、`bytes`、`remoteDir`、`host`。

## exec

命令走 `bash -s` 的 stdin，不经过引号拼参数，所以引号、换行、`$` 都原样活下来。

- `expectedExitCode`（默认 0）不符才算失败；`failOnError=false` 时只记录，不让这一步红
- 超时后强杀，退出码按 124 记，归 `ENVIRONMENT`
- `command` 也接受 `script` / `commands` 这个写法

输出：`exitCode`、`durationMs`、`stdoutBytes`。

## 两个动作共用

- 私钥可以给路径（`keyFile`，相对 workspace），也可以给内容（`key`，写到 0600 的临时文件，用完删除）
- `knownHostsFile` 写进去的条目不加哈希（`HashKnownHosts=no`），人能读、脚本能 grep
- `BatchMode=yes`：不会有人来问密码，卡住就是超时
- `host`/`user` 缺失是两个动作同一套码（40001/40002）；`port` 不是数字则各用旧原子自己的码（publish 40005、exec 40004）

## 错误码

| 码 | publish | exec |
| --- | --- | --- |
| 40001 | host is required | host is required |
| 40002 | user is required | user is required |
| 40003 | remoteDir is required | command is required |
| 40004 | remoteDir 不能是 `/` 或 `*` | port must be a number |
| 40005 | port must be a number | no private key at … |
| 40006 | baseDir 必须在 workspace 内 | 命令退出码不符 / 超时 |
| 40007 | no file matched … | — |
| 40008 | no private key at … | — |
| 40009 | ssh failed (exit N) | — |
| 40010 | action 只认 publish 或 exec | 同左 |

## 构建与测试

```bash
mvn verify        # -> dist/ssh/，jar 名 ssh.jar（task.json 的 packagePath）
```

- `src/test/java/atom/SshPublishIT.java` — 临时端口上起一个**临时 sshd**（自己的 host key 与 authorized_keys，不碰系统配置），传完回到磁盘逐字节对账：嵌套路径、带空格与中文的文件名、exclude、`deleteRemote`、私钥当 secret、baseDir、字面路径，以及各种失败。
- `src/test/java/atom/SshExecIT.java` — 同一个临时 sshd，验证引号与多行脚本、非零退出码、`expectedExitCode`、`failOnError`、超时、ssh 自身失败（255）。
- `src/test/java/atom/SshActionIT.java` — 只管分发：默认 publish、大小写、拼错的动作被拒绝。
