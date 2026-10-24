# 电视端待机页（IdleView）设计说明

适用设备：Android 4.3 电视，Android 4.3（API 18），1920×1080 电视，遥控器操作。
实现：`app/src/app/webcast/ui/IdleView.java`（纯 Java + Canvas，无 WebView、无 AndroidX、无动画/定时器、无二维码）。

> **变更（task-5）**：用户反馈电脑端无法扫电视上的二维码，待机页已**移除二维码**（连同 `io.nayuki.qrcodegen` 源码与 QR 许可证）。右侧改为「投屏小贴士」卡片，地址面板加宽到约 2/3 屏宽，方便电脑端直接输入地址。

---

## 1. API 契约（MainActivity 按此调用）

```java
public final class IdleView extends android.view.View {
  public IdleView(android.content.Context c);
  public void setInfo(String url, String ip, String model);
  public void setStatus(String status, boolean connected);
  public void setVersion(String v);
}
```

调用建议（MainActivity 现有实现即如此）：

```java
IdleView idle = new IdleView(this);
setContentView(idle);
idle.setVersion("1.0.0");
idle.setInfo(server.url(), server.getIp(), "Android 4.3 电视"); // url 形如 https://192.168.6.169:8443/
idle.setStatus("投屏中", true);   // 连接建立 / 断流回到待机页时切回 false
```

约定：

- `setInfo(null, null, model)` → 进入离线态（地址面板显示「未连接网络，请检查电视 Wi-Fi」，无二维码占位）。
- `url` 为空但 `ip` 非空 → 自动拼 `https://<ip>:8443/`。
- 四个 setter 均可在任意线程调用；内部用 `invalidate()` / `postInvalidate()` 调度重绘，不做跨线程 UI 操作。
- `status` 在 `connected=true` 时显示为「电脑已连接：<status>」；`status` 为空显示「电脑已连接」；若 `status` 本身已以「电脑已连接」开头（如 MainActivity 传「电脑已连接，正在建立画面…」）则原样显示，不重复前缀。
- `connected=false` 时默认显示「等待电脑连接」；若 `status` 是失败/错误文案（含「失败」「错误」「异常」），则原样显示并改用红点，避免启动失败信息被隐藏。
- `setVersion` 兼容 `"1.0.0"` 与 `"WebCast v1.0.0"`：页脚统一渲染为 `v1.0.0`（去掉重复的应用名前缀、补齐 `v`）；版本为空时省略该段。
- 型号为空时回落 `Android TV`；型号过长时页脚信息自动省略号，绝不与左侧状态文字重叠。

---

## 2. 设计基准与缩放

| 项 | 值 |
| --- | --- |
| 设计画布 | 1920×1080（16:9） |
| 安全边距 | 左右 96、上 84、下 80 设计 px（≥ 需求 48 px，抗 5% 过扫描） |
| 缩放 | `scale = min(viewW/1920, viewH/1080)`，内容水平/垂直居中（16:9 屏上即 1:1） |
| 坐标 | 所有坐标以设计 px 书写，绘制时 `x = offsetX + dx*scale`、`y = offsetY + dy*scale` |
| 重布局 | `onSizeChanged()` 重算矩形 + 渐变着色器 + 字号 + 贴士换行；无位图、无缓存重建 |
| 屏幕密度 | 直接按像素绘制，不经过 dp/sp（电视固定分辨率，规避固件 density 差异） |

---

## 3. 1920×1080 实际布局坐标

```
┌──────────────────────────────────────────────────────────────────────────────────┐ 1920×1080
│ 96                                                                            1824│
│ (100..148) ● WebCast · 局域网投屏            ┌───────────────────────────────┐  │
│                                               │ 投屏小贴士 (1280..1824)        │  │
│ 投屏到这台电视  (baseline 292, 96px)          │ top 200  /  bottom 840        │  │
│ 在电脑浏览器打开下面的地址，点击开始投屏      │ 让画面更稳的 4 个小设置        │  │
│ (baseline 360, 34px)                          │ ───────────────────────────   │  │
│ ┌────────────────────────────────────────┐    │ ① 同一网络                    │  │
│ │ 地址面板 96,420  1128×212  圆角28       │    │    电脑和电视连接同一个…       │  │
│ │ 在电脑浏览器打开以下地址      HTTPS·端口│    │ ② 返回键停止                  │  │
│ │ https://192.168.6.169:8443 (78px 粗体) │    │    投屏中按电视遥控器…         │  │
│ └────────────────────────────────────────┘    │ ③ 画质可调                    │  │
│                                                │    电脑页面可选清晰度…         │  │
│ ① Chrome 等浏览器打开地址  (行心 y=696)      │ ④ 1080p 60Hz                  │  │
│ ② 首次访问 可能提示不安全，点「高级…」(y=780) │    面板 1920×1080 60Hz…       │  │
│ ③ 点「开始投屏」            (行心 y=864)       └───────────────────────────────┘  │
│ ──────────────────────────────────────────────────────────────────────────────── │ y=918
│ ● 等待电脑连接 (baseline 988)                  Android 4.3 电视 · v1.0.0 · 1920×1080  │
└──────────────────────────────────────────────────────────────────────────────────┘
```

精确数值（设计 px，缩放系数 s = 1 时即物理 px）：

| 元素 | 坐标/尺寸 | 说明 |
| --- | --- | --- |
| 品牌胶囊 | x 96，y 100..148，h 48，圆角 24 | 宽度随文案测量（约 300）；青点 r5 在 (118,124) |
| 主标题 | x 96，baseline 292，字号 96，粗体 | 「投屏到」(0xF1F5F9) + 「这台电视」(0x5EEAD4) |
| 副标题 | x 96，baseline 360，字号 34，常规 | 「在电脑浏览器打开下面的地址，点击开始投屏」 |
| 地址面板 | (96,420)–(1224,632)，1128×212，圆角 28，描边 2 | 约占内容区（1728）的 65%，填充 0x0A1729，描边 0x2B5B8F |
| 面板左侧 accent | x 96..104，y 454..598，圆角 4 | 青色 0x38BDF8；离线时琥珀 0xF59E0B |
| 面板标签 | x 142，baseline 476，字号 26 | 在线「在电脑浏览器打开以下地址」/ 离线「网络状态」 |
| 面板右上提示 | 右对齐 x 1184，baseline 476，字号 22 | 在线「HTTPS · 端口 8443」/ 离线「Wi-Fi 未连接」 |
| 地址文本 | x 142，baseline 566，字号 78，粗体 | 可用宽 1042，超宽自动缩字号；离线文案 54px 琥珀色 |
| 步骤圆点 | 圆心 x 119，r 23，行心 y 696/780/864 | 数字 24 粗体居中；步骤文字 x 164、字号 34 |
| 分隔线 | y 918，x 96..1824，线宽 1.5 | 0x17263C |
| 状态行 | 圆点 (104,978) r7；文字 x 128，baseline 988，字号 30 | 未连接黄点 0xFBBF24 / 已连接绿点 0x34D399 / 失败红点 0xF87171 |
| 页脚信息 | 右对齐 x 1824，baseline 988，字号 24 | `<model> · v<version> · 1920×1080`；版本归一化，过长省略号 |
| 贴士卡片 | (1280,200)–(1824,840)，544×640，圆角 40 | 半透明深色 0xE6122238，描边 0x2C4A6E；与面板间距 56 |
| 卡片标题 | x 1324，baseline 286，字号 40，粗体 | 「投屏小贴士」 |
| 卡片副标题 | x 1324，baseline 330，字号 23 | 「让画面更稳的 4 个小设置」 |
| 卡片分隔线 | y 366，x 1324..1780，线宽 1.5 | 0x24405F |
| 贴士行 | 行顶 396/504/612/720，行距 108 | 序号圆 r17 圆心 (1341, 行顶+18)；标题 x 1370、字号 28；正文 x 1370、字号 23、行高 30、最多 2 行、可用宽 410 |

三步提示文案（步骤区，字号 34、文字 x 164；可用宽约 1060 到面板右缘 1224，且不越过卡片左缘 1280）：

1. `① Chrome 等浏览器打开地址`（"Chrome" 粗体）
2. `② 首次访问可能提示不安全，点「高级 → 继续前往」`（整句粗体）（"首次访问" 粗体，其余常规；整行估算 731 px、开发机 PingFang 复刻实测 770 px，均远小于可用 1060，单行不缩字号）
3. `③ 点「开始投屏」`（粗体）

贴士文案（静态、两行排版，`onSizeChanged` 时按当前宽度重新断行）：

1. **同一网络** — 电脑和电视连接同一个 Wi-Fi 或路由器，否则打不开上面的地址。
2. **返回键停止** — 投屏中按电视遥控器的返回键即可停止，画面会回到本页。
3. **画质可调** — 电脑页面可选清晰度、帧率和码率；网络卡时用 720p。
4. **1080p 60Hz** — 电视面板是 1920×1080 60Hz，电脑端选 1080p 即可满屏清晰。

> 面板刷新率以真机 `DisplayDeviceInfo` 实测 `1920×1080@60Hz` 为准（此前空闲时曾出现 50Hz，现已稳定 60Hz）；文案不再出现 50Hz，避免误导。投屏发送帧率由电脑页面控制（协议默认 30fps），与面板 60Hz 无冲突。

断行规则：按字符贪心测量，优先在空格/中文标点处断行；行首禁止出现「，。；、：！？」（标点悬挂到上一行行尾）；超过 2 行时末行加「…」。因贴士为静态文案，断行结果在 `onSizeChanged` 计算一次，`onDraw` 不再测量换行。

---

## 4. 配色

| 用途 | 色值 |
| --- | --- |
| 背景渐变 | 0x0E1D34 → 0x08111F → 0x04070D（LinearGradient） |
| 右侧氛围光 | RadialGradient，中心 (0.72w, 0.42h)，0x332563EB → 透明 |
| 主文字 / 青色强调 / 副文字 | 0xF1F5F9 / 0x5EEAD4 / 0x94A3B8 |
| 面板填充 / 描边 / accent / 提示 | 0x0A1729 / 0x2B5B8F / 0x38BDF8 / 0x5B7A9E |
| 地址文字 | 0xDCEFFF |
| 离线态（标签/正文/描边/accent） | 0xE0B25A / 0xFBBF24 / 0x7A5B22 / 0xF59E0B |
| 步骤圆 / 圆环 / 数字 | 0x123252 / 0x2F6FA8 / 0x7DD3FC |
| 贴士卡填充/描边/标题/副标题 | 0xE6122238（约 90% 不透明）/ 0x2C4A6E / 0xF1F5F9 / 0x7E93AD |
| 贴士卡分隔线 / 序号底 / 条目标题 / 正文 | 0x24405F / 0x0F766E / 0xE2E8F0 / 0x94A3B8 |
| 分隔线 / 页脚信息 | 0x17263C / 0x6B7F99 |
| 状态点（已连接/等待/失败） | 0x34D399 / 0xFBBF24 / 0xF87171 |
| 状态文字（已连接/等待/失败） | 0xA7F3D0 / 0xCBD5E1 / 0xFCA5A5 |

字号阶梯（设计 px，随 s 缩放）：96 标题 / 78 地址 / 54 离线提示 / 40 卡片标题 / 34 副标题·步骤 / 30 状态 / 28 贴士标题 / 26 面板标签 / 24 品牌·页脚 / 23 贴士正文·卡片副标题 / 22 面板提示。
字体仅用 `Typeface.DEFAULT` / `Typeface.DEFAULT_BOLD`（系统 Droid Sans + DroidSansFallback CJK）。

---

## 5. 状态机

| 状态 | 进入条件 | 地址面板 | 贴士卡片 | 底部状态 |
| --- | --- | --- | --- | --- |
| 等待连接 | url 非空，connected=false | 蓝色 `https://ip:8443`（78px） | 固定显示 4 条贴士 | 黄点 +「等待电脑连接」 |
| 已连接 | connected=true | 同上 | 同上 | 绿点 +「电脑已连接：<status>」（已含前缀不重复） |
| 离线 | url 为 null/空 且 ip 为空 | 琥珀色「未连接网络，请检查电视 Wi-Fi」+「Wi-Fi 未连接」 | 同上（贴士不依赖网络，仍显示） | 保持 setStatus 语义（默认黄点等待） |
| 服务/网络失败 | connected=false 且 status 含「失败/错误/异常」 | 同上 | 同上 | 红点 + 原样失败文案（如「服务启动失败：…」） |

页面**不再有二维码及二维码占位**；离线时仅改变地址面板配色与文案。

---

## 6. 性能与 API18 约束

- 不使用 WebView、SurfaceView、动画、Choreographer、Handler 定时器、Timer；静止画面不产生周期重绘。
- 无 Bitmap / 无自定义缓存：只有一次性计算的贴士断行文本；`onDraw` 内不做换行测量、不做内存分配（复用 Paint/RectF 与预计算字符串）。
- 仅在初始化、`setInfo`/`setStatus`/`setVersion`、`onSizeChanged` 时 `invalidate()`；旧 GPU 不会持续耗电。
- `onSizeChanged` 只重算矩形、渐变着色器和贴士断行，不重建任何图片资源。
- 仅使用 API18 及以下接口：`Canvas`、`Paint`、`LinearGradient`、`RadialGradient`、`RectF`、`Typeface.DEFAULT*`、`Looper`、`View.postInvalidate`。无 lambda / 方法引用（`-source 8` 下也可编译，但代码保持匿名类风格）。
- 无网络/磁盘/反射操作、无第三方库依赖。

---

## 7. 编译验证（本机实测）

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
export PATH="$JAVA_HOME/bin:$PATH"
rm -rf work/check-ui && mkdir -p work/check-ui
javac -source 8 -target 8 -nowarn \
  -bootclasspath "$HOME/Library/Android/sdk/platforms/android-18/android.jar" \
  -sourcepath app/src -d work/check-ui app/src/app/webcast/ui/IdleView.java
```

结果：`EXIT=0`（javac 25.0.3，`-bootclasspath android-18` 强制校验 API，若引用 API19+ 会直接报错）。
产物：仅 `work/check-ui/app/webcast/ui/IdleView.class`（无 qrcodegen class）。
`grep -rn "qrcodegen\|QrCode\|Bitmap" app/src --include='*.java'` → 无匹配，`app/src` 不再引用二维码库。
未运行 `./build.sh`，未使用 adb，未用浏览器，未改 manifest/res/build 脚本/其他包。

联调验证：把 `app/src` 全部 Java 源文件（含当前 MainActivity/CastService/net/media 与本页）与已生成 `R.java` 一起编译到临时目录，`EXIT=0`，证明 MainActivity 对 `IdleView` 的四次调用与实现完全匹配。

字节码引用的 Android 类型仅：`Context`、`View`、`Canvas`、`Paint`、`Paint.FontMetrics`、`Paint.Style`、`RectF`、`LinearGradient`、`RadialGradient`、`Shader`、`Shader.TileMode`、`Typeface`、`Looper`（全部 API18 或更早）。

`javap` 公开 API 核对：

```
public final class app.webcast.ui.IdleView extends android.view.View {
  public app.webcast.ui.IdleView(android.content.Context);
  public void setInfo(java.lang.String, java.lang.String, java.lang.String);
  public void setStatus(java.lang.String, boolean);
  public void setVersion(java.lang.String);
  protected void onSizeChanged(int, int, int, int);
  protected void onDraw(android.graphics.Canvas);
}
```

---

## 8. 静态自检（不依赖设备）

几何核算（设计 px）：

| 检查项 | 期望 | 结果 |
| --- | --- | --- |
| 地址面板宽 1128 / 内容宽 1728 | 约 2/3 | ✓ 65.3%，面板右缘 1224 |
| 面板右缘 1224 + 间距 56 = 卡片左缘 1280 | 不重叠 | ✓ |
| 面板底 632 < 步骤①圆顶 673 | 无重叠 | ✓ 间距 41 |
| 步骤③圆底 887 < 分隔线 918 | 无重叠 | ✓ 间距 31 |
| 贴士卡底 840 < 分隔线 918 | 不压页脚 | ✓ 间距 78 |
| 贴士第 4 行正文第二行 baseline 812 + descent ≈ 819 | < 卡底 840 | ✓ 间距 21 |
| 所有元素左右边距 96 / 上下安全区 | ≥ 48 | ✓ 左 96、右 96、顶 100、底 92 |
| 标题 96..768 vs 面板右缘 1224 | 不碰撞 | ✓ |
| URL 25 字符（78px 粗体）≈ 905 px | ≤ 可用宽 1042 | ✓ 不缩字号；27 字符 ≈ 1076 时自动缩至 ~75px |
| 离线文案（54px）≈ 783 px | ≤ 1042 | ✓ |
| 步骤②新文案（34px，粗体「首次访问」+常规） | ≤ 可用 1060（x 164..1224），且不越过卡片左缘 1280 | ✓ 估算 731 px / PingFang 复刻 770 px，单行不缩字号、不换行 |
| 贴士正文 4 条 | 每条 2 行内 | ✓ 断行算法 + 标点悬挂 + 末行省略兜底 |

开发机 Java2D 复刻本页布局（1920×1080）逐像素检查：标题/面板/步骤/贴士卡/页脚无重叠，贴士 4 条均为 2 行、无行首标点、无省略号，卡片无空洞。文本宽度与真机 Roboto/DroidSansFallback 存在字体差异，最终以真机截图为准；地址/状态有自动缩字号与省略号兜底。

---

## 9. 真机验收清单（给 Lead 截图用）

1. 1920×1080 全屏，四边无被裁内容；留白左右 96、上 100、底 92。
2. 左上品牌胶囊、标题「投屏到**这台电视**」（后半段青色正常，无 tofu）。
3. 地址面板明显变宽（约 2/3 屏）：显示 `https://<电视 IP>:8443`（无尾斜杠、78px 粗体、不溢出）；右上「HTTPS · 端口 8443」。
4. 三步文案完整：「Chrome 等浏览器打开地址」「首次访问可能提示不安全，点「高级 → 继续前往」」（整句粗体）「点「开始投屏」」；第二步单行显示、不与右侧卡片重叠；`→`、`「」` 无豆腐块。
5. 右侧「投屏小贴士」半透明卡片：标题 + 副标题 + 4 条贴士，每条标题加正文 2 行，排版均匀不空、不溢出卡片。
6. 底部：黄点「等待电脑连接」→ 发送端连上后变绿点「电脑已连接：…」；右下 `Android 4.3 电视 · v1.0.0 · 1920×1080`。
7. 断网/无 IP 场景：地址面板琥珀色「未连接网络，请检查电视 Wi-Fi」+ 右上「Wi-Fi 未连接」，页面无二维码/二维码占位。
8. 静态画面，无闪烁/动画；长时间待机不升温。

---

## 10. 二维码移除与清理记录（task-5）

- 删除源码目录 `app/src/io/nayuki/qrcodegen/`（QrCode/QrSegment/BitBuffer/DataTooLongException/LegacyObjects 共 5 个文件），删除后 `app/src/io` 为空目录，已一并移除。
- 删除 `app/assets/licenses/QR-Code-generator.txt`；`app/assets/licenses/README.md` 中的 QR 条目已移除。
- `IdleView.java` 中删除：`io.nayuki.qrcodegen.QrCode` import、`Bitmap` import、`buildQr()`、QR Bitmap 缓存字段、`qrPaint`、QR 区域/占位/图形绘制方法及相关色值常量。
- `docs/UI.md` 同步删除二维码设计、扫码说明与 QR 验收项。
- 编译产物 `work/check-ui` 重新生成，仅含 `IdleView.class`。

---

## 11. 已知风险

1. **CJK 粗体**：API18 固件对 DroidSansFallback 做合成加粗，个别字形可能偏粗；若真机观感不佳，可把标题/贴士标题改为 `Typeface.DEFAULT`（局部常量改动）。
2. **字符可用性**：`→`(U+2192)、`「」`、`×`(U+00D7) 依赖系统字体；截图若出现豆腐块，需把「高级 → 继续前往」替换为纯中文写法，`×` 改 `x`。
3. **贴士文案宽度**：按 DroidSansFallback 全角 ≈ 1em 估算均为 2 行；若真机字体更宽导致 3 行，断行算法会末行省略，不会溢出卡片，但如需更美观可再精简文案。
4. **状态文字过长**：与右下页脚信息按可用宽度自动省略号截断，不会重叠。
5. **非 1920×1080 输出**（如 720p）：整体等比缩放，文字仍清晰；断行结果随宽度按比例重算。
6. 遥控器焦点：待机页无交互控件，`setFocusable(false)`；按键由 Activity 处理（本视图不拦截）。
