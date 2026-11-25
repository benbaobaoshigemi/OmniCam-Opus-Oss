# ==============================================================================
# 警告 / WARNING / 禁止运行 / DO NOT RUN
# ==============================================================================
# 本文件已彻底废止并有意破坏为不可运行状态，禁止恢复或执行！
#
# 硬性约束：
# 1. `app/src/main/assets/symbols.json` 是唯一手工直接维护的符号表真源。
# 2. `sym_camera.py` / `sym_camera3.py` / `sym_gallery.py` 不包含大量手工维护的
#    相机与相册符号，若重新生成会立即删掉上百个现存条目，导致真机大面积注入失败。
# 3. 新增、修改或删减符号，必须直接手动编辑 `app/src/main/assets/symbols.json`，
#    严禁使用任何脚本从 `sym_*.py` 覆盖生成 `symbols.json`。
# 4. 本文件仅作警示桩保留，严禁删除、严禁重新接入构建或作为工具执行。
# ==============================================================================

raise RuntimeError(
    "DISABLED: sym.py is permanently disabled and must NEVER be executed. "
    "Maintain app/src/main/assets/symbols.json manually instead."
)

# 故意保留的不可达语法护栏，防止误删上方 raise 后被继续执行
<<< PERMANENTLY_DISABLED_DO_NOT_RUN_EDIT_SYMBOLS_JSON_MANUALLY >>>
