# 协作约定

这些是本项目固定的维护流程，供人或自动代理参考。

## 每次改完代码

1. 在项目根目录构建并跑测试：

   ```bat
   build.cmd --offline build
   ```

   确认 `BUILD SUCCESSFUL`，测试 0 失败。首次构建或依赖变化时去掉 `--offline`。

2. 把新构建安装到本机 simmc 实例：

   ```bat
   tools\deploy.cmd
   ```

   - 目标实例：`D:\PCL2\simmc\.minecraft\versions\simmc`
   - 脚本会先删除实例里的旧版 `rail-restock-*.jar` / `feeder-restock-*.jar`，再复制 `build/libs` 里最新的 jar（没有构建产物时退回 `dist/<版本>/`），最后校验 SHA256。
   - 修改文件名或实例路径时用参数：`tools\deploy.cmd -Instance "D:\...\versions\simmc" -Jar "path\to\jar"`。

3. **游戏正在运行时不要直接替换 jar**（Windows 会锁住文件）。改为后台执行：

   ```bat
   tools\deploy.cmd -Wait
   ```

   它会等游戏进程退出后再安装；把任务 ID 告诉用户即可，不要反复轮询等待。

## 发布

- 改 `gradle.properties` 的 `mod_version` 与 `CHANGELOG.md`，构建后把 `feeder-restock-<版本>.jar`、`SHA256SUMS.txt`、`BUILD_REPORT.md` 放进 `dist/<版本>/`。
- 提交信息用英文简述改动；分支固定 `1.21.11-fabric`。
- Release 标签 `v<版本>`，附件 `feeder-restock-<版本>.jar`。

## 不要做的事

- 不要删除用户在会话根目录、实例目录或存档里的文件（例如 `错误报告-*.zip`、`options.txt`、世界存档）。
- 不要在游戏运行时强制删除 jar 或结束进程；交给 `-Wait` 排队。
- 不要把 `build/`、`.gradle/`、Gradle 发行包或依赖缓存提交进仓库。
