// Fujifilm X100VI film simulation color engine (tables identical in the X-T5 v4.31 firmware) (kind 13, looks/x100vi_*.bin).
#include <memory>

#include "master_engine.h"
#include "master_style.h"
#include "x100vi_hue_block.h"

namespace mastercolor {
namespace {

using namespace detail;

struct X100viLook : Look {
    float inputScale = 1.0f;
    std::vector<float> data;  // 165 + 17^3 * 3 + 14 * 40 + 14 (luma-dependent chroma gain: 4 thresholds, 5 slopes, 5 intercepts) + x100vi::kHueFloats (hue block: flag, 36 x 5 chroma gain, 3 x 36 x 6 x 8 high-chroma correction)
    // The X-T5 Adobe Standard HueSatMap at its two calibration illuminants: the engine's front end was fitted on the
    // colorimetric scene (forward matrix only), the master front stage hands it the Adobe Standard scene.
    uint32_t hue = 0, sat = 0;
    float t1 = 0, t2 = 0;
    std::vector<float> hs1, hs2;
};

constexpr double kFrontM[3][3] = {
    { 1.175569, -0.086910, -0.088659},
    {-0.026913,  0.990242,  0.036671},
    {-0.132675,  0.143012,  0.989663},
};
constexpr double kFrontGain = 0.996683;
constexpr double kFrontPedestal = 0.000123;
// Firmware Rec.601 RGB <-> YCbCr (b30 0x00B08210, Q10) for chroma4 Cb/Cr range clamping.
constexpr double kFwYcc[3][3] = {
    { 306.0 / 1024.0,  601.0 / 1024.0,  117.0 / 1024.0},
    {-173.0 / 1024.0, -339.0 / 1024.0,  512.0 / 1024.0},
    { 512.0 / 1024.0, -429.0 / 1024.0,  -83.0 / 1024.0},
};
constexpr double kFwYccInv[3][3] = {
    {1.0, -0.0012510846063459792, 1.4019210183441391},
    {1.0, -0.3442581267761096,   -0.7139778436988783},
    {1.0,  1.7716407368889486,    0.0009645458759482},
};
// Firmware Color -4..+4 chroma gain (EEPROM 0xaf50..0xaf58, unsigned byte / 128).
constexpr double kColorGain[9] = {102.0 / 128, 109.0 / 128, 115.0 / 128, 122.0 / 128, 1.0, 134.0 / 128, 141.0 / 128, 147.0 / 128, 154.0 / 128};
// Firmware WB Shift -9..+9: Q10 multipliers on the R and B white-balance gains (b82 0x2ebb7fc), G untouched.
constexpr double kWbShiftR[19] = {554, 641, 735, 782, 834, 892, 913, 945, 977, 1024, 1050, 1088, 1124, 1159, 1242, 1315, 1417, 1612, 1896};
constexpr double kWbShiftB[19] = {587, 665, 755, 800, 852, 904, 932, 953, 990, 1024, 1041, 1064, 1095, 1120, 1169, 1225, 1278, 1398, 1534};
constexpr size_t kBaseFloats = 165 + 17 * 17 * 17 * 3;
constexpr double kCceYccLuma[3] = {0.2988, 0.5869, 0.1143};
constexpr size_t kShadowFloats = 2 * 7 * 40;  // DR100 rows, then DR200 rows
// The 3D LUT is extrapolated linearly past the top of its index range (saturated blues exceed 1 after the film colour gain; FX Blue acts on the overshoot).
constexpr double kLutCodeMax = 1.5;
constexpr double kCceAY = 0.8452;
constexpr double kCceCCh = 0.3693;
constexpr double kCceXE[2] = {159.0924 - 162.7657, 159.0924};
constexpr double kCceXB[2] = {215.0538 - 50.2728, 215.0538};
constexpr double kCceHueOffset = 1.5509;
constexpr double kCceA[6] = {-0.3167, -0.4593, 155.2244, 147.0820, 550.9013, 518.9044};
constexpr double kCceEdgesE[6] = {47.0, 83.0, 156.0, 174.0, 286.0, 347.0};
constexpr double kCceVecsE[6][2] = {{-0.4909, 0.1811}, {-0.8408, 0.5362}, {-1.0484, 0.1238}, {0.0000, 0.0000}, {0.2013, 0.4390}, {-0.3056, 0.7095}};
constexpr double kCceEdgesB[7] = {47.0, 83.0, 168.0, 189.0, 260.0, 295.0, 355.0};
constexpr double kCceVecsB[7][2] = {{0.0000, 0.0000}, {0.0000, 0.0000}, {0.0000, 0.0000}, {1.3373, -0.2973}, {0.6737, 0.0068}, {0.2588, -0.0512}, {0.0000, 0.0000}};

// REALA ACE (firmware mode 0xd) runs the module with its own sets and the "both" vector row at every level, so "off" is a
// baseline. Plateau / 256 per level (off, weak, strong); the R curve sits on the first three anchors of kCceEdgesE (orange to green), the rest A / B.
constexpr double kModeDA[3] = {96.0 / 256, 176.0 / 256, 1.0};
constexpr double kModeDR[3] = {32.0 / 256, 144.0 / 256, 1.0};
constexpr double kModeDB[3] = {96.0 / 256, 176.0 / 256, 1.0};
constexpr int kModeDRZones[3] = {0, 1, 2};

inline double clamp01(double v) {
    return std::min(std::max(v, 0.0), 1.0);
}

double evalCceSector(const double *edges, const double (*vecs)[2], int count, double h, double x,
                     double xMin, double xMax, double cb, double cr, const double *scale = nullptr) {
    int idx0 = count - 1;
    for (int k = 0; k < count; ++k) {
        if (edges[k] <= h) idx0 = k;
    }
    int idx1 = (idx0 + 1) % count;
    double span = edges[idx1] - edges[idx0];
    if (span <= 0.0) span += 360.0;
    double dh = h - edges[idx0];
    if (dh < 0.0) dh += 360.0;
    double t = dh / span;
    double f = clamp01((xMax - x) / (xMax - xMin));
    double v0 = (vecs[idx0][0] * cb + vecs[idx0][1] * cr) * (scale ? scale[idx0] : 1.0);
    double v1 = (vecs[idx1][0] * cb + vecs[idx1][1] * cr) * (scale ? scale[idx1] : 1.0);
    return ((1.0 - t) * v0 + t * v1) * f;
}

// Color Chrome Effect & Color Chrome FX Blue on post-3D-LUT sRGB-encoded output (`S6`, 0..1).
Vec3 applyColorChrome(Vec3 encoded, int cceLevel, int fxbLevel, bool modeD) {
    if (cceLevel == 0 && fxbLevel == 0 && !modeD) return encoded;
    double r = encoded[0] * 255.0, g = encoded[1] * 255.0, b = encoded[2] * 255.0;
    double y = kCceYccLuma[0] * r + kCceYccLuma[1] * g + kCceYccLuma[2] * b;
    double cb = -0.1689 * r - 0.3311 * g + 0.5000 * b;
    double cr =  0.5000 * r - 0.4189 * g - 0.0811 * b;
    double mn = std::min({r, g, b});
    double c = std::hypot(cb, cr);
    double x = kCceAY * y + (1.0 - kCceAY) * mn - kCceCCh * c;
    double h = std::fmod(std::atan2(-cb, cr) * (180.0 / 3.14159265358979323846) + 720.0 - kCceHueOffset, 360.0);

    double q = 0.0;
    if (modeD) {
        double zone[6];
        for (int k = 0; k < 6; ++k) zone[k] = kModeDA[cceLevel];
        for (int k : kModeDRZones) zone[k] = kModeDR[cceLevel];
        double fe = clamp01((kCceA[2] - x) / kCceA[3]);
        bool inA = (h >= 134.0 && h <= 194.2);
        double qeA = (kCceA[0] * cb + kCceA[1] * cr) * fe * clamp01((kCceA[4] - h) / kCceA[5]);
        double we = h < 134.0 ? 1.0 : (h < 273.9 ? 0.0 : clamp01((h - 273.9) / (307.6 - 273.9)));
        double wb = h < 134.0 ? 0.0 : (h <= 278.5 ? 1.0 : clamp01((318.9 - h) / (318.9 - 278.5)));
        double qe = evalCceSector(kCceEdgesE, kCceVecsE, 6, h, x, kCceXE[0], kCceXE[1], cb, cr, zone);
        double qb = evalCceSector(kCceEdgesB, kCceVecsB, 7, h, x, kCceXB[0], kCceXB[1], cb, cr);
        q = (inA ? kModeDA[cceLevel] * qeA : we * qe) + kModeDB[fxbLevel] * wb * qb;
    } else {
        double qe = cceLevel > 0 ? evalCceSector(kCceEdgesE, kCceVecsE, 6, h, x, kCceXE[0], kCceXE[1], cb, cr) : 0.0;
        double qb = fxbLevel > 0 ? evalCceSector(kCceEdgesB, kCceVecsB, 7, h, x, kCceXB[0], kCceXB[1], cb, cr) : 0.0;
        if (fxbLevel == 0) {
            q = (cceLevel * 0.5) * qe;
        } else if (cceLevel == 0) {
            q = (fxbLevel * 0.5) * qb;
        } else {
            double fe = clamp01((kCceA[2] - x) / kCceA[3]);
            bool inA = (h >= 134.0 && h <= 194.2);
            double qeA = (kCceA[0] * cb + kCceA[1] * cr) * fe * clamp01((kCceA[4] - h) / kCceA[5]);
            double we = h < 134.0 ? 1.0 : (h < 273.9 ? 0.0 : clamp01((h - 273.9) / (307.6 - 273.9)));
            double wb = h < 134.0 ? 0.0 : (h <= 278.5 ? 1.0 : clamp01((318.9 - h) / (318.9 - 278.5)));
            q = (cceLevel * 0.5) * (inA ? qeA : we * qe) + (fxbLevel * 0.5) * wb * qb;
        }
    }
    q = std::max(q, 0.0);
    if (q <= 0.0) return encoded;
    for (int i = 0; i < 3; ++i) {
        encoded[i] = clamp01(encoded[i] - q / 255.0);
    }
    return encoded;
}

double x100viBezier(const float *v, const float *edges, int segments, double x) {
    int k = 0;
    for (int j = 1; j < segments; ++j) if (x >= edges[j]) k = j;
    double t = (x - edges[k]) / (edges[k + 1] - edges[k]), a = 1 - t;
    return v[k * 3] * a * a * a + 3 * v[k * 3 + 1] * t * a * a + 3 * v[k * 3 + 2] * t * t * a + v[k * 3 + 3] * t * t * t;
}

class X100viRenderer : public Renderer {
public:
    std::shared_ptr<const X100viLook> look;
    Mat3 ppToSrgb{}, adobeToP3{};
    double proPhotoScale = 1.0, inputScale = 1.0;
    int hue = 0, sat = 0;
    std::vector<float> hueSatTable;  // the X-T5 Adobe Standard HueSatMap at the frame's colour temperature
    double wbR = 1.0, wbB = 1.0;
    int colorChrome = 0, colorChromeBlue = 0;
    bool modeD = false;  // REALA ACE
    float tone[40]{};
    bool dr200 = false;  // dynamic range 200: tone curve from the DR200 table, falling gain above lum 1
    double color[9]{};  // ycc_to_rgb @ diag(1, saturation * chroma4) @ rgb_to_ycc

    void render(const Vec3 &scene, float out[3]) const override {
        const float *v = look->data.data();
        const Vec3 pp = inverseHueSat(hue, sat, hueSatTable, {scene[0] * proPhotoScale, scene[1] * proPhotoScale, scene[2] * proPhotoScale});
        Vec3 lin = xform(ppToSrgb, pp);
        // Colours outside sRGB keep their negative channels through the linear front matrix; the result is clipped at 0.
        for (int i = 0; i < 3; ++i) lin[i] = (lin[i] - kFrontPedestal) * kFrontGain;
        lin[0] *= wbR / 1024.0;
        lin[2] *= wbB / 1024.0;
        Vec3 front{};
        for (int i = 0; i < 3; ++i) {
            front[i] = kFrontM[i][0] * lin[0] + kFrontM[i][1] * lin[1] + kFrontM[i][2] * lin[2];
        }
        Vec3 input{std::max(front[0], 0.0), std::max(front[1], 0.0), std::max(front[2], 0.0)};
        for (int i = 0; i < 3; ++i) {
            input[i] *= inputScale;
        }
        constexpr float edges[14] = {0, 16, 32, 64, 128, 256, 512, 1024, 2048, 4096, 6144, 8192, 12288, 16384};
        double lum = std::max(0.25 * input[0] + 0.5 * input[1] + 0.25 * input[2], 0.0);
        double x = std::min(std::max(lum * 16384.0, 0.0), 16384.0);
        double gain = lum > 0 ? (x / 16384.0) * x100viBezier(tone, edges, 13, x) / lum : 1.0;
        // Above lum 1 the DR200 gain continues past the table end from its end value and slope; DR100 saturates there.
        if (dr200 && lum > 1.0) gain = double(tone[39]) * std::pow(lum, 12.0 * double(tone[39] - tone[38]) / double(tone[39]));
        Vec3 mixed{}, shaped{}, code{}, encoded{};
        for (int i = 0; i < 3; ++i) {
            for (int j = 0; j < 3; ++j) mixed[i] += v[144 + i * 3 + j] * input[j] * gain;
        }
        for (int c = 0; c < 3; ++c) mixed[c] = std::max(mixed[c], 0.0);  // the film matrix output is clipped at 0
        for (int i = 0; i < 3; ++i) {
            shaped[i] = x100viBezier(v + 40 + i * 31, v + 133, 10, std::min(std::max(mixed[i] * 16384.0, 0.0), 16384.0));
        }
        for (int i = 0; i < 3; ++i) {
            for (int j = 0; j < 3; ++j) code[i] += color[i * 3 + j] * shaped[j];
        }
        {
            double y  = kFwYcc[0][0] * code[0] + kFwYcc[0][1] * code[1] + kFwYcc[0][2] * code[2];
            double cb = std::min(std::max(kFwYcc[1][0] * code[0] + kFwYcc[1][1] * code[1] + kFwYcc[1][2] * code[2], -0.5), 0.5);
            double cr = std::min(std::max(kFwYcc[2][0] * code[0] + kFwYcc[2][1] * code[1] + kFwYcc[2][2] * code[2], -0.5), 0.5);
            for (int i = 0; i < 3; ++i) code[i] = kFwYccInv[i][0] * y + kFwYccInv[i][1] * cb + kFwYccInv[i][2] * cr;
        }
        {
            // Film-core chroma gain: G(Y) = (b_i + a_i * Y / 256) / 256 on Y = 0..255 (b82 0x3366748 line segments), applied after chroma4 Cb/Cr clamping.
            const float *gm = v + kBaseFloats + kShadowFloats;
            const double y = (306.0 * code[0] + 601.0 * code[1] + 117.0 * code[2]) / 1024.0;
            const double y255 = std::min(std::max(y, 0.0), 1.0) * 255.0;
            int seg = 0;
            while (seg < 4 && gm[seg] <= y255) ++seg;
            const double g = (gm[9 + seg] + gm[4 + seg] * y255 / 256.0) / 256.0;
            for (int i = 0; i < 3; ++i) code[i] = y + g * (code[i] - y);
        }
        int base[3];
        double frac[3];
        for (int i = 0; i < 3; ++i) {
            code[i] = std::min(std::max(code[i], 0.0), kLutCodeMax) * 4095.0;
            base[i] = std::min(int(std::floor(code[i] / 256.0)), 15);
            frac[i] = (code[i] - base[i] * 256.0) / (base[i] == 15 ? 255.0 : 256.0);
        }
        for (int z = 0; z < 2; ++z) for (int y = 0; y < 2; ++y) for (int x2 = 0; x2 < 2; ++x2) {
            double weight = (x2 ? frac[0] : 1.0 - frac[0]) * (y ? frac[1] : 1.0 - frac[1]) * (z ? frac[2] : 1.0 - frac[2]);
            int at = 165 + (((base[2] + z) * 17 + base[1] + y) * 17 + base[0] + x2) * 3;
            for (int c = 0; c < 3; ++c) encoded[c] += weight * v[at + c];
        }
        x100vi::applyHueBlock(v + kBaseFloats + kShadowFloats + 14, encoded.data());
        encoded = applyColorChrome(encoded, colorChrome, colorChromeBlue, modeD);
        // The film core's encoded output is Adobe RGB (gamma 563/256): converted straight to Display P3.
        const double adobeGamma = 563.0 / 256.0;
        style::clampToDisplay(xform(adobeToP3, {std::pow(clamp01(encoded[0]), adobeGamma), std::pow(clamp01(encoded[1]), adobeGamma), std::pow(clamp01(encoded[2]), adobeGamma)}), out);
    }
};

class X100viStyle : public Style {
public:
    const char *id() const override { return "x100vi"; }
    bool ownsKind(uint32_t kind) const override { return kind == 13; }

    std::shared_ptr<const Look> parse(uint32_t, const std::vector<uint8_t> &blob, size_t &at) const override {
        auto d = std::make_shared<X100viLook>();
        bool ok = readValue(blob, at, d->inputScale) && (d->inputScale > 0.0f) &&
                  readFloats(blob, at, kBaseFloats + kShadowFloats + 14 + x100vi::kHueFloats, d->data) &&
                  readValue(blob, at, d->hue) && readValue(blob, at, d->sat) && d->hue >= 2 && d->hue <= 360 && d->sat >= 2 && d->sat <= 64 &&
                  readValue(blob, at, d->t1) && readValue(blob, at, d->t2) && d->t1 > 0 && d->t2 > 0 && d->t1 != d->t2 &&
                  readFloats(blob, at, size_t(d->hue) * d->sat * 3, d->hs1) && readFloats(blob, at, size_t(d->hue) * d->sat * 3, d->hs2);
        return ok ? d : nullptr;
    }

    std::unique_ptr<Renderer> prepare(const std::shared_ptr<const Look> &file, const Key &key, const Vec3 &grey) const override {
        auto look = std::static_pointer_cast<const X100viLook>(file);
        auto renderer = std::make_unique<X100viRenderer>();
        renderer->proPhotoScale = style::proPhotoGain(grey, key);
        if (!(renderer->proPhotoScale > 0)) return nullptr;
        renderer->inputScale = double(look->inputScale);
        const double weight = std::min(std::max((1.0 / key.cct - 1.0 / look->t1) / (1.0 / look->t2 - 1.0 / look->t1), 0.0), 1.0);
        renderer->hue = int(look->hue);
        renderer->sat = int(look->sat);
        renderer->hueSatTable.resize(look->hs1.size());
        for (size_t i = 0; i < look->hs1.size(); ++i) renderer->hueSatTable[i] = float(look->hs1[i] * (1.0 - weight) + look->hs2[i] * weight);
        renderer->look = look;
        renderer->ppToSrgb = mul(mul(inv(kSrgbToXyz), cat(kD50, kD65)), kProPhotoToXyz);
        renderer->adobeToP3 = Mat3{1.15015701, -0.15009213, -0.00003531, 0.04641974, 0.95356952, 0.00000245, 0.02388714, 0.02650547, 0.94960698};
        const int shiftR = std::min(std::max(int(std::lround(style::param(key, "wb_r", 0.0f, -9.0f, 9.0f))), -9), 9);
        const int shiftB = std::min(std::max(int(std::lround(style::param(key, "wb_b", 0.0f, -9.0f, 9.0f))), -9), 9);
        renderer->wbR = kWbShiftR[shiftR + 9];
        renderer->wbB = kWbShiftB[shiftB + 9];
        const int colorStep = std::min(std::max(int(std::lround(style::param(key, "color", 0.0f, -4.0f, 4.0f))), -4), 4);
        const double saturation = kColorGain[colorStep + 4];
        const float *luma = look->data.data() + 162;
        for (int i = 0; i < 3; ++i) {
            for (int j = 0; j < 3; ++j) {
                renderer->color[i * 3 + j] = luma[j] + saturation * (look->data[153 + i * 3 + j] - luma[j]);
            }
        }
        renderer->dr200 = style::param(key, "dr", 0.0f, 0.0f, 1.0f) >= 0.5f;
        const float highlight = style::param(key, "highlight", 0.0f, -2.0f, 4.0f);
        const float shadow = style::param(key, "shadow", 0.0f, -2.0f, 4.0f);
        auto sampleC40 = [&](double val, int i) {
            const double v = val + 2.0;
            const int idx0 = std::min(int(std::floor(v)), 5);
            const float frac = float(v - idx0);
            const float *base = look->data.data() + kBaseFloats + (renderer->dr200 ? 7 * 40 : 0);
            return base[idx0 * 40 + i] * (1.0f - frac) + base[(idx0 + 1) * 40 + i] * frac;
        };
        for (int i = 0; i < 24; ++i) renderer->tone[i] = sampleC40(shadow, i);
        for (int i = 24; i < 40; ++i) renderer->tone[i] = sampleC40(highlight, i);
        renderer->colorChrome = std::min(std::max(int(std::lround(style::param(key, "color_chrome", 0.0f, 0.0f, 2.0f))), 0), 2);
        renderer->colorChromeBlue = std::min(std::max(int(std::lround(style::param(key, "color_chrome_blue", 0.0f, 0.0f, 2.0f))), 0), 2);
        renderer->modeD = key.look == "x100vi_reala_ace";
        return renderer;
    }
};

}  // namespace

const Style &x100viStyle() {
    static const X100viStyle style;
    return style;
}

}  // namespace mastercolor
