// Times whole LMT bakes (front stage, style, output stage) the way the camera does: bake_time <engine dir> <path 0|1|2> <threads> [look]
// The first bake pays the process-level set-up (engine files, film data, lattice); the second changes a style setting.
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <string>

#include "../../app/src/main/cpp/master_core.h"

int main(int argc, char **argv) {
    if (argc < 4) return 2;
    using Clock = std::chrono::steady_clock;
    auto ms = [](Clock::time_point a, Clock::time_point b) { return std::chrono::duration<double, std::milli>(b - a).count(); };
    std::string error;
    auto t0 = Clock::now();
    if (!mastercolor::loadEngine(std::string(argv[1]) + "/hncs_engine.bin", &error)) return std::fprintf(stderr, "%s\n", error.c_str()), 1;
    auto t1 = Clock::now();
    std::printf("loadEngine %.0f ms\n", ms(t0, t1));
    mastercolor::Key key;
    key.path = mastercolor::Path(std::atoi(argv[2]));
    key.cct = 5000;
    key.ccm = {1.0703125f, -0.1328125f, 0.0625f, -0.09375f, 1.109375f, -0.03125f, 0.046875f, -0.5703125f, 1.5234375f};
    key.lens = "main23";
    key.look = argc > 4 ? argv[4] : "film_portra400";
    key.params = {{"medium", 2}, {"print", -1}, {"exposure", 0}};
    const int threads = std::atoi(argv[3]);
    for (int i = 0; i < 4; i++) {
        key.params["dir"] = i == 3 ? 2.0f : 1.0f;   // 3: a new selection (new lattice)
        key.cct = 5000 + (i == 1 ? 120 : 0);        // 1: another key, same lattice
        std::vector<float> lut;
        auto a = Clock::now();
        const bool ok = mastercolor::bake(key, lut, threads);
        std::printf("bake %d (%s): %.0f ms, ok=%d\n", i, i == 0 ? "first" : i == 1 ? "same lattice, new cct" : i == 2 ? "repeat" : "new selection", ms(a, Clock::now()), ok);
    }
    // A selection nobody has built yet, asked for with exact = 0: the coarse stand-in.
    key.params["dir"] = 0.5f;
    key.params["exact"] = 0;
    std::vector<float> lut;
    auto a = Clock::now();
    const bool ok = mastercolor::bake(key, lut, threads);
    std::printf("bake coarse stand-in (new selection, exact=0): %.0f ms, ok=%d\n", ms(a, Clock::now()), ok);
    key.params["exact"] = 1;
    a = Clock::now();
    mastercolor::bake(key, lut, threads);
    std::printf("bake exact for the same selection: %.0f ms\n", ms(a, Clock::now()));
    return 0;
}
