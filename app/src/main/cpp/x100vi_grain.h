#pragma once

#include <cstdint>

namespace x100vi_grain {

struct X100viGrainFrame {
    bool p010;
    uint8_t *y;
    uint8_t *uv;
    uint32_t yStride;
    uint32_t uvStride;
    int w;
    int h;
    float kr;
    float kb;
    bool fullRange;
    bool msb;
    bool hlg;
    bool vFirst;
};

void setX100viGrainState(bool masterMode, bool enabled, int strength, int size, bool isAcros);
bool isX100viGrainActive();

// Applies calibrated Fujifilm X100VI Film Grain Effect onto the given YUV frame using the currently bound ES 3.0 context.
// Returns true when grain was applied; false leaves the frame untouched.
bool applyX100viGrainGpu(const X100viGrainFrame &frame);

}  // namespace x100vi_grain
