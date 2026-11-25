// Runs the actual portable device baker for every selectable look; samples are checked in Python.
#include "../../app/src/main/cpp/film_prelmt.h"
#include "../../app/src/main/cpp/master_core.h"
#include <cmath>
#include <cstdio>
#include <fstream>
#include <sstream>
#include <string>
#include <vector>

bool masterColorLook(std::string &, float &, float &, mastercolor::Params &) { return false; }
bool buildGrainPlan(const fotufilm::Engine &, const fotufilm::Data &, const fotufilm::Options &,
                    const std::vector<double> &, int, double, int, int, double, FilmGrainPlan &) { return false; }
bool buildHalationPlan(const fotufilm::Engine &, const std::vector<double> &, int, int, int, double, FilmHalationPlan &) {
    return false;
}

int main(int argc, char **argv) {
    if (argc != 5) return 2;
    std::string error;
    if (!mastercolor::loadEngine(argv[1], &error)) { std::fprintf(stderr, "%s\n", error.c_str()); return 1; }
    mastercolor::Key key;
    if (!mastercolor::readKeyFile(argv[4], key)) return 1;
    if (key.path != mastercolor::Path::Preview || key.cct != 5000 ||
        key.params != mastercolor::Params{{"probe_a", 50}, {"probe_b", 100}, {"probe_c", 150}}) return 1;
    key.params.clear();
    std::ifstream names(argv[2]);
    std::string line;
    const int axis[] = {0,8,24,40,63};
    int count = 0;
    while (std::getline(names, line)) {
        if (line.empty()) continue;
        // "<look> [name=value ...]": the look and, optionally, its style settings; the output stem is the line with spaces and '=' as '_'.
        std::istringstream tokens(line);
        std::string stem = line, token;
        for (char &c : stem) if (c == ' ' || c == '=') c = '_';
        tokens >> key.look;
        key.params.clear();
        while (tokens >> token) {
            size_t eq = token.find('=');
            if (eq == std::string::npos) return 1;
            key.params[token.substr(0, eq)] = std::strtof(token.c_str() + eq + 1, nullptr);
        }
        key.path = mastercolor::Path::Main;
        key.luxBlend = false;
        std::vector<std::vector<float>> tables;
        if (!mastercolor::lookTables(key, tables, 8)) { std::fprintf(stderr, "bake failed: %s\n", line.c_str()); return 1; }
        for (const auto &t : tables) for (float v : t) if (!std::isfinite(v) || v < 0 || v > 1) return 1;
        const std::vector<float> &lut = tables[0];
        std::ofstream out(std::string(argv[3]) + "/" + stem + ".f32", std::ios::binary);
        for (int b : axis) for (int g : axis) for (int r : axis) for (int c = 0; c < 3; ++c) {
            float v = lut[size_t(c)*64*64*64 + (b*64+g)*64+r];
            out.write(reinterpret_cast<const char *>(&v), sizeof(v));
        }
        if (!out) return 1;
        if (++count % 20 == 0) std::printf("baked %d looks\n", count);
    }
    // A style setting is part of the key and changes the table; a missing one is the style's default.
    key.params.clear();
    key.look = "film_portra400";
    std::string normal = key.name();
    std::vector<float> a, b;
    if (!mastercolor::bake(key, a, 8)) return 1;
    key.params = {{"push", 1.0f}};
    if (normal == key.name() || !mastercolor::bake(key, b, 8) || a == b) return 1;
    key.params.clear();
    const std::pair<const char *, const char *> settings[] = {
        {"basic_gamma", "gamma"}, {"sigmoid", "contrast"}, {"agx", "black"}, {"filmic", "black"}};
    const float changed[] = {2.2f, 2.2f, -8.0f, -6.0f};
    for (int i = 0; i < 4; i++) {
        key.look = settings[i].first;
        key.params.clear();
        std::string defaults = key.name();
        std::vector<float> d, e, f;
        if (!mastercolor::bake(key, d, 8)) return 1;
        key.params[settings[i].second] = changed[i];
        if (defaults == key.name() || !mastercolor::bake(key, e, 8) || d == e) return 1;
        key.params.clear();
        if (defaults != key.name() || !mastercolor::bake(key, f, 8) || d != f) return 1;
    }
    std::printf("PASS %d looks, MCOLKEY9, style settings and rendering\n", count);
    return 0;
}
