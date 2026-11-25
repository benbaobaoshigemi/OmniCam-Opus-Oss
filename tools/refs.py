"""Where the reference material outside this repository lives.

The root defaults to F:/理光 and can be moved with the MUSE_REFS environment variable.
"""
import os
from pathlib import Path

ROOT = Path(os.environ.get("MUSE_REFS", "F:/理光"))
PHOTONCAMERA = ROOT / "第三方/PhotonCamera-bjzhou"        # https://github.com/bjzhou/PhotonCamera
STOCK_ODM = ROOT / "固件解包/target_x9ultra"               # Find X9 Ultra stock /odm, /vendor, /product
X10_ODM = ROOT / "固件解包/x10_pmw110"                     # Find X10 (PMW110) /odm/etc: camera config and feature graph
PULLED_LIBS = ROOT / "固件解包/拉取的库"                    # libOplusSecurity.so (config key) and other pulled libraries
ROM = ROOT / "固件解包/_rom"                               # selected extractions (c16, x8u, x9u_stock_20260924)
RMX5200 = ROOT / "固件解包/unpacked/fs"                       # realme RMX5200 11_A.01 /odm, /vendor: GR gamma and basictone tables
CPH2745 = ROOT / "固件解包/cph2745/odm_cam"                   # OPPO CPH2745 /odm
XIAOMI_17U = ROOT / "固件解包/xiaomi17u"                       # Xiaomi 17 Ultra /odm: MONOPAN 50 library and assets
SYMBOL_INDEX = ROOT / "研究/符号索引/apk_17.0.0.105"       # dexdump indexes for tools/symbols
ADOBE_DCP = ROOT / "第三方/Adobe-Standard-X9U"            # Adobe Standard profiles of the four rear lenses, from the phone's Lightroom cache
ADOBE_CAMERA_RAW = Path(os.environ.get("ADOBE_CAMERA_RAW", "C:/ProgramData/Adobe/CameraRaw"))  # CameraProfiles/ and Settings/ installed by Lightroom Classic
M240_PARAMS = ROOT / "实验/Leica-M240引擎/engine/params/m240_params.npz"   # tables of the Leica M240 film modes (extract_params.py), from firmware 2.1.0.0
M10R_PARAMS = ROOT / "实验/Leica-M10R引擎/engine/params/m10r_params.npz"   # tables of the Leica M10-R colour engine (extract_params.py), from firmware 40.26.35.51
M9_UPD = ROOT / "固件/Leica-M9/m9-1_216.upd"                # Leica M9 firmware 1.216 (the .upd from the Leica download page)
DCP_RENDER = ROOT / "研究/DCP显影"                          # dcp_render.py: DCP tag reader
SOFT_LIGHT = ROOT / "实验/柔光替换"                           # physical HDF / black mist simulation and its GPU pyramid weights (pyramid_weights.json)
FOTUFILM_ENGINE = ROOT / "实验/fotufilm色彩引擎"            # fotufilm colour engine (Python port of fotufilm-engine, Apache-2.0 / data CC BY-SA 4.0); bakes the film looks
