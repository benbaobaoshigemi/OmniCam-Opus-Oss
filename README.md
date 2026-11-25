# OmniCam Opus

Find X9 Ultra（PMA110）的 KernelSU 模块与设置应用：理光 GR、POP、X10 滤镜与调色盘、大师色彩、自然模式、哈苏超清、移轴模式、柔光（弱 = HDF、中 = 1/4 黑柔、强 = 白柔）、XPAN 显影实况、XPan 取景净化、AI 灵感构图（云端建议）、专业视频 OpenGate（0.6x / 1x / 3x 全幅读出，SDR / HDR / O-Log2）。LSPosed 钩子加原生库在相机进程里工作，模块文件通过 KSU 绑定覆盖 `/odm` 配置。当前版本 **Opus 3.0.0（versionCode 525）**。

## 目录

滤镜与水印业务结构见 [FILTER_WATERMARK.md](docs/FILTER_WATERMARK.md)。

| 路径 | 内容 |
|---|---|
| `app/` | 设置应用与钩子：Java（`hook/*`）、Kotlin（`ui/*`）、C++（`cpp/*`，大师色彩引擎 `master_*`） |
| `module/` | KSU 模块：脚本、`payload/` 配置与资源、`system/odm/lib64/` 库 |
| `stock/` | 模块里厂商文件的原件（`originals/`）、补丁（`diffs/`）与清单（来源、哈希），`tools/stock_diff.py` 由原件重建成品，见 [STOCK.md](docs/STOCK.md) |
| `tools/` | 生成与离线测试：`mastercolor/`（大师色彩）、`softlight/`（柔光权重表）、`symbols/`（混淆符号特征）、`opengate/`（OpenGate 调校、菜单与 UI layer）、`basictone_router/`（X9/X10 BasicTone 路由库源码）、`stock_diff.py`、`patch_25mp.py`、`bake_master_luts.py`、`offline_tests/` |
| `tools/refs.py` | 仓库外参考资料的位置（`MUSE_REFS` 可改根目录） |
| `docs/` | `MODULE.md` 各功能的原理与涉及文件、`DEV_NOTES.md` 设备操作与排查、`STOCK.md` 厂商文件与差分 |

大师色彩引擎（`app/src/main/cpp/master_*`、`style_*`）把一帧的 LMT 烘成 64³ 表，分三段：场景前级（镜头 Adobe Standard 标定，`master_core.cpp`）→ 风格插件 → 输出级（显示色经大师 HDR 模型反推成 LMT 码）。风格是编译进包的插件（`master_style.h` 写明合同，`master_styles.cpp` 登记）：输入是 ProPhoto D50 的场景线性光、帧元数据（色温、CCM、镜头、路径）和风格自己的设置，输出是 P3 显示线性 [0, 1]。HNCS、Lumix、胶片、Canon、Darktable、固件移植（Leica M9、Fuji）、基础各是一个插件，另有“原厂表”插件直接提供 X7U / X8U 的整张表。
风格设置是 `名字 → 数值` 的键值，宿主不解释：设置应用写进 `files/jiege/mastercolor/look`（成片）与预览的 `key.bin`（`MCOLKEY9`），插件自己读取、限幅、补默认值；某项缺失就是该风格的默认。
每个风格在 Java 侧有一个自述类（`settings/MasterStyle` 的子类，`MasterStyles` 登记）：显示名与说明、持久化的风格序号、选项卡（照片风格、胶片与成片方式……）、参数（设置名、范围、默认、单位）和“当前选择对应哪个 look id”。大师色彩页和相机里的钩子只读这份自述：页面按它画选项卡与滑杆，钩子按它算 look id、收集参数。新增风格 = 一个 `style_*.cpp` 加一个自述类，各登记一行。`exposure` 每个渲染插件都认，其余设置名（`gamma`、`contrast`、`hue`、`black`、`white`、`toe`、`shoulder`）由各自的自述类声明；胶片清单另见 `tools/mastercolor/catalog.json`（资源构建用），`check_catalog.py` 核对两者一致。胶片 look 由 `tools/mastercolor/fotufilm_looks.py` 预烘：fotufilm 色彩引擎（工作区 `实验\fotufilm色彩引擎`，路径走 `refs.py`）逐点算出 65³ 格点，每个胶片一个文件（负片另有“数字参考”一份）。

大师色彩的“曝光补偿”有两种：偏移量（加减固定 EV；“基准模式”开启时抵消相机自己的 EV，偏移量 +1 EV、相机 -0.3 EV 则引擎提亮 1.3 EV，相机降 EV 保高光、成片亮度仍在基准上；相机 EV 取自预览结果的 `CONTROL_AE_EXPOSURE_COMPENSATION`）和对齐（把所选 look 对到原厂大师模式（LMTLut3）的曝光）：中灰、中间调（中灰下 3 档到上 1 档的平均明度）、高光（上 1 档到传感器削顶点）、白点（90% 漫反射白）。对齐量是加在曝光补偿上的 EV 偏移，由 `tools/mastercolor/align_table.py` 生成 `MasterAlignTable.java`（中性灰阶，偏移与色温无关）；look 达不到原厂值时（胶片的高光与白点）不提供该项。

## 构建

环境：Windows、JDK 17、Android SDK 37、NDK 29.0.14206865、CMake 3.22.1、Python（含 numpy、pycryptodome）、Git for Windows。`local.properties` 写 `sdk.dir=…`（已忽略）。

```powershell
$env:OMNICAM_PYTHON = 'python'
.\build.ps1
```

`build.ps1` 先跑色彩目录检查、离线测试和脚本语法检查，再 `assembleRelease`。根目录有 `port.keystore`（已忽略；别名 grport）或设置 `OMNICAM_KEYSTORE`，且密钥库密码由 Gradle 属性 `omnicam.keystore.password`（写在用户目录的 `~/.gradle/gradle.properties`，不入库）或环境变量 `OMNICAM_KEYSTORE_PASSWORD` 给出时，产出签名 APK 并打包 `OmniCam-<版本>-KSU.zip`（module.prop 的 version，空格换成连字符），否则只产出未签名 APK。APK、zip 是构建产物，不入库，发布包放工作区的 `发布\Muse\`。

## 发布与版本

每个正式更新一次提交加 `v<版本>` 标签。版本号在 `module/module.prop` 与 `app/build.gradle.kts` 两处。
