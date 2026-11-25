# 模块机制

Find X9 Ultra（PMA110，相机 7.006.103）上各项功能的原理与涉及文件。配置类改动在 `module/`，钩子在 `app/src/main/java/local/jiege/hook/`。设备排查方法见 [DEV_NOTES.md](DEV_NOTES.md)。

## 相册调色盘

相册（17.10.7）的“色彩风格”页（风格列表、调色盘面板、按照片 `filter.info` 还原相机的滤镜与调色盘）有三处：

1. 开关 `feature_is_support_color_palette` 要求 `ro.product.first_api_level >= 37`，本机是 36，其余条件（相机 `com.oplus.feature.color.palette.support`、IPU 调色盘、Find 系列、工程保存）都满足。钩子 `palette/GalleryColorPalette` 只把这一个开关答为真。
2. 调色盘模式经 `CombineLutProcessUnitSDK`、`PaletteColorTable` 出图，系统的 `com.oplus.camera.unit.sdk.jar` 自带这两个类；`GalleryColorPalette.hookSdkLoader` 把钩子挂在相册加载的这个共享库加载器上，不替换加载器。
3. 出图路径：相册给 SDK 的是 GL 纹理，SDK 走系统 APS 的纹理入口（`input_texture`／`output_texture`），引擎经 `libBasicTonePhoto.so` 路由到 X10 后端（相册传 CamMode 6）。路由库源码是 `tools/basictone_router/router.c`：拍照（6）、人像（8）且请求带调色盘时走 `libBasicTonePhotoX10.so`（Find X10 原库），其余走 `libBasicTonePhotoX9.so`（本机原库）。调色表来自 `payload/x10_basictone/palette`。
4. 大师成片的调色盘：大师成片的 `filter.info` 为版本 4，`capture.mode` 为 master。`palette/GalleryPaletteScope` 让大师成片、调色盘拍摄的照片和无 `filter.info` 的图进入调色盘模式；影调、饱和度、柔光没动时用相册自己的直通（`shouldPassThroughAsOriginal`），阶段不运行，主图只经滤镜阶段。阶段运行时，引擎在 `is_hdr` 下按照片的 `hdr.transform.data` 重建 HDR 再调色，而大师成片里这份数据是相机在成片最后一步已经做过的大师 HDR 变换，再用一次等于叠第二遍影调；`palette/GalleryColorPalette` 对大师成片把该数据置空，引擎按 SDR 编辑只处理基图，增益图由相册原样带过，成片仍是 UltraHDR。其他拍摄的照片保持原行为，相机的其他滤镜照片仍用原厂滤镜编辑器。
5. 滤镜识别：相机在大师成片的 `filter.info`（偏移 28，最长 100 字节）里记应用侧的类型（`800t.bin`、`positive_normal.bin`），相册大师组里列的是大师文件（`800t_master.bin`、`positive_master.bin`），按名字找不到就丢滤镜。`GalleryPaletteScope` 钩 `FileExtendedContainer.getExtensionData("filter.info")`，对 `capture.mode` 为 master 的照片，把去掉 `_normal`／`_sdr`／`_hdr` 变体后加 `_master.bin` 的名字回给相册，只在大师组里确有同名项时才改（组内容由 `GalleryFilters` 在 SDK 初始化后记下）。
6. 模块滤镜的调色盘：相册只对 SDK 颜色表（`PaletteColorTable.hasPalette`／`get`、`PaletteSeekbarColorTable.get`）里有的类型显示调色盘入口，这两张表是固定 Map，不含模块滤镜。`GalleryFilters` 钩这三个方法，对目录里的模块滤镜类型答“有”，并给表里 `default` 的行，面板和强度条可以绘制。

相册进程需要看到 `/odm/lib64` 的叠加（`libAlgoInterface`、`libBasicTonePhoto` 的 X10 路由）。Hybrid Mount 把它创建的叠加登记到 KernelSU 的内核卸载名单，名单对档案里“卸载模块”未关的应用生效；档案只在 SukiSU 里改，`pm uninstall-system-updates` 会让 KernelSU 清除应用的档案（内核日志 `KernelSU: prune uid`）。所以 `post-mount.sh` 和 `service.sh` 用 `ksud umount remove /odm/lib64` 把这一项从名单去掉，相册和相机都不依赖档案；`/odm/etc` 下的配置绑定本来就不在名单里。其余叠加（`/system/etc`、`/product/overlay`、`/my_product/vendor`）和 4 个 ISP 绑定仍按原样卸载。`UnmountedGuard.filesMounted` 同时比对 `/odm/etc` 的配置绑定和 `/odm/lib64` 的库绑定，缺任一项时功能停用，诊断页标红。
相册的滤镜列表来自同一个 SDK 的 `FilterGroupManager`：`GalleryFilters` 在它初始化后把目录里默认开启的模块滤镜追加到普通组和大师组末尾（名称用目录标题，原厂序号不变）；导入滤镜在相机私有目录、相册读不到，不列出；LUT 不在 `/odm/etc/camera/meishe_lut`、`filters_lut` 的也不列出。

## 相册编辑理光水印

理光模式的照片尾部有 `gr.effect.info`（GRInfo，13 个 float），水印样式为 `gr_style_N`。相册编辑器只在照片解析出 GRInfo 且 `feature_is_camera_gr_supported` 为真时提供理光水印，而扩展信息解析（符号 `Extend.parse`）只在水印品牌含 realme（符号 `Brand.isRealme`）时读 GRInfo；本机照片的品牌是 OPPO。`GrGalleryWatermark` 在解析带 `gr.effect.info` 的照片期间让品牌判断返回真，其余照片不受影响。

水印状态由 `watermark/WatermarkCore` 决定分组，`WatermarkHooks` 接入相机的读写、模式切换、拍摄判断和编辑器返回。GR 照片固定独立；XPAN 由隔离设置决定独立或共享，其他拍照模式默认共享。`CameraWatermarkStore` 保存每组完整的样式、备用样式、开启状态和拍摄开关，关闭也是记忆值。GR 使用原厂限定模式槽，普通组与 XPAN 复用原厂照片槽；`WatermarkFiles` 保留其资源副本。相机设置页打开的编辑器记录发起组，Binder 和广播回传均写入该组；广播的异步资源拷贝完成后保留对应样式，并恢复当前组的资源。`WatermarkStyles.load` 保留原厂按拍摄 identity 存储的延迟处理 JSON，只为实时照片槽修正缓存样式。

理光水印开关为 `pref_watermark_gr_capture_switch_key`；拍摄判断期间的通用照片开关读取受 GR 开关约束。普通照片与 GR 写入按组分区，编辑器回传的另一侧空数据不能关闭该组；键删除也遵循同一归属。设置页的摘要读对应组的照片开关与样式。`GrWatermarkRenderer` 处理样式、字体和图标，`GrGalleryWatermark` 处理相册中的样式入口和素材。`GrModeHooks` 的模式进入调用 `BaseMode.onStart()`，保留理光水印状态。相机传给相册的空样式代表关闭，编辑页保留该空值。

## 大师水印的滤镜名

大师水印（`com.oplus.support.new.master.watermark`）的滤镜名来自预设记录的 `filterName`。`filters/FilterLabels` 按 `FilterCore` 的资源标题替换 `FilterNames.nameAt` 的结果，并通过 `Preset.filterName` 精确写入预设的滤镜名称字段；预设名称与参数键各自保留。模块 LUT 的原厂名称资源和捕获枚举映射独立于业务名称：部分 LUT 使用 Radiance 的注册资源与枚举，实际 LUT 文件仍由类型字符串指定。相册重建水印时，`watermark/WatermarkFilterNames` 优先使用照片 `master.mode.preset.info` 中的滤镜名称。理光风格的机型文字由 `ModelSpoof` 的 `MarketName.*` 适配负责。

## POP 成片的滑杆与 10X

POP 的光感、自然饱和度、色温、色调随拍照参数以 `KEY_MASTER_MODE_*` 送到引擎，成片要生效就必须经过 BasicTone。拍照链路里有四道门：

1. 初始化名单：SDK 把 `retro_camera_mode` 映射为 `common` 拍照配置，`oplus_camera_algo_switch_config` 里 `common` 的前后置项 `aps_algo_basic_tone` 都必须为 1，引擎才注册 BasicTone。
2. 决策规则：`SINGLE_ALGO_BASIC_TONE` 的条件要有 POP 分支（`REAR_NORMAL` 且 `retro_camera_mode`），另加前置 POP 分支（`FRONT_NORMAL` 且 `retro_camera_mode`，SDR 下前置靠它进入算法清单，其余模式要求 `ultra_hdr_enable`），并对其余使用 `common` 的后置三个模式、`AI_HIGH_PIXEL` 和前置 `FRONT_NORMAL/BOKEH/NIGHT` 加“非 POP”守卫，使它们保持原行为。文件：`payload/oplus_camera_preview_decision_config.json`（`post-fs-data.sh` 绑定到 `/odm`）与 `payload/unlock25mp/…` 同名文件（25MP 开关打开时读取）。
3. 流水线节点：`ApsCapDecisionRearNormal25021::addCommonPipelines` 与 `ApsCapDecisionFrontNormal25021::addCommonPipelines` 只在 `ultra_hdr_enable` 为真时加入 BasicTone 节点（算法 0x4e，三个节点）。
4. 全 0 跳过：BasicTone 预检在 POP 且四个值全 0 时直接返回；HybridRaw 交给它的是 10bit P010，跳过会把 P010 直接送去编码，主图损坏。

`libAlgoInterface.so`（`tools/patch_algo_interface.py` 生成）把第 3 处的两条分支（后置与前置 `addCommonPipelines` 里的 BasicTone 门）和第 4 处（`basicToneProcessV1` 的全 0 检查）改为 NOP，POP 走 SDR 时后置与前置的滑杆也生效。

10X 条纹：长焦（物理相机 4）硬件 IPE 离线处理在“10bit 输入 → 8bit 输出”时输出损坏，与 ISP 调校文件无关。让 IPE 保持 10bit 输出、由 BasicTone 转 8bit 即绕开。移轴不走这条路，见“移轴模式”。

## 调色盘：人像与前置成片

调色盘算法 `aps_algo_color_palette` 在后置人像、前置人像、前置拍照的算法清单里，但流水线没有节点去执行它。`libAlgoInterface.so` 在四个类（`RearNormal25021`、`RearBokeh25021`、`FrontBokeh25021`、`FrontNormal25021`）的 `addCommonPipelines` 入口跳转到代码段末尾空隙里的共用例程（`tools/algo/palette_nodes.S`，由 `tools/patch_algo_interface.py` 放入并扩展代码段长度）：

- 调色盘算法未启用：直接返回；
- Ultra HDR 开：把 `paletteInit/Proc/Uninit` 追加进流水线数组；
- Ultra HDR 关且没有滤镜算法：走库里原有的单节点 `0x458` 路径。

`paletteProc` 的依赖：后置人像 `bokeh3`；前置人像 `copyUhdrP010`；前置拍照 `fbHdrConvertUninit`（不能依赖 `copyUhdrP010`，会成环使 `endCapture` 中止）；`aps_algo_rsp` 启用时再加 `rspProcess`，并让 `rspUninit` 依赖 `paletteProc`，否则有人脸时 RSP 提前释放蒙版，相机在 `~ReSamPlingStrategy` abort。

## POP 0.6x 拍照

POP 在 0.6x 拍照会让 HAL（`vendor.qti.camera.provider-service`）在 `NodeBase::SetupRequestData` 的请求环缓冲里 abort。0.6x 的决策选 `ALGO_HR_REAR_UW0P6X-1X_50M`，需要 `rear_wide` 与 `rear_ultra_tele` 的 `surface_key_capture_full_size` 输出流，而 `camera_unit_config` 里 POP 的用例 `retro_camera_case` 的 `capture_raw10_fullsize` 只列了 `rear_main`、`rear_tele`。现已与 `sat_photo_full_size_case` 对齐（main、wide、tele、ultra_tele）。

## POP 鱼眼

鱼眼成片的圆球外是底座：`payload/meishe_lut/fisheyelayer.rgba.bin`（864×648 的 RGBA，黑底、中央透明圆孔，取自 ROM `PND110_17.0.1.202` 的 `/odm/etc/camera/meishe_lut/`，随 `meishe_lut` 合并目录挂到 `/odm/etc/camera/meishe_lut`）。`libAlgoInterface` 在 `retro_fish_eye_enable` 为真时给美摄设 `Fish Eye Enable` 与 `Fish Eye Layer File Path`，17.0.1.200 的 `libmsnativefilter.so` 原本不认路径，圆外是纯黑。

`payload/algo/libmsnativefilter.so` 是 17.0.1.200 的原库加 `tools/patch_msnativefilter.py` 的补丁，`post-mount.sh` 绑到 `/odm/lib64/libmsnativefilter.so`（标签 `same_process_hal_file`，与原库相同）。补丁动鱼眼、软焦与流光胶片（竖图、Ultra HDR 混合），其他模式与滤镜走原代码：

- **成片的两条路径**：基础、Inst、CCD 经 `MsRenderHardwareBuffer` → `CNvComputeRenderer` → `CompoundLut::RenderEffectYuvGps`，用一份 GL 文本着色器（`COMPOUND_YUV_FISHEYE` 变体）；预览、相机里的缩略图、流光走 18 份内嵌的 QSB 图形着色器。两处都换成 ROM 17.0.1.202 的鱼眼算法（`tools/algo/fisheye_glsl.py`、`fisheye_qsb.py`），并加了底座采样器（GL 绑定在纹理单元 13，QSB 在 12）。
- **底座纹理**：`hook_set_string` 记下 `Fish Eye Layer File Path`；`hook_bind_base`（QSB 路线）与 `hook_yuv_prep`、`hook_yuv_bind`、`hook_yuv_uniform`（GL 路线）按 `CNvFilterResourceManager` 缓存纹理，首次用 `UploadLayerTex` 上传，管理器销毁或释放资源时清掉。GL 路线里管理器在着色器绑定处已不在寄存器，纹理按线程（`tpidr_el0`）交接。状态放在 `.bss` 末尾新增的一页（`0x10a8800`）。
- **裁切**：新 ROM 的做法是“补边、画圆、裁边”：把 4:3 的输入原样画进短边放大 7/6 的帧（上下补黑边），圆内接于这个帧，再把黑边裁掉，所以圆的直径是画面短边的 7/6，两侧被裁。补丁在着色器里用坐标做同样的变换（`fyS`、`fyB`、`fyIn`），不经过拷贝。只对 4:3 画幅生效：其他画幅沿用 `RenderEffectYuvGps` 原有的“裁到 4:3、图形管线、拷回”路线，`GetCompundLutGraphicsPilelineUbo` 里的钩子（`hook_fish_flag`）读 `ParamSet` 的画幅串，非 4:3 时把写进 uniform 块的鱼眼开关从 1 改成 2，QSB 着色器据此不再补边。
- **清晰度**：`RenderEffectYuvGps` 开头会把输入先缩小再做鱼眼，补丁把系数改成与 ROM 17.0.1.202 一致：输出宽 ≥ 2600 取 0.85，更小不缩放（原值是 0.7 与 0.85）。
- **鱼眼参数**：手机的 `ParamSet` 没有焦距，着色器固定用后置超广角（0.6x）的取值：`scale` 0.6、各向异性 0.97、边缘镜像折返。

POP 的“去锐化”是美摄的软焦（`Soft Focus Enable` / `Radius` / `Thresh` / `Opacity`）。原库把软焦列为独立效果（id 9），与滤镜同时存在时成片走“拷贝、RGBA 效果链、再拷回”，CCD 会变色、INST 会丢鱼眼。`tools/algo/soft_focus.S` 按 ROM 17.0.1.202 的做法改成单趟：`DoProcessEffectsWithHarewareBuffer` 入口把“滤镜 id + 9”收成单个滤镜 id；CCD 与 Neutral CCD 在拷进 0.7 倍帧后原位模糊（`soft_blur_slot`）；Cinestill 800T、Portra 400、Polaroid 在软焦下改走复合渲染，复合渲染前先 `soft_process`（拷贝为 RGBA 再 `CNvSurfaceBlur::RenderEffect`）并以非 YUV 输入调用 `CNvCompoundLut::RenderEffectYuvGps`。只有软焦没有滤镜时仍走原来的单独模糊。Polaroid 的模糊参数不写采集标志（写了鱼眼画面会翻转）。预览走的 QSB 路径未改。

POP 的流光胶片（美摄 LightFlow）在成片着色器里按横向画布摆三团漏光，翻转标志 `flipLighX`、`flipLighY` 也是按横向定义的，输入是竖图时要靠 `LightFlow Input Vertical` 旋转 90°，但整套应用从不设置这个参数。Ultra HDR 下，`APSRefFrameProcessor` 因算法清单里有 `HDR_TRANSFORM`，让快速图先转成竖向（HybridRawLite 的 1280×960 变成 960×1280）再跑滤镜，成片仍在横向画布上滤镜后才转正，两者漏光相差 90°。`tools/algo/lightflow_vertical.S` 的三个钩子（`0x78d830`、`0x78d898`、`0x78daa4`，都在 `RenderEffectYuvGps` 的 uniform 上传里）在画布高大于宽时置竖向标志并互换两个翻转标志，漏光就落在与成片一致的位置。SDR 风格没有 `HDR_TRANSFORM`，快速图保持横向，不受影响。

成片着色器里流光的屏幕混合 `1 − (1 − 底图) × (1 − 漏光)` 只在底图不超过 1 时成立。Ultra HDR 的底图在高光处大于 1，`(1 − 底图)` 变负，漏光里大于 1 的通道（第三层红色乘了 2.2）会把该通道压到零，亮背景上就出现蓝青色。补丁在 `isSDR == 0` 时让这一步与同一份着色器里的贴图层混合一致：先 `toneMapFwd(…, 1/2.5, 0.75, true)`，混合后再 `toneMapBwd`；SDR 路径不变（`patch_yuv_gps_glsl`）。

不要对这个库下 uprobe：断点会让相机进程崩溃。

## 移轴模式

相机里完整的移轴模式出厂关闭，引擎算法 `aps_algo_tilt_shift`、GL 预览库和 `libCaptureBokeh.so` 的 `TiltShiftBokeh` 都在，缺的是配置，四层各需一处：

1. 特性标志 `com.oplus.feature.tilt.shift.photo.support`：HAL 侧写在 `payload/oplus_camera_config`（任何进程启动即可见），应用侧由 `service.sh` 写进相机 `override_config_data.xml`。
2. 模式列表数据库 `mode_data.db`：按特性标志建库，`service.sh` 开机检查库里有没有 `tiltShift` 行，没有就删库一次（`$MODDIR/tilt_db_reset` 标记保证一次）。不能靠抬高 `com.oplus.camera.mode.data.db.version`：HAL 里它是 Byte（最大 127），且重启后系统会在覆盖配置加载前先起相机进程，对 203 的库做“降级”重建。所以版本保持 102。
3. 镜头支持：`camera_unit_config` 把 `tilt_shift_mode`（操作模式 8001）加进五个后置镜头的 `mode_type_list`，并按 `photo_mode` 复制 `capture_stream_number`。
4. 界面特性：`camera_unit_feature_config.protobuf` 追加特性 `com.oplus.camera.feature.tilt.shift`，新增 `tilt_shift_mode` 条目（复制 `photo_mode`，`TiltShiftPhotoMode` 继承 `PhotoMode`），并在 `common` 与各后置镜头组追加该特性；前置不加。

成片模糊：拍照时引擎用预览决策算出的算法清单（`mApsAlgoFlags`），应用自己拼的清单只在没有决策结果时才用，而决策配置没有移轴节点，`aps_algo_tilt_shift` 永远进不了拍照清单（往决策 JSON 加节点无效，解析器跳过不认识的名字）。`hook/tilt/TiltShiftCapture` 钩 `ApsProcessor.createMetaItemInfo`，在 `mbTiltShiftOpen && mTiltShiftBlurValue > 0` 时把它插到 `aps_algo_rotate_mirror` 之前。

移轴成片是 SDR，走普通 MFNR（自带移轴的机型也是这样：移轴被排出 TurboHDR 与 HybridRaw，落到 ISP 渲染的 NV12 单帧链）。`tiltShiftProcess` 只接受 `NV21` 和 `YUV_420_888`，所以 `tiltShift` 不在 `capture.hdr.cap.mode.value` 名单里。HybridRaw 在 SDR 下交出的是面向 HDR 的 10-bit P010，而 SDR 路径没有 `hdr_transform` 把它转回 SDR，成片会偏亮；MFNR 没有这个中间量。

- 决策：入口 `PREREQUISITES_TILT_SHIFT_MFNR` 紧跟 `PREREQUISITES_PRIVACY_NOLINT`，条件为 `REAR_NORMAL` 且 `paramsHolder->tiltshift_open` 非 0，子链 `ALGO_MFNR`（NV12，算法清单空）。`SINGLE_ALGO_BASIC_TONE` 的移轴分支已去掉，移轴成片不过 BasicTone。四份决策配置（`payload`、`unlock25mp`、`face200m`、`face200m/unlock25mp`）都有，由 `tools/patch_tilt_mfnr.py` 写入。
- 用例：这台机器的相机配置对 8 位拍照选 `sat_photo_none_yuv_case`（`PhotoMode.getSurfaceUseCase`：设备配置 `KEY_QCOM_NONE_YUV_CASE_SUPPORT` 开且未开 10 位拍照时），它没有 `capture_yuv` 流，MFNR 的 NV12 请求在 HAL 里没有链接，`OemLayer::NodeBase::ForwardDeterminConfigInfo` 中止，`vendor.qti.camera.provider-service` 崩溃。`hook/tilt/TiltShiftCapture` 钩 `PhotoMode.getSurfaceUseCase`，只对 `TiltShiftPhotoMode` 把 `sat_photo_none_yuv_case` 与 `sat_photo_none_yuv_full_size_case` 换成 `sat_photo_case` 与 `sat_photo_full_size_case`（带 `capture_yuv`，与自带移轴的机型一致）。方法由 `PhotoMode` 声明，所以钩 `PhotoMode` 并按实例类型过滤。
- 决策文件的读取：开着「放宽 25MP」或「哈苏超清 200MP 解除人脸门禁」时，相机读的是 `files/jiege` 下的副本（`face200m.cpp` 重定向），不是 `/odm` 那份。

## POP 走 Ultra HDR 与前置 BasicTone

- 应用只对 `com.oplus.camera.capture.hdr.cap.mode.value` 名单里的模式发 `ULTRA_HDR_ENABLE=on`（`HdrAllRoutePresenter.B2` 优先读它）。`service.sh` 把 `retroCamera` 加进成片名单；预览名单 `preview.hdr.cap.mode.value` 不动。POP 成片因此带增益图（JPEG 里有 `hdrgm` 与 MPF 标记）。
- 前置 POP 滑杆：`common` 前置项（id 1）的 `aps_algo_basic_tone` 为 1；Ultra HDR 下前置流水线本来就加 BasicTone 节点，SDR 下靠上面第 3 处的前置 NOP。

## POP 的 SDR 风格

设置「POP 使用 SDR 风格」（`pop_sdr_style`，默认关）。成片是 SDR 还是 Ultra HDR 只取决于 `retroCamera` 在不在 `capture.hdr.cap.mode.value` 名单里；`hook/pop/PopSdrStyle` 钩 `CameraConfig.list`（`CameraConfig.u(String)`），开关打开时对该键返回去掉 `retroCamera` 的新列表（原列表是定长的）。名单在每次建会话时读取，切模式或切镜头后生效。模块侧的名单不变，SDR 路径需要的引擎补丁（上面的 NOP、决策里的 POP 分支）都已在。

## 前置 POP 拍完点缩略图闪退

Ultra HDR 拍摄后走“无缝 UI”路径：`PreviewRenderer.setSeamlessGaussianBlurBitmap` 在预览缩放状态下调 `BitmapUtil.cropCenterBitmapByScale`；缩放值小于 1（前置广角）时裁剪框大于位图，方法返回 null，调用方不检查就包成纹理，下一帧 GL 线程空指针，相机关闭。另外三处调用都判断了“缩放值 ≥ 1”。`hook/seamless/SeamlessBlurGuard` 在返回 null 时改返回原位图；目标方法由 `symbols.json` 的 `BitmapUtil.cropCenterByScale` 按错误日志字符串 `cropCenterBitmapByScale, error, sw: ` 定位。这是竞态，不是每次都崩。

## 注入状态与日志导出

PMA110 的 HDRTransform 输入虽然称为 P010，实拍转储中的 10 位值放在 uint16 的低位；离线读取不能直接套用标准 P010 的右移 6 位处理。

日志页的“成片转储”提供 RAW、ccmVec、HDRTransform 三个独立开关，默认关闭；更改后重启相机进程生效。文件保存在 `/sdcard/Android/data/com.oplus.camera/files/muse/dumps/`，按阶段与时间分目录，每组附带 `info.json`：`raw/` 保存 HybridRaw 的 PickFrame 读取的逐帧传感器缓冲及其打包格式、行跨度、黑电平；`ccm/` 在同帧输入上运行原厂 ProProc 的白平衡与 CCM 前缀，将 gamma 之前的 `ccmVec` 写为 RGB 交错、小端 float32，不改变原厂输出；`hdrtransform/` 保存大师主图和快图的输入 P010 与输出 NV21。读取 YUV 时按元数据中的行跨度、slice height 和有效宽高去掉对齐区域。三类全开时，5000 万像素拍摄一次约占 1.08 GB，并增加拍摄处理时间。

相机看不到设置应用（包可见性），所以由应用来问：`call(com.oplus.camera.entry, "jiege.diagnostics")` 落到相机导出的 `EntryProvider.call`，钩子（`hook/common/Diagnostics.java`）只对模块 uid 返回状态：符号解析数、缺失数、错误行数、进程启动时间、加载钩子的模块 APK、最近 600 行模块日志，以及相机的大师滤镜原厂列表（应用存进 `MasterFilterStore`）。调用会在相机未运行时拉起相机进程。`Log.e` 计入错误数。

主页状态（`ui/Diagnostics.kt`）：LSPosed（xposedsharedprefs 可用）、KSU 模块（`/odm/lib64/libAlgoInterface.so` 与 `/odm/lib64` 不在同一设备，即绑定挂载生效）、相机注入、符号匹配、功能加载。全部正常时状态卡为绿色；相机仍跑着旧模块 APK 时为橙色，提示重启相机；其余异常为红色。“重启相机”“重启相册”用 `su -c am force-stop` 后重新打开，没有 Root 授权时打开应用信息页。主页的“日志”是二级页：“开启日志”用 Root 起一个 logcat 写入应用缓存（两个 8 MiB 轮转文件），关闭时停止并保留；“导出日志”写一个 zip：概要、相机状态与日志、模块设置（授权码只标记已保存）、大师滤镜配置、应用自身 logcat，有 Root 时加 LSPosed 模块日志、logcat 与记录下的日志。

## 理光 GR 快门声

GR 模式向相机的播放器请求自己的音效（14 快门，15/16 长曝光开始/结束，raw `camera_gr_shutter*`），PMA110 的相机里这三个文件是 0 字节，加载永远不完成，请求一直挂在“加载完再播”的队列里。`hook/gr/GrShutterSound.java` 在 `SoundPool.load(Context, resId, …)` 遇到这三个空资源时，改为加载模块里 realme 相机（RMX5200 16.0.10.501，`com.oplus.camera` 6.070.215）的原版音效 `assets/gr_sounds/*.ogg`（首次复制到相机缓存）。相机的快门声设置、情景模式和本模块的快门声功能照常生效。

## 快启键指定模式

快启键以 `android.media.action.STILL_IMAGE_CAMERA`、`android_camera_launch_type=spruce_double_tap_launch_from_framework`（并带 `android_spruce_key_launch_type`）经 `CameraImageActivity` 拉起 `Camera`。`CameraEntry` 会打开 intent 整数 `mode` 经 `CameraInfoCursor` 映射出的模式，但 PMA110 的映射表里没有大师（`professional`）和 GR。`hook/launch/KeyLaunchMode.java` 给快启键的 intent 放一个表里没有的 `mode`（9000），映射时换成设置里选的模式名（`settings/LaunchModes.java`，模式名取自相机 `mode_data.db`）。

相机把当前模式记在 `pref_camera_mode_key`，下次启动打开它。快启键启动时先记下原记忆模式，这次会话停止（`Camera.onStop`）时若仍停在快启键的模式，就写回原记忆；会话中手动换过的模式照常保留。`pref_camera_mode_key` 的 DataKey 在第一次用到时才解析，钩子安装时解析会让相机卡死。

## 柔光：HDF、黑柔与白柔

三档柔光都由 `libmsnativefilter.so` 的 ProMist 在 HLG 帧上合成：预览走 `MsRenderHDRTexture`（RGBA8 HLG 纹理，散射写入暂存纹理作为滤镜输入），成片走 `MsRenderHardwareBuffer`（P010 硬件缓冲，先 1280×960 缩略图，后台再处理全尺寸）。`SMsEffectParam` +0x48 是柔光开关、+0x4c 是类型：弱 = 1、中 = 0、强 = 2。

替换在“拍摄 → 实验性功能 → 物理柔光”二级页里按挡开关（`soft_light_weak_hdf`、`soft_light_medium_black_mist`、`soft_light_strong_white_mist`，默认关），相机打开（变焦条创建）时以位掩码读入原生层，未开的挡走原厂。弱档为 HDF、中档为 1/4 黑柔、强档为白柔：`soft_light.cpp` 在调用期间关掉原厂 ProMist，先对滤镜的输入帧（大师 LMT 输出）做光学散射，再让美摄在散射后的帧上渲染滤镜（影调、调色盘、胶片），光晕因此在任何滤镜之前由场景光生成：按大师管线的中性色调曲线（内置 gamma 表加六张原厂 LMT 灰轴的平均，逐通道；`soft_light_curve.h`，由 `tools/softlight/gen_curve.py` 生成）把 HLG 信号反解回场景光（18% 灰 = 0.18，传感器削顶约灰上 5.9 档），13 抽头降采样金字塔与逐级帐篷上采样链，每级每通道的权重来自物理光学模拟的蒸馏（`soft_light_weights.h`，由 `tools/softlight/gen_weights.py` 从工作区 `实验\柔光替换\pyramid_weights.json` 生成），HDF 散射约 20%（皮升墨滴，覆盖率 10%，集中在 0.6° 以内；HDF 属于 GR 的 28 mm 镜头，光晕按 28 mm 视场计算、不随变焦变化，黑柔与白柔随当前焦距）、黑柔约 15%（多在 0.3–1.3° 的光晕里，另吸收 18%，按透过率补回）、白柔约 26%（七成在 3° 以外，成乳白雾），Hybrid Raw 里已削顶的光源比记录值亮（RAWMax 台灯按削顶最少的通道至少 1.8 倍），HDF 按 2.6 倍真实亮度处理：接近削顶的像素送入金字塔前按峰值通道放大（削顶下 0.5 档起线性升到 2.6），周围约 0.1° 的平均亮度在削顶下 0.3 档以内时直透光也放大到 2.6 倍（按邻域均值判断，纹理不被拉开），压肩把光源压成一片白；光晕由光源的真实亮度撑起，散射比例因此只需约 20%，画面其余部分不发雾。黑柔与白柔的削顶像素按记录值散射：大师帧里被照亮的白色表面也会削顶，经宽光晕放大会像光源一样发光。超出传感器削顶的光按峰值通道软压肩，再按同一条曲线编码回 HLG。预览在美摄的 GL 上下文里跑，保存并恢复 GL 状态；成片在 GPU 上算同一套金字塔：模块自己建一个 EGL pbuffer 上下文（调用线程原来的上下文用完即还，互斥串行），输入 P010 或 NV21 平面作为整数纹理上传，着色器按像素算编码值的变化量（色度按 2×2 平均，抖动与 CPU 版用同一个整数哈希），打包成与平面字节一致的 RGBA8 后一次 `glReadPixels` 写回副本，未散射处保持原值，副本交给滤镜渲染。大师模式的 P010 硬件缓冲先被 CPU 锁定、原位处理，GR 的主机缓冲处理其私有副本。没有 EGL 上下文、纹理超过 `GL_MAX_TEXTURE_SIZE`、行距不是 4 的倍数、宽度不满足 4 字节对齐（P010 为 2 的倍数，NV21 为 4 的倍数）、着色器编译失败、出现 GL 错误，或系统属性 `debug.muse.softlight_cpu` 为 1 时，改走 CPU 版（`std::thread` 多线程，同一套金字塔）。

“关闭对比度增强”（`soft_light_no_local_contrast`，默认关闭）在大师模式使用已替换的柔光挡位时，让 HDRTransform 的 `TmcDownTransEF::odtFusionPro` 直接复制全局影调结果，省去局部细节融合；其余模式保持原厂路径。开关在重启相机后生效。

光晕是镜头前的角度，在画面里随焦距缩放：Java 侧（`softlight/SoftLight.java`）在变焦条创建时报 1x、`Zoom.setZoom` 时报当前变焦对应的等效焦距（`common/ZoomFocal.java`，按相机配置的变焦档位插值），原生侧按 log₂(焦距像素数) 在权重表里插值；超出表尾时金字塔基准每倍焦距上移一级。

理光模式用原厂柔光：下拉面板的“柔光”项（`pref_soft_light_menu_switch`）由 `GrModeHooks` 打开，列表（`SoftLight.typesForMode` 按大师模式取）、拍摄请求（`StockSoftLight.installCapture` 补开 `SOFT_LIGHT_OPEN`）和原生侧的大师标记（`SoftLight` 对 `gr` 同样置位）都接上，物理柔光的挡位开关与大师模式共用。弹出的柔光条走滤镜菜单的 `showFilterPanel`，原厂对 `gr` 在该方法里提前返回，容器已在屏幕上却没有走显示流程：`n` 标志不置位，柔光条的触摸监听吞掉所有触摸；面板管理器不计入展开，点空白处不会撤销。柔光模式下 `StockSoftLight.installPanel` 把该方法的模式参数换成 `common` 再放行。顶栏的橙色柔光图标点击只写 `pref_soft_light_seek_bar` 为 off，靠美颜功能的监听打开柔光条，理光没有美颜功能，所以 `GrSoftLightEntry` 把这次写入当作信号，经滤镜 presenter 打开柔光条（`w4(true)`），再按 `ExpandedFilterMenu` 的约定登记为展开（隐藏下方控件、点预览撤销），柔光条的容器淡出时收回。成片走 V1 滤镜节点（`gr_basictone.cpp` 把理光帧转给 `doFilterProcess`），它用主机缓冲渲染：10 位帧走 `MsRenderHDRHostBuffer`（P010，声明 BT.2020 加 HLG、矩阵 BT.601、全范围），8 位帧走 `MsRender`（NV21），CCD 滤镜走 `MsRenderHostBuffer`；元素布局（0xd0 字节）：+0x00/+0x08 两个平面指针，+0x20/+0x24 行距（字节），+0x30/+0x34 宽高，+0x38 类型（1 为 NV21，5 为 P010），+0x44 矩阵，+0x48 范围。`soft_light.cpp` 在这三个入口对输入帧的私有副本做同一套散射再放行，帧编码与大师一致，NV21 的码值放大到 10 位单位后进同一份算法。

## 大师调节栏的颗粒

调节栏在暗角前多一项“颗粒”，条目显示当前强度。点按弹出“强度”“大小”两根并排收起的半尺，照专业模式白平衡的色温／色调双尺（`WbBar`、`ToneBar`，监听 `ScaleBarListener` 用 `Proxy` 实现）：按下哪根展开哪根，另一根与其标题淡出，收起时复原；再点“颗粒”、点其他项或关闭调节栏时隐藏。刻度为参数 ×100，0–100。`hook/masterfilter/MasterGrain` 在菜单构造容器视图前把条目插进条目表；尺与标题加在原厂调节尺的父容器里，复制其布局参数。

默认值照原厂：Portra 400、800T 与三款富士大师 36／70（800T 开霓虹时 36／42），黑白 HC 40／47，正片 25／47，其他滤镜 0／70。强度与大小是模块效果值（`masterlut/ModuleEffects`，见“大师 LUT 槽与对比度”），按所选预设或滤镜保存，-1 为未设置，随预设另存与保存修改。条目自身的值只表示相对基准是否有改动（`EffectMenu.modified` 之前同步），原厂按预设存值的回调对本项拦截；调节栏重置把所选预设的值放回基准。

成片：大师模式最后一个颗粒设置函数（`setPositiveFilterGrain`）之后，当前滤镜设置过才替换原厂颗粒量与大小；原厂无颗粒的滤镜强度大于 0 时填大师彩色胶片那组参数，颗粒量以负数下发。

`OCamFilterMeisheV2::setMesheFilterParam` 只给按名字认识的滤镜设颗粒：`800t.bin`、`fuji-nc.bin`、`fuji_cc.bin`、`fuji-proNegHi.bin`、`ccd_base_cold/warm.bin`，前缀 `portra400`，`800t_master.bin` 与三个富士大师文件，前缀 `blackandwhite_HC_` 或 `positive`。各分支在 `0x1ad02a4` 汇合；`tools/patch_grain_gate.py` 把这里改成跳到代码段尾 `0x2902ea0` 的跳板：颗粒量为负时取反，补上 `x22 = sp + 0x130`，进入黑白／正片的颗粒块 `0x1acfab4`（从 metadata 读全部颗粒参数）。原厂颗粒量都为正，不受影响。

标准不下发滤镜参数，拍照清单也没有滤镜节点（预览决策里被去掉）。强度大于 0 时，`ApsProcessor.createMetaItemInfo` 之后给大师模式的拍照 metadata 补上空的 `filter_type` 和同一组颗粒参数，并把 `aps_algo_filter` 插到应用自建清单里它之后的节点（眼部增强、超级文本、星芒、鱼眼、旋转镜像、移轴、水印）之前。`doFilterProcessV2` 在类型为空且黑柔、星光、复古都关时跳过节点（`0x1ad2e64`）；第二个跳板（`0x2902ec4`）照该函数读 `retro_filter_intensity` 的写法读 `filter_grain_amount`，为负才进入节点，节点不加载 LUT。

## MonoPan50 滤镜

大师滤镜“徕卡风格”里的 MONOPAN 50 是小米 M3 黑白的复刻（代码里叫 MonoPan50），滤镜本身只是入口（`JG_MONOPAN50.cube.rgb.bin`，恒等 LUT，`tools/bake_master_luts.py` 生成），效果在引擎里，预览与成片分别处理：

- 转灰（预览与成片共用）：`master_lut_slots.cpp` 的 Post 槽阶段。LMT 输出沿大师中性曲线（`soft_light_curve.h`）反解回场景光，按 .299/.587/.114 取灰，再沿同一条曲线写回灰色 HLG 信号，不改影调。
- 影调、暗角、颗粒（预览与成片）：`MsRenderHardwareBuffer` 钩子（`soft_light.cpp`）在滤镜节点输出的 P010 上调用 `monopan_grain.cpp`，全程浮点：灰色 HLG 码加 ±0.5 码抖动，经大师中性曲线回到场景光，过 M3 的 Gamma1774（DRC 1 叶子，18% 灰 → 显示码 0.512；表由 `tools/monopan/gen_m3_tone.py` 对原厂 10 位整数叶子做 Savitzky-Golay 平滑后生成 `monopan_tone.h`，数据 `tools/monopan/data/m3_gamma1774.json`、`tools/mastercolor/data/x9u_master_tonecurve.npy`）。
  - 暗角：`libM9PhotoFilter.so` 里 CvStyleEffect 的 `colorDark` 着色器原样移植，八个参数按场景 lux 与变焦从原厂 `leica_filter_param_m3.bin` 查表；lux 取自帧元数据，变焦取自焦距 / 23 mm；x 的宽高比换算改按长边，不依赖缓冲存储方向。大师调节栏的“暗角”开关决定它开不开：`getVigParams`（`gr_basictone.cpp`）读出原厂开关值交给 `monoPanSetVignette`，原厂自己的成片暗角仍清零；预览不单独读开关，沿用最近一次成片报告的值。
  - 颗粒：8 位亮度交给颗粒辅助进程，颗粒相对每个 8 位档位的均值先去掉，再按未量化的显示值补回平滑后的色调偏移，变化量加在暗角后的浮点值上；最后经 `kM3HlgOfDisplay` 写回 HLG，取整前再加 ±0.5 码抖动。调节栏的“颗粒”条目与其“强度”“大小”两根尺在选中该滤镜时隐藏，大师颗粒下发也跳过它。
- 颗粒辅助进程（`monopan_helper.cpp`，CMake 直接编进 `payload/monopan`）：原厂 `libmialgo_monopan.so` 不能在相机进程里跑——它自带 OpenCL 运行时，在相机进程里建不出图像缓冲（`MonopanInit` 把查找表写进空缓冲而崩溃，同一个库在独立进程里完整运行）；库还带自解密外壳，加载时按自己的文件名在 `/proc/<pid>/maps` 里找基址，所以只能由真实文件路径加载（APK 内映射、数据目录映射都不行）。厂商库按名字加载系统 OpenCL，系统链接器只许 `/data/adb` 之外的程序这样做，所以 `service.sh` 开机把进程与库复制到 `/data/local/tmp/monopan`，用 root 从那里起（库与 OpenCL 资产来自 `payload/monopan`，NDK C++ 运行库与 NEEDED 项改名为 `libmpancxx_sh.so`）；相机通过 `files/jiege/monopan/job` 与它交换：`in.y`（8 位亮度）、`req`（宽高，最后写）、`out.y`、`done`（`ok` / `fail`）。辅助进程没有运行或失败时成片只做暗角。
- MONOPAN 50 迫冲（`JG_MONOPAN50PRO.cube.rgb.bin`，同样是恒等 LUT）：影调与暗角同 MONOPAN 50，颗粒换成小米相册 M3 编辑的配置。相册入口 `LegendRefineProcess` 只是 Monopan 的外壳：它的初始化配置从偏移 0x08 起就是 Monopan 的配置，同一份配置经 17U 直出版库给出的灰阶统计与相册库一致，所以辅助进程直接按请求换配置，不带 `liblegendrefine.so`。配置是 density 4、颗粒大小 1.8、mix 0.3、核 7、sigma 0.5、mode 1 与相册的 32 点曲线，效果比直出更粗更重，并带 S 形影调（128 级附近压暗约 24 码，高光抬高约 7 码）。`req` 里的第三个字段是变体（1 MONOPAN 50，2 迫冲）。依据与对照数据见工作区 `研究\文档\M3相册后处理
otes.md`。
- 选中状态（`hook/masterfilter/MonoPan50.java`）：进入大师、预设或滤镜切换、成片请求的滤镜类型三处同步槽参数 `MONOPAN`（0 无、1 MONOPAN 50、2 迫冲）。两个滤镜都隐藏调节栏的“颗粒”项，暗角开关控制 M3 暗角。两个滤镜只在大师模式出现（`FilterEntry.masterOnly`）：拍照与人像的“同步大师滤镜列表”（`NormalMasterMirror`）和相册编辑器的滤镜列表（`GalleryFilters`）都跳过它们。

## 大师与理光的 Pro 挡位

大师的 Auto／Pro 挡位是一个布尔设置（`key_master_mode_auto_option`，true 为 Auto，持久在相机 0 的偏好里），理光继承 ProMode，两个模式读写同一个值。`modestate/ModeStateSplit` 在 `ModeSwitch.switch` 前把当前值以文本存到离开模式的键（`..._jiege_<模式>`），并放回进入模式存过的值；第一次进入的模式沿用当前值。该拆分常开，与闪光灯、实况照片按模式分开用同一套机制。相机进入模式时还有自己的“要不要自动打开实况”的判断（`LivePhoto.autoOn`，按相机内存里一份“用户关闭”状态决定，不看模式存的值），模式存的是关、共享值也已放回关时，这个判断会又把它打开，所以该模式存的是关时跳过它。

大师与理光的 Pro 参数（白平衡、色调、ISO、曝光补偿、快门、对焦及其手动标志）也按模式分开：`modestate/ProParamsSplit` 在 `ModeSwitch.switch` 前把离开模式的参数存成一段文本（`pref_jiege_pro_params_mode_<模式>`），并放回进入模式存过的参数，没有记录的模式从全自动开始；预览准备完成后刷新参数栏显示。读写参数的键由 `common/ProParamStore` 统一提供，与专业预设共用。大师与理光互切时，相机在切换开始阶段对 Pro 参数栏做淡出隐藏，切换结束阶段再按进入模式的挡位显示；两个模式互切很快，淡出动画尚未结束时结束阶段已显示参数栏，随后淡出把它置为不可见，所以 `gr/GrModeHooks` 在这对模式之间跳过开始阶段的隐藏。

大师 Pro 挡位的滤镜面板菜单提供“记忆专业参数”和“另存专业预设”。前者将 ISO、快门、白平衡、色调、曝光补偿和对焦保存到当前滤镜或预设；后者将这些参数与当前滤镜效果保存为新预设。选择条目时恢复其专业参数；“专业预设展开Pro面板”控制是否切换 Pro 挡位，默认关闭。“在滤镜名称卡片进行提示”默认开启，为有专业参数记忆的条目显示 Pro Preset。已有专业参数记录的槽位显示“清除专业参数”，可移除该槽位的记录；当前参数与记录一致时，“记忆专业参数”置灰。参数记录随相机数据保存，删除自定义预设时清除对应记录。

## 大师 LUT 槽与对比度

原厂大师 BasicTone 每帧把风格 LMT、SC（影调 Cont、饱和度 Sat）、CWCM（冷暖 CoWa、青品 CyMa）合成一张表：`CWCM(SC(LMT(x)))`，成片之后再接 ODT（开启时）与暗角，预览无 ODT。模块加两个槽，合成变为 `Post(CWCM(SC(Pre(LMT(x)))))`：Pre 放塑形类（对比度，以后的高光／暗部亮度），Post 放着色类（彩色 Fx 蓝，以后的分离色调、片基），恒等时不改任何调用。各槽是有序的阶段列表，阶段直接作用在原厂表的格点值上。

- native：`app/src/main/cpp/master_lut_slots.cpp`。成片挂 `libBasicTonePhotoX9.so` 的 `BasicTone::MergeLutTet_resize` 与 `BasicTone_OGL::load3DLut<half>`，按源表是否为 LMT（`this+0xd48`）、查找表是 SC∘CWCM（`+0xd58`）还是 ODT（`+0xd50`）分情况套 Pre／Post；只作用于大师帧（`basicToneMasterFrame()`，由 `gr_basictone.cpp` 的 processCore 钩子按帧标记）。预览挂 `libCombineLut.so` 的 `MergeLutTet_resize2rgb`（四线程分段）；原厂四项调节全为 0 时不调用合成，由 Java 侧对返回表套 `Post(Pre())`。
- Java：`hook/masterlut/MasterLutSlots.java` 传参数、按 `Lut3d.load` 的模式名标记大师预览、参数变化时同步调用 `Lut3d.update` 重算预览表（与原厂调节一致，拖动实时生效）。
- 对比度：`hook/masterlut/MasterContrast.java`。条目插在影调之后，用原厂影调图标；影调改用理光影调图标 `gr_mode_effect_tone_icon`。量程 −100..100（`EffectMenu.bipolar`），数值按所选预设或滤镜独立保存：原厂照影调的方式，在 `key_master_mode_current_preset_key` 所指的作用域下写入、恢复和显示条目键 `key_master_mode_effect_muse_contrast`，模块只负责生效（改动、切滤镜后原厂恢复的分发、进入大师、预设变更时读回），重置清零所选预设的值。效果为理光对比度：理光 SC 表 Sat=0 一列 Lut3／Lut4／Lut5，按 `fuseUtilLut` 的方式在节点间线性插值。
- 模块效果值：`masterlut/ModuleEffects` 把对比度与颗粒的强度、大小放进原厂的预设数据模型。值属于所选预设或滤镜（`key_master_mode_current_preset_key`，键名后缀为该作用域），自定义预设 `custom_N` 另有基准 `custom_base_N`，值与基准不同即为已修改；原厂的“已修改”判断与预设记录相等判断（另存为新预设、保存修改据此判定）只看自己的一组固定值，这里把模块的值并进去；另存为新预设时值移到新预设及其基准、源预设回到基准，保存修改时基准取当前值，条目重置回基准。
- 彩色 Fx 蓝：`app/src/main/cpp/master_fx_blue.h`，Post 槽，参数 `MasterLutSlots.Param.FX_BLUE` 0..100（富士弱／强约 50／100）。在 Oklab 里给饱和的蓝加密度：L × (1 − 0.15w)、C × (1 + 0.40w)，色相不变；权重 w = 色相（228°–270° 满，青侧到 190°、紫侧到 295° 余弦归零）× 饱和度 C/L（0.02→0.10，再陡会把色度噪声放大成明暗斑驳）× 明度（L 0.05→0.30）；C/L 0.15→0.35 时彩度增益退掉，已很鲜艳的蓝只加密度；出 P3 色域时在新明度上二分收彩度。离线测试 `tools/offline_tests/run_native.ps1`（主机 MSVC）。

## 快速切换滤镜面板

大师模式的右下入口（单击打开预设卡片）和带调色盘的模式的右下入口（单击打开调色盘），按设置“拉起快速切换滤镜面板”（`filter_panel_trigger`：无 / 上滑（默认）/ 长按）弹出原厂旧滤镜条，即移轴模式单击打开的 `effects_menu_panel`；单击不变。`hook/filterpanel/LegacyFilterPanel` 在 `MenuRightButton`（`camera_menu_right_enter_button`）上挂长按与触摸监听。上滑沿手机长边朝顶端（按屏幕旋转换算，横屏也一样），超过 24 dp 打开，并给按钮补发取消，单击与长按都不再触发。

列表：卡片和调色盘页是 `FilterPresenter` 同一套“风格页”（`styleMode`）。大师模式是预设列表（自定义预设在前，之后每个可见滤镜一个内置预设，`CameraFilterPresets`），调色盘是调色盘的滤镜。打开期间置调色盘列表标志（`FilterPresenter.paletteList`）；条目在 `FilterHelper.setImageResourceHorizontal` 处替换：大师模式按预设页构造，自定义预设显示预设名；名称同卡片：目录滤镜用目录标题，“无”显示为“标准”。滤镜条渲染器只在当前项变化时重画名称纹理（`FilterNameMarquee.updateMarqueeParams`），每次构建前把 `mCurFilterIndex` 置 -1。选中按卡片滑动的原厂顺序套用（`EffectStylePageIndicator` 消息 3：`clampStyle`、`beginPresetSwitch`、`storeStyle`、`applyFilter(styleFilter)`、`syncPalette`、`markPresetSwitchReady`、`StylePager.sync`），各页带着原位参数、自定义预设参数、调色盘与柔光记忆；滑动中同一帧的多次选中只套用停下的那一页。

打开：
- 有美颜功能的模式（拍照等）走移轴入口的原厂路径 `FaceBeautyUIManager.t(3)` → `FaceBeautyBaseMenu.C` → `BaseMode.C7` → `FilterPresenter.showMenu`，只在 `showMenu` 内让调色盘（`FilterPresenter.palette`）与大师卡片（`com.oplus.feature.effect.style.support`）答否，菜单就落到滤镜条；隐藏下方控件、点空白退出都由美颜菜单负责。美颜菜单对象随支持美颜的模式创建，换模式后可能残留，所以路径按模式选，不按对象是否存在。
- 没有美颜功能的模式（大师、理光）里，滤镜菜单按 `filterpanel/ExpandedFilterMenu` 的约定展开，旧滤镜条与理光的柔光条共用。美颜菜单正是隐藏控件的一方（`t()` 末尾 `CameraUIManager.F9` → `CameraControlUI.Wg(true)`），也是点空白退出的一方（`TouchEventManager.d8` 只在 `EffectPanelUIContainer.isExpanded()` 为真时收起效果面板，而只有美颜菜单答真；滤镜菜单既不答也不处理 `w8`）。这里滤镜菜单直接显示滤镜条，效果面板展开 `pref_filter_menu`（`EffectPanelUi.expand`：收起模式栏与入口按钮），记下快门、缩略图、切换镜头、侧快门的可见性后调 `Wg(true)`。打开期间效果面板 `isExpanded()` 答真，收起消息（`EffectPanelBase.revoke`）让滤镜菜单 `hideAnimated(true)`。
- 大师预览流是套大师色彩之前的 HDR 帧，缩略图会发灰，美摄菜单 SDK 还会对每一项走 HDR 路径。照原厂对 XPAN 的处理，打开期间新建的 `GLModelParam` 设 `setNeedBasicToneConvert(true)`，先用本模式的基础色调 LUT 转成 2D 纹理。

绘制窗口：美摄菜单 SDK 每帧把所有条目画一遍，52 项时大师预览从 30 fps 降到约 20 fps（拍照模式约 26 fps，移轴原生面板不降）。渲染器可以只画一个下标区间（`MeisheRender.drawFiltersFrame` 的 start / end，-1, -1 为全部），SDK 自己的窗口（`mFilterItemDrawCount`，配置 `com.oplus.filter.item.draw.count`，本机未配）按 `rect.top` 找中心，而滤镜条沿 x 排列、`top` 相同，窗口会停在开头几项，不能用。打开期间按滤镜条滚动位置（SDK 绘制收到的 `mCurrPosition`，经 `FilterStrip.indexAt`）取居中项前后各 4 项作为区间。列表本身不动：渲染器按列表缓存每项资源、取第一项的圆角，内容一变就整体重建。

结束：滤镜条容器进入 `FADING_OUT` / `HIDDEN`（`BottomEffectContainer.setVisibilityState`，覆盖所有隐藏路径），或相机自己显示菜单（面板开着时单击入口打开卡片或调色盘）。恢复调色盘列表标志；大师模式再把原来可见的控件经各自方法带 150 ms 动画放回（`ControlUi.mainShutter`、`thumbnail`、`switchCamera`、`sideShutter`、`sideShutterEnabled`），效果面板收起（`EffectPanelUi.collapse`，模式栏回来）。

## 快捷滑条（EV）

大师与理光的全屏取景里，滤镜入口按钮上方有一把竖直的刻度尺，调当前挡位的曝光补偿（`hook/quickruler`）。刻线是横线，两端渐隐，指针是固定的一根哈苏橙横线，推动的是刻度带；收起时刻线密，手指一放上去（没有单独的展开动作）刻线铺开、显示范围拉长，抬手收拢。数值在上端、“EV”在下端，字号字体读自顶栏“EV 0.0”的数值（`oplus_setting_bar_left` 里的 `tv_second`），文字实心。

- 开关：设置页“拍摄 → 取景 → EV 快捷滑条”（`ModuleSettings.QUICK_EV_RULER`，默认开）；关闭后相机打开时不创建这把尺，改设置会重启相机。
- 显示条件：模式为大师（`professional`）或理光（`gr`），入口按钮完全显示（自身与各级父级透明度乘积大于 0.99），`ExpandedFilterMenu.expanded()` 为假。每帧在 `onPreDraw` 里判断，面板一开始展开的那一帧尺就隐藏，没有过渡。
- 位置：机身自然坐标里，入口按钮正上方居中，不随屏幕旋转改变物理位置，旋转时整把尺随屏幕转，只有数值与“EV”两处文字反向转回正读。原厂在横屏下把入口按钮自身旋转了，`getLocationInWindow` 只带动左上角，所以按钮中心沿各级父级的矩阵逐级换算（`QuickRuler.centerOf`）。理光模式隐藏右入口按钮，位置由左入口按钮推算：左按钮外缘与右按钮外缘左右镜像，右按钮中心 x = 自然宽 − 左按钮左缘 − 按钮边长/2，与大师模式一致。
- 拖动：长按“EV”约 0.45 秒（震动，“EV”变橙）后可自由拖动，松手按中心所在半边吸附到入口按钮那一列或它的镜像列（`OvershootInterpolator`，260 ms）。左右列与高度偏移竖屏、横屏各记一份，存在相机自己的 `jiege_quick_ruler` 偏好里。双击数值回到 0。尺所在区域申请系统返回手势排除。
- 值与相机同步：不自写存储，调用相机自带的不混淆类 `com.oplus.camera.util.AndroidTestAdapter`（用 `Activity` 与 `CameraManager` 实例化）。Auto 挡（`key_master_mode_auto_option` 为真）：`getSupportedGlobalEvList` 取选项表（`-3.0` … `+3.0`），`setGlobalEvValue` 写（相机按当前模式名分开存，所以大师与理光数值独立、实时生效），`getCurrentGlobalEvValue` 读；写入后用符号 `TopLeftMenu.updateEv`（`CameraMenuContainerTopLeft` 里按文字更新顶栏 EV 条目的方法）刷新顶栏读数。Pro 挡：`getProfessionalList` 取 EV 选项（整数 −18…18，按 Auto 挡选项表最大值除以最后一个序号换算成 EV，即每档 1/6），`setProfessionalParameter` 写，`getProfessionalExposureCompensation` 读，写入后 `ProParamStore.refreshPanel` 刷新原厂 Pro 条。选项表按“模式 + 挡位”各读一次；尺的刻度按选项序号计，文字用相机的写法（Auto 挡原样，Pro 挡为换算后的 EV），每 `round(1/EV 步长)` 档一根长线。
- 每 150 ms 读一次相机当前值显示在尺上，原厂控件改 EV 时尺跟着变。

## 专业视频 OpenGate

OG 是全幅读出的专业视频（X10 布局）。App 在 OG 下不走多摄（SAT），每颗镜头单独打开物理相机，会话 opmode 为 `0x800B`，开 HDR 或 10bit 时为 `0x830B`。开放超广、主摄、3x 长焦三颗镜头，与原厂电影模式用物理单摄跑视频的三颗相同；10x 超长焦不开 OG。

- UI layer（`payload/og/camera.oemlayer.uilayer.so`，`tools/opengate/uilayer/`）：会话里有 4080×3064 或 4096×3072 视频流时，把选传感器模式用的 caps 键换成私有键 `0x800000`（保留 Dolby 位 `0x2000` 和 60fps 位 `0x0200`）。只有 `post-fs-data.sh` 插入映射行成功（`merged/og_ready`）后，`post-mount.sh` 才挂载它。
- HW 配置（开机时在现行文件上插入，`payload/og/`）：
  - 私有键映射到各镜头与 4K 同读出的全幅模式：主摄 7、超广 3、长焦 3；
  - 三颗镜头的物理单摄 `0x800B` 会话各加一行 SHDR，拿到和 4K 一样的 HDR 合成；
- 调校（`tools/opengate/build_opengate.py` 从上一版 payload 生成）：全幅模式的 usecase 2（实时）和 usecase 0（OG 离线 IPE `OplusOfflineReprocess` 用的是 usecase 0）下，feature1 节点引用 4K 专业视频的 feature1 节点：
  - 主摄 21 / 31；
  - 超广 22 / 23；
  - 长焦 18 / 27。

  只引用真实节点。超广 mode 3 自带 feature1 53/55 节点，先改到该节点下没有任何请求会用的值，再挂引用。
- 菜单（`camera_unit_feature_config.protobuf`）：`rear_main`、`rear_wide`、`rear_tele`、`rear_sat` 四个单元加 `video_size_open_gate`，并带 X10 的画幅规则；OG 时帧率 [60,120] 改为 30。`rear_ultra_tele` 不加。
- 功能列表 `master.video.feature.list` 扩到 17 项：
  - 14：OG；
  - 15：OG 下"增强"防抖改为光学防抖；
  - 16：OG 下的超广。

  第 17 项是 X10 的基础 ISO 表，不开。
- 帧率：X10 的 App 把 OG 当 1080p 判断能否 60fps，全幅模式只有 30fps。`hook/video/OpenGateFps` 让 `VideoMode.isSupport60Fps` 在 OG 下返回 false。
- 镜头：OG 下专业视频走物理单摄会话，它的变焦配置（`MasterVideoMode.zoomConfig`）在机器有超长焦时打开 10x 入口。`hook/video/OpenGateLenses` 在画幅为 `video_size_open_gate` 时关掉这个入口（`ZoomConfig.ultraTele`）。
- 关闭：在模块目录 touch `disable_opengate`。

## 自然模式：落日特调与长焦反差

两项都在“拍照模式自然化”下，由 `hook/natural/NaturalMode` 在 `files/jiege/natural` 里保持文件与设置一致。

- 关闭落日特调（`sunset_off.cpp`，标志文件 `sunset_off`）：
  - `libOPAlgoCamHybridRaw.so` 的 `TmcSunsetDetection::CheckSunset` 以 `isSunset = 网络分数 > 0.6` 结束。在导出函数里找唯一一处 0.6 比较后的 `cset wN, gt`，改为 `mov wN, wzr`，分类器恒判“不是落日”：不选落日 AITM，CvTmc 不进落日模式（≥ 10 倍的预截断、上采样边缘回注、特征统计里的落日开关）。
  - `persist.camera.hybridraw.SunsetEnhance` 读到 `1`（钩 `__system_property_get`），跳过橙黄提饱和。
  - 上层场景识别传入的落日状态（10 倍以上多帧 `teleSunSet`、25 倍以上跳过 LDR 增强）不归分类器管，不受影响。
- 关闭长焦反差增强（tuning json）：`TMCParamsSet.highZoomLceContrastParam`（10–20 / 20–30 / 30–60 / ≥ 60 倍，原厂 1.2 / 1.3 / 1.4 / 1.6）与 `hightZoom200MLceParm` 全部改为 1。
- 关闭超长焦 AI 超分（`gan_off.cpp`，标志文件 `gan_off`）：`HybridRawImplStage2::processCore` 向虚函数 `enhanceDecision` 要第二段路线：1 `jddEnhancerProcess`、2 pro 双线性缩放、3 `ganSDProcess`（GAN 超分，60 倍以上为扩散模型）、4 Stage2 PostSR，其余为 `YUVBicubicResize`。导出函数 `enhanceDecision` 加内联钩子，标志文件存在时把返回的 3 改成 0，整张走双三次缩放（原厂调试属性 `persist.camera.hybridraw.stage2` 落在同一路线）。GAN 与扩散共用路线 3，无法只关其一。

## AI 灵感构图（`hook/aicomposition`）

相机自带构图引擎 `OplusAICompositionHelper` 依赖厂商 native，本机用不了；相机的特性清单（主菜单入口）与配置位 `com.oplus.feature.aicomposition.*.support` 已放行，所以只换引擎。设置“拍摄 → 实验性功能 → AI灵感构图”（`ai_composition_cloud`，默认关，相机打开时读入）。

- 云端：系统 AI 单元的 `cloud_ai_shot_suggestions`（相册同款）。AIUnit SDK 要求检测器与连接器是子类，`tools/aibridge` 编译出 `assets/aiunit_bridge.dex`（`python tools/aibridge/build.py`，占位类只供编译，dex 里只有 `local.jiege.aiunit` 两个类），`AiCompositionCloud` 用 `InMemoryDexClassLoader`（父加载器为相机）从模块 APK 取出并加载。SDK 的混淆成员按形状查找：连接器上收动作接口的方法、输入槽上 `(Bitmap, Boolean)→int`、检测器上收输入槽的处理方法、槽里的字符串字段。
- 请求 `{"aiCapData":{"zoom":1.0,"maxZoom":6.0,"sensor":[0,0,0],"lang":…}}`；答复 `{"data":{"zoom","center","reason"}}`，`center` 在 768×1024 画布上，换算成相机要的 `{"outputNum":1,"crops":[{"zoom","srcCrop","compositionDesc"…}]}`。
- 取图：构图缓冲不是 GPU 可采样纹理，用相机的 `HardwareBufferUtils.copyHardwareBufferToBitmap` 转软件位图。处理器入口（符号 `AiCompositionHelper.feed`，调用 `runCompositionStreaming` 的那个方法）收三个缓冲：768×1024 RGB_888（送给引擎的那个，转出来是空的）、768×1024 RGBA_8888（预览帧，云端要的是这个）、360×480（跟踪用），钩子记下第二个。
- 送入：`runCompositionStreaming` 在原生失败后，在后台线程向云端要建议（总等待 20 秒），经相机自己的流式回调以事件 1、2 送入。原厂本地引擎（X10 的 lite2 视觉语言模型）一次推理最多写出两个框（`box_a`、`box_b`），两框 IoU ≥ 0.75 就丢掉倍率小的那个；云端一次只给一个，所以并发问两次（每路各建一对检测器与连接器；两路共用一对时会拿到同一个答复），按同一规则合并（`AiCompositionCloud.merge`），倍率低于 1.2 的按 1.2 算（相机丢弃更小的裁剪）。一轮构图只问一轮云端（`reset` 后再问）：每帧重算会被判成画面剧变而退出。
- 变焦：进入构图前相机自己退到 1X（`startRealSceneComposition` → `executeZoomOutBeforeComposition`，到 1X 后等预览稳定再取帧），超出构图范围的变焦会取消构图；云端的倍率是相对 1X 的建议，由相机推到该倍率。这是原厂流程，模块不碰变焦。
- 跟踪：`initializeTracker` / `track` 由 `AiCompositionAttitude` 接管。原厂每帧把旋转矢量传感器的姿态四元数（w, x, y, z，设备到世界，`vb.u` 300 帧环形缓冲按帧时间戳取最近一条）连同 360×480 的跟踪帧尺寸、变焦倍率（初始化时）、锚点像素交给引擎；这里取同一份四元数，按针孔模型把锚点的视线从起始姿态转到当前姿态再投影回像素（1X 焦距按 23 mm 等效、对角线 43.27 mm，乘变焦倍率），不自己开传感器。没有姿态或目标转到相机背后时保持上一个位置。返回的第四个值必须是 3（锁定），否则状态机判定画面变化较大而退出。
