// Another model's stock master colour, served as finished LMT tables; no settings apply.
//   x7u  Find X7 Ultra: the shipped master LMT (looks/x7u.lmt, 64^3 fp32 planar [3][B][G][R]) as it is.
//        The table is X7U ODT_Photo . LMTLut0 (all four C16 LMTs are identical) pushed through the exact inverse of
//        the X9U HDRT master model (tools/mastercolor/x7u_lmt.py); it does not depend on the frame.
//   x8u  Find X8 Ultra: its stock master LMTs (looks/x8u/LMTLut0..3, PKJ110 17.0.0.104 /odm/etc/camera/basictone/lmt,
//        verbatim) laid onto this phone's six slots, so the stock selector keeps choosing by scene level and CCT
//        parameter per frame. X8U has this phone's LMT input coding (identical hybridraw built-in gamma) and
//        ODT_Photo, so its tables share the code space of the stock ones. Its TriggerBIN: two lux entries (0-195 /
//        550-10000, blended between, as here) of two CCT-parameter nodes (<= 50, >= 600, linear between): LMTLut0/1
//        and LMTLut2/3. Here each lux entry has three nodes (<= 50, 250-500, >= 700): the outer ones take X8U's
//        nodes, the middle one their mix at weight 0.591, the value that minimises the largest weight error against
//        X8U's ramp over the CCT parameter (0.23 at 250 and 500).
#include "master_engine.h"
#include "master_style.h"

namespace mastercolor {
namespace {

using namespace detail;

bool loadRawLmt(const std::string &path, std::vector<float> &lut) {
    constexpr size_t kFloats = size_t(3) * kLutSize * kLutSize * kLutSize;
    std::vector<uint8_t> bytes;
    if (!readFile(path, bytes) || bytes.size() != kFloats * sizeof(float)) return false;
    lut.resize(kFloats);
    std::memcpy(lut.data(), bytes.data(), bytes.size());
    for (size_t i = 0; i < kFloats; i += 4099)
        if (!std::isfinite(lut[i])) return false;
    return true;
}

bool x8uSlots(std::vector<std::vector<float>> &tables) {
    constexpr double kMiddle = 0.591;
    std::vector<std::vector<float>> x8(4);
    for (int i = 0; i < 4; i++)
        if (!loadRawLmt(engineDir() + "/looks/x8u/LMTLut" + std::to_string(i), x8[i])) return false;
    tables.assign(kStockTables, {});
    for (int entry = 0; entry < 2; entry++) {
        const std::vector<float> &cool = x8[2 * entry], &warm = x8[2 * entry + 1];
        tables[3 * entry] = cool;
        tables[3 * entry + 2] = warm;
        std::vector<float> &mid = tables[3 * entry + 1];
        mid.resize(cool.size());
        for (size_t i = 0; i < mid.size(); i++) mid[i] = float((1 - kMiddle) * cool[i] + kMiddle * warm[i]);
    }
    return true;
}

class StockStyle : public Style {
public:
    const char *id() const override { return "stock"; }
    bool ownsId(const std::string &look) const override { return look == "x7u" || look == "x8u"; }
    bool servesTables() const override { return true; }

    bool tables(const Key &key, std::vector<std::vector<float>> &out) const override {
        if (key.look == "x8u") return x8uSlots(out);
        out.assign(1, {});
        return loadRawLmt(engineDir() + "/looks/x7u.lmt", out[0]);
    }
};

}  // namespace

const Style &stockStyle() {
    static const StockStyle style;
    return style;
}

}  // namespace mastercolor
