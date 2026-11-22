# 开发辅助脚本

这些文件只在**准备构建环境**、**本地部署**或**排查问题**时用得到，不参与模组运行。

| 文件 | 用途 |
| --- | --- |
| `prepare.cmd` | 首次构建前运行一次：把 Gradle 9.2.1 下载并解压到会话目录的 `.gradle-dist/`，之后 `build.cmd` 就完全离线可用 |
| `Dl.java` | `prepare.cmd` 用的下载器（JDK 自带即可编译运行，不依赖 curl/PowerShell 的网络栈） |
| `deploy.cmd` | 把 `build/libs` 里最新的 jar 安装到本机 simmc 实例：先删掉实例里的旧版 `rail-restock-*.jar` / `feeder-restock-*.jar`，再复制并校验 SHA256 |
| `deploy.ps1` | `deploy.cmd` 的实际实现，可带参数：`-Instance <实例目录>`、`-Jar <指定 jar>`、`-Wait`（等游戏退出后再装）、`-TimeoutMinutes <上限>` |
| `inspect_mixin.py` | 读取 class 文件常量池，检查 Mixin 注入点是否被正确重映射；排查“注入在开发环境正常、进游戏失效”时用 |

日常只需要在项目根目录执行：

```bat
build.cmd build
tools\deploy.cmd
```

**游戏正在运行时** jar 会被 Windows 锁住，`deploy.cmd` 会提示并退出（返回码 2）。此时以后台方式执行：

```bat
tools\deploy.cmd -Wait
```

它每 3 秒检查一次游戏进程，退出后再替换文件；复制失败会重试，最后用 SHA256 校验。

构建缓存（Gradle 发行包、依赖、临时目录）都在**上一级会话目录**的 `.gradle-*` 里，不会写进项目，也不会污染系统目录。
