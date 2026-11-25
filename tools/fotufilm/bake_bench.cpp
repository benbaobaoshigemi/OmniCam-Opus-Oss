// Times a full 65^3 lattice bake of one selection: bake_bench <fotufilm.bin> <film id> <medium 0|1|2> <print code -1..10> <threads> [repeat]
// Prints the wall time and a checksum of the lattice (equal checksums = equal lattice).
#include <atomic>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <string>
#include <thread>
#include <vector>

#include "../../app/src/main/cpp/fotufilm/fotufilm.h"

static double ppDecode(double x) { return x >= 0.03125 ? std::pow(x, 1.8) : x / 16; }

int main(int argc, char **argv) {
    if (argc < 6) return 2;
    std::string error;
    auto data = fotufilm::loadData(argv[1], &error);
    if (!data) return std::fprintf(stderr, "%s\n", error.c_str()), 1;
    fotufilm::Options o;
    o.stock = -1;
    for (size_t i = 0; i < data->stocks.size(); i++)
        if (data->stocks[i].id == argv[2]) o.stock = int(i);
    static const char *prints[] = {"ektacolor-edge", "crystal-archive", "endura-premier", "vision-2383", "vision-2393", "eterna-cp",
                                   "eterna-cp-3521xd", "agfa-cp30", "exr-5386", "ilfochrome-cps-1k", "ilfochrome-clm-1k"};
    const int medium = std::atoi(argv[3]), print = std::atoi(argv[4]), threads = std::atoi(argv[5]);
    const int repeat = argc > 6 ? std::atoi(argv[6]) : 1;
    o.medium = medium == 0 ? fotufilm::Medium::DigitalReference : medium == 1 ? fotufilm::Medium::Negative : fotufilm::Medium::Print;
    o.print = print >= 0 ? data->printIndex(prints[print]) : -1;
    constexpr int n = 65;
    for (int rep = 0; rep < repeat; rep++) {
        auto t0 = std::chrono::steady_clock::now();
        auto engine = fotufilm::Engine::prepare(data, o, &error);
        if (!engine) return std::fprintf(stderr, "%s\n", error.c_str()), 1;
        std::vector<float> out(size_t(n) * n * n * 3);
        std::atomic<int> next{0};
        auto worker = [&]() {
            for (int b; (b = next.fetch_add(1)) < n;)
                for (int g = 0; g < n; g++)
                    for (int r = 0; r < n; r++) {
                        const auto p3 = engine->render({ppDecode(r / 64.0) * 2.88, ppDecode(g / 64.0) * 2.88, ppDecode(b / 64.0) * 2.88});
                        float *dst = &out[((size_t(b) * n + g) * n + r) * 3];
                        for (int c = 0; c < 3; c++) dst[c] = float(p3[c]);
                    }
        };
        std::vector<std::thread> pool;
        for (int t = 1; t < threads; t++) pool.emplace_back(worker);
        worker();
        for (auto &t : pool) t.join();
        const double ms = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count();
        double sum = 0;
        for (size_t i = 0; i < out.size(); i++) sum += out[i] * double(i % 97 + 1);
        std::printf("%s medium %d print %d threads %d: %.0f ms, checksum %.9g\n", argv[2], medium, print, threads, ms, sum);
    }
    return 0;
}
