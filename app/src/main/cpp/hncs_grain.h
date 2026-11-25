#pragma once

#include <cstdint>

namespace hncs_grain {

struct HncsGrainFrame {
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

void setHncsGrainState(bool masterMode, bool enabled, int type, int amount, int size, int roughness, int color);
bool isHncsGrainActive();

// Runs Phocus 4.1.1 CFilmGrainFilter on the given YUV frame using the currently bound ES 3.0 context.
// Returns true when grain was applied; false leaves the frame untouched.
bool applyHncsGrainGpu(const HncsGrainFrame &frame);

}  // namespace hncs_grain
