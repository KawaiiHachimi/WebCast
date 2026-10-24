# Third-party licenses

本项目（WebCast 电视端）的第三方组件为 `third_party/conscrypt`（Conscrypt 2.5.3，Apache-2.0，仅用于 TLS），其 LICENSE/NOTICE 见该目录；APK 内不再包含其他第三方库。

## 变更记录

- task-5：待机页移除二维码（电脑端无法扫码），同步删除 vendored 的
  `io.nayuki.qrcodegen`（MIT）源码与 `QR-Code-generator.txt` 许可证文件。
  旧版本曾从 https://github.com/nayuki/QR-Code-generator 复制该库（MIT，上游版权头保留），
  该依赖现已完全移除，`app/src` 不再引用。
- 其余网络/TLS 能力使用 Android 平台 API 与 `third_party/conscrypt`（构建依赖，其许可证
  随构建产物说明，不在此目录）。
