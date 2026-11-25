// The loaded hncs_engine.bin (format 4): the output stage's tone model and the HNCS tables. Styles read it through
// engineData(); master_core.cpp owns it.
#pragma once

#include <string>
#include <vector>

#include "master_math.h"

namespace mastercolor {

struct EngineData {
    float k = 0, greyScene = 0;
    int gw = 0, gh = 0;
    float cbStart = 0, crStart = 0, divFactor = 0;
    float matrixMax = 0, tableMax = 0;
    std::vector<detail::Anchor> matrices, tables;
    detail::Mat3 cm{};
    std::vector<float> film, toneCurve, builtin, proproc;
    float greyCode = 0, cap = 0;
    std::vector<float> luxInverse;  // 64^3 x 3, [B][G][R][3]: luxIndex > 280 encoding inverse at the nodes
};

// Valid after loadEngine succeeded.
const EngineData &engineData();
// Directory of hncs_engine.bin; look files are in looks/ below it.
const std::string &engineDir();

}  // namespace mastercolor
