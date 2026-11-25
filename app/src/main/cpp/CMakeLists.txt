cmake_minimum_required(VERSION 3.22.1)
project(jiege_native CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# One LSPosed native module (see assets/native_init). native_entry.cpp owns the
# LSPosed entry point and routes library-load callbacks to the feature files.
add_library(jiege_native SHARED
    native_entry.cpp
    capture_dump.cpp
    raw_dump.cpp
    ccm_dump.cpp
    hdrt_dump.cpp
    hncs_hdrt.cpp
    gr_basictone.cpp
    pop_path_remap.cpp
    natural_mode.cpp
    sunset_off.cpp
    gan_off.cpp
    unlock25mp.cpp
    face200m.cpp
    master_color.cpp
    master_core.cpp
    master_styles.cpp
    style_hncs.cpp
    style_lumix.cpp
    style_film.cpp
    fotufilm/data.cpp
    fotufilm/core.cpp
    fotufilm/engine.cpp
    fotufilm/digital.cpp
    fotufilm/optics.cpp
    fotufilm/print.cpp
    fotufilm/positive.cpp
    style_canon.cpp
    style_darktable.cpp
    style_firmware.cpp
    style_x100vi.cpp
    style_m9.cpp
    style_m240.cpp
    style_m10r.cpp
    style_basic.cpp
    style_adobe.cpp
    style_stock.cpp
    master_dt.cpp
    master_canon.cpp
    soft_light.cpp
    hncs_grain.cpp
    x100vi_grain.cpp
    film_prelmt.cpp
    film_proproc.cpp
    master_lut_slots.cpp
    monopan_grain.cpp
    bigalloc_trace.cpp)

# The allocation trace walks frame pointers.
set_source_files_properties(bigalloc_trace.cpp PROPERTIES COMPILE_OPTIONS -fno-omit-frame-pointer)

target_compile_options(jiege_native PRIVATE -O2 -fvisibility=hidden)
target_link_libraries(jiege_native log dl camera2ndk android EGL GLESv3)

# MonoPan50 grain helper: a plain executable that the module starts (service.sh) next to the vendor library it runs,
# built straight into the module payload.
add_executable(monopan_helper monopan_helper.cpp)
set_target_properties(monopan_helper PROPERTIES RUNTIME_OUTPUT_DIRECTORY ${CMAKE_CURRENT_SOURCE_DIR}/../../../../module/payload/monopan)
target_compile_options(monopan_helper PRIVATE -O2)
target_link_libraries(monopan_helper log dl)
