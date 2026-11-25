// Natural photo mode, "关闭超长焦 AI 超分" (settings and flag file: NaturalMode.java).
//
// HybridRawImplStage2::processCore asks the virtual enhanceDecision which stage-2 route a capture takes:
//   1 jddEnhancerProcess, 2 pro bilinear resize, 3 ganSDProcess (GAN super-resolution, and the
//   diffusion model above 60x), 4 Stage2 PostSR, anything else YUVBicubicResize.
// While the flag file is present a result of 3 is turned into 0, so the capture is resized by the plain
// bicubic route; the stock persist.camera.hybridraw.stage2 switch lands on the same route. The hook is
// installed on the exported function when libOPAlgoCamHybridRaw.so loads; the flag is read per call.
#include <android/log.h>
#include <cstring>
#include <unistd.h>

#include "native_hooks.h"

namespace {

constexpr char kTag[] = "TeleSrOff";
constexpr char kFlag[] = "/data/user/0/com.oplus.camera/files/jiege/natural/gan_off";
constexpr char kLibraryName[] = "libOPAlgoCamHybridRaw.so";
constexpr char kSymbol[] = "_ZN19HybridRawImplStage215enhanceDecisionERbS0_S0_PK10APPSettingRKiS5_S5_S5_RKfRi";
constexpr int kGanSdRoute = 3;
constexpr int kBicubicRoute = 0;

// (this, bool &, bool &, bool &, const APPSetting *, const int &, const int &, const int &, const int &, const float &, int &)
using EnhanceDecision = int (*)(void *, void *, void *, void *, const void *, const void *, const void *, const void *, const void *,
                                const void *, void *);
EnhanceDecision original;

int hookedEnhanceDecision(void *self, void *a1, void *a2, void *a3, const void *a4, const void *a5, const void *a6, const void *a7,
                          const void *a8, const void *a9, void *a10) {
    int route = original(self, a1, a2, a3, a4, a5, a6, a7, a8, a9, a10);
    if (route == kGanSdRoute && access(kFlag, F_OK) == 0) {
        __android_log_print(ANDROID_LOG_INFO, kTag, "stage 2 route %d -> %d (bicubic resize)", route, kBicubicRoute);
        return kBicubicRoute;
    }
    return route;
}

}  // namespace

void onLibraryLoadedForTeleSrOff(HookFunction hook, const char *name, void *handle) {
    static bool hooked = false;
    if (hooked || !name || !std::strstr(name, kLibraryName)) return;
    hooked = true;
    hookExport(hook, handle, kSymbol, reinterpret_cast<void *>(hookedEnhanceDecision), reinterpret_cast<void **>(&original), kTag);
}
