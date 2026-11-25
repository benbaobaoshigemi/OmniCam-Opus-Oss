#pragma once

namespace hncs_hdrt {

// Rewrites HDRTransform's SDR NV21 output and 1/2 RGB888 GainMap from full-range HLG P010 input
// when HNCS full-range mode is active (Master mode + hncs/hncs_* look without blending).
void afterMasterTransform(void *in, void *out, void *param, void *bufs);

}  // namespace hncs_hdrt
