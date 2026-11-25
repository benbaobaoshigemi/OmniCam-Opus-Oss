# 厂商文件：原件、成品与补丁

模块里凡是来自厂商的文件（OPPO、realme、小米的库，ISP 调校，相机配置，LUT 表，以及改了名的 NDK 运行库）都三样齐全地放在仓库里：

- 原件：`stock/originals/<来源>/<设备路径>`，从对应机器原样取出；
- 成品：`module/` 下模块实际装上的文件；
- 补丁：`stock/diffs/<成品路径去掉 module/>.bsdiff|.patch`。

`stock/manifest.json` 逐项记录成品（`out`）、原件出处（`from`，`来源:路径`）、原件与成品的 sha256、补丁类型。`tools/stock_diff.py` 只用仓库里的内容就能从原件重建全部成品并核对哈希，不需要连手机或另找固件。

## 补丁类型

| kind | 成品怎么来 | 补丁 |
|---|---|---|
| `bsdiff` | 原件加二进制差分（bsdiff4） | `.bsdiff` |
| `text` | 原件文本加统一格式差分；`decrypt` 表示原件是 OPLUS 加密配置，差分从明文算起（密钥取自同一机器的 `/odm/lib64/libOplusSecurity.so`，也在原件目录里）；`reformat` 表示先把原件 JSON 按模块脚本的写法重新排版（去掉注释，缩进 2），差分里只剩真正的改动 | `.patch` |
| `rename` | 原件里一个名字换成等长的另一个名字（`rename`: [旧, 新]） | 清单里的 `rename` 本身 |
| `copy` | 原件原样；成品就是原件，不重复存放 | 无 |
| `build` | 由本仓库源码构建（`built_by`），没有原件 | 无 |

`made_by` 是当初从原件生成成品的脚本；补丁负责复现，脚本说明改动怎么做出来（补丁点与原指令字写在脚本里）。

来源（`sources`）：

| 来源 | 机器与版本 |
|---|---|
| `pma110` | OPPO Find X9 Ultra（PMA110），系统 17.0.1.200 |
| `pmw110` | OPPO Find X10（PMW110），PMW110_11.A.01_001_202609190059 |
| `rmx5200` | realme RMX5200，RMX5200_11_A.01 |
| `x8u` | OPPO Find X8 Ultra |
| `cph2745` | OPPO CPH2745 |
| `xiaomi17u` | 小米 17 Ultra |
| `ndk` | Android NDK，路径为 NDK 目录内的相对路径（首段是版本号） |
| `module` | 本模块的另一个文件，先重建它再派生（`unlock25mp/`、`face200m/` 里的配置都从 `payload/` 下的配置派生） |

## 重建与核对

需要 Python 3 与 `bsdiff4`、`pycryptodome`。

```
python tools/stock_diff.py apply
python tools/stock_diff.py check
```

- `apply`：从 `stock/originals` 与补丁重建成品写回 `module/`，逐项核对哈希；`--into 目录` 写到别处，`--only 片段` 只处理 `out` 含该片段的项。
- `check`：逐项从原件重建，与仓库里的成品比对。

`build` 项不在这里生成：`libBasicTonePhoto.so` 由 `tools/basictone_router/build.py` 构建，`monopan_helper` 由 `app/src/main/cpp/CMakeLists.txt` 随应用构建。

## 改了厂商文件之后

改动仍用原来的脚本做（`made_by`），然后：

```
python tools/stock_diff.py make
```

`make` 从工作区的固件副本（`tools/refs.py`）把原件复制进 `stock/originals`，按当前成品重切补丁、更新哈希，清掉不再使用的原件和补丁，最后跑一遍 `check`。新增厂商文件时先在 `stock/manifest.json` 加一项（`out`、`from`、`kind`），再运行 `make`。

## 不在清单里的载荷

`payload/master_color`（大师色彩表）、`meishe_lut`（滤镜 LUT）等由 `tools/` 下的脚本从本仓库数据生成。水印与画框素材（`leica_watermark`、`leica_series`、`more_series`、`phocus_watermark`、`pld_watermark`、`watermark_drawables`、`apk_resources`、`earth_explorer`、`watermark_master_styles`）尚未逐项登记来源。
