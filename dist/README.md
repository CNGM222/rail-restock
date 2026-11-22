# 产物目录

`dist/` 只保存**可以直接安装的发布包**，源码和构建脚本都在上一层。

```
dist/
├── 0.5.3/                    当前版本
│   ├── rail-restock-0.5.3.jar   ← 放进 mods 的就是这个
│   ├── SHA256SUMS.txt           校验值
│   └── BUILD_REPORT.md          该版本的构建与验证记录
└── archive/                  历史版本（仅留档）
    └── <版本>/ ...
```

- 每个版本的正式下载都在 GitHub Releases：<https://github.com/CNGM222/rail-restock/releases>
- `archive/` 只是本地留档，方便对比旧版本；不需要时整目录删掉即可，不影响构建。
- 构建产物实际生成在 `build/libs/`，`build/` 是临时目录，可随时删除。

## 安装

1. 退出游戏。
2. 删除 `mods` 里旧版的 `rail-restock-*.jar`，只保留一个。
3. 把 `dist/<版本>/rail-restock-<版本>.jar` 复制进 `mods`（**不要**用 `-sources.jar`）。
