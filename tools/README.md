# 开发辅助脚本

这些文件只在**准备构建环境**或**排查问题**时用得到，不参与模组运行。

| 文件 | 用途 |
| --- | --- |
| `prepare.cmd` | 首次构建前运行一次：把 Gradle 9.2.1 下载并解压到会话目录的 `.gradle-dist/`，之后 `build.cmd` 就完全离线可用 |
| `Dl.java` | `prepare.cmd` 用的下载器（JDK 自带即可编译运行，不依赖 curl/PowerShell 的网络栈） |
| `inspect_mixin.py` | 读取 class 文件常量池，检查 Mixin 注入点是否被正确重映射；排查“注入在开发环境正常、进游戏失效”时用 |

日常只需要在项目根目录执行：

```bat
build.cmd build
```

构建缓存（Gradle 发行包、依赖、临时目录）都在**上一级会话目录**的 `.gradle-*` 里，不会写进项目，也不会污染系统目录。
