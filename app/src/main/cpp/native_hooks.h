#pragma once

#include <array>
#include <cstddef>
#include <string>
#include "master_core.h"

// Inline-hook function handed to native modules by LSPosed.
using HookFunction = int (*)(void *target, void *replacement, void **backup);

// Installs a hook on an exported symbol and logs a miss under the given tag.
void hookExport(HookFunction hook, void *handle, const char *symbol, void *replacement,
                void **backup, const char *tag);

// Large-allocation trace (bigalloc_trace.cpp): active only while files/jiege/bigalloc exists in the camera's files.
void installBigAllocTrace(HookFunction hook);

// POP port: redirect POP retro settings and Meishe LUT reads to the app-private copies.
void installPopPathRemap(HookFunction hook);

// Natural photo mode (natural_mode.cpp): the edited tuning json and the softening, both per photo.
void onLibraryLoadedForNatural(const char *name);
// Returns the path to open instead of the tuning json, or null for every other path.
const char *naturalRemapPath(const char *path, char *buf, size_t n);

// Natural photo mode, sunset tuning off (sunset_off.cpp): the sunset classifier and SunsetEnhance.
void installSunsetOff(HookFunction hook);
void onLibraryLoadedForSunsetOff(const char *name);

// Natural photo mode, stage-2 AI super-resolution off (gan_off.cpp): the GAN / diffusion route of HybridRaw.
void onLibraryLoadedForTeleSrOff(HookFunction hook, const char *name, void *handle);

// Normal-mode 25MP unlock (unlock25mp.cpp): serves the relaxed config copies while the switch is on.
const char *unlock25mpRemapPath(const char *path, char *buf, size_t n);

// 哈苏超清 200MP without the big-face gate (face200m.cpp): serves the decision copy while the switch is on.
const char *face200mRemapPath(const char *path, char *buf, size_t n);

// Soft light replacement (soft_light.cpp): called for every library the camera process loads.
void onLibraryLoadedForSoftLight(HookFunction hook, const char *name, void *handle);

// GR port: called for every library the camera process loads.
void onLibraryLoadedForGr(HookFunction hook, const char *name, void *handle);
// True while BasicTone processes a master capture (set by the GR port's processCore hook).
bool basicToneMasterFrame();

// Master LUT slots (master_lut_slots.cpp): called for every library the camera process loads.
void onLibraryLoadedForLutSlots(HookFunction hook, const char *name, void *handle);
// True while the selected master filter is MonoPan50, whose own vignette replaces the stock one.
bool monoPanSelected();
// 1 MonoPan50, 2 MonoPan50 Pro (the gallery's grain), 0 neither.
int monoPanVariant();
// MonoPan50 capture look (monopan_grain.cpp): the M3 vignette and the original Monopan grain on a master capture's
// P010 luma, in place. The frame's scene lux index (the vignette table's key) is set from the capture metadata.
struct AHardwareBuffer;
bool monoPanFrame(AHardwareBuffer *buffer, bool fullRange);
void monoPanSetFrameLux(int lux);
void monoPanSetVignette(bool on);
// The 35 mm equivalent focal length of the zoom bar (soft_light.cpp), 0 until it reports.
float softLightFocalMm();

// Master colour (master_color.cpp).
extern void *gAlgoInterfaceHandle;  // libAlgoInterface handle (its deps include libcamera_metadata)
struct MasterColorCapture {
    int cct = 0;
    std::array<float, 9> ccm{};
    std::string cameraId;    // physical camera of the frame
    std::string lens;        // its scene front stage profile
    int luxIndex = -1;
    bool luxBlend = false;
    std::string look = "hncs";
    float toneWeight = 1;    // blend mode weights 0..1; 1 / 1 = replacement
    float colourWeight = 1;
    mastercolor::Params params;  // the look's style settings, passed to the style as they are
};
// Master colour selected in the module UI: false = stock master LMT, else look = hncs / lumix_* / film_*, the style's
// own settings and, in blend mode, how much of the look replaces the stock tone and colour (0..1, 1 / 1 = plain
// replacement).
bool masterColorLook(std::string &look, float &toneWeight, float &colourWeight, mastercolor::Params &params);
bool masterColorMasterMode();
void masterColorSetMasterMode(bool on);
bool masterColorNativeVignette();
void masterColorSetNativeVignette(bool on);
bool masterColorFilmPhysicalVignette(const std::string &look, const mastercolor::Params &params);
bool masterColorOpticalVignette();
bool masterColorCaptureInfo(void *frame, int cctFromParams, MasterColorCapture &out);
// The colour temperature and scene lens of a master frame whose LMT was already chosen, found by the frame's post-AWB CCM (what HybridRaw's
// ProProc, which runs before the filter node, has of the frame). False if no such frame has been seen.
bool masterColorSceneOfFrame(const float ccm[9], int &cct, std::string &lens);
std::string masterColorCaptureDir(const MasterColorCapture &capture, int width, int height);
void masterColorPreparePath(const char *path);
const char *masterColorRemapPath(const char *path, char *buf, size_t n);
