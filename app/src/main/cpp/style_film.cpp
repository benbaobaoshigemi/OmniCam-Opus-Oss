// Film: the fotufilm colour engine (cpp/fotufilm) evaluated into a 65^3 lattice in ProPhoto encoding, cached per selection.
// Look id: film_<film id>; the rest of the selection arrives as settings (see FilmStyle.java): medium, print, style, grade, push,
// expired, dir, bleach, light, preflash, film_preflash, filter, head_y, head_m, light_r, light_g, light_b, shadow, highlight. Data: fotufilm.bin next to the engine.
#include <dirent.h>
#include <sys/stat.h>
#include <unistd.h>
#include <utime.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cstdarg>
#include <cstring>
#include <memory>
#include <mutex>
#include <thread>

#ifdef __ANDROID__
#include <android/asset_manager.h>
#include <android/asset_manager_jni.h>
#include <android/log.h>
#include <jni.h>
#include <sys/resource.h>
#endif

#include "film_prelmt.h"
#include "fotufilm/fotufilm.h"
#include "master_engine.h"
#include "master_style.h"

namespace mastercolor {
namespace {

using namespace detail;

constexpr int kN = 65;
constexpr int kCoarse = 33;  // the stand-in while the exact lattice is being built
// Lattice axis over the scene ProPhoto light after the exposure gain (18 % grey = 0.18). The first kAxisNormal of the axis
// is the gamma 1.8 code of 0..2.88 (4 stops over grey); the rest is logarithmic over kHeadroomStops more stops, so the
// brightest scene light times the largest exposure (+4 EV) still lands inside the lattice and the film's own shoulder
// decides what the highlights look like, not the lattice edge. The axis ends 11 stops over grey, past where the films saturate
// as prints and digital references (9.5 stops at the latest); light beyond it takes the last node's value.
constexpr double kNormalTop = 2.88;
constexpr double kAxisNormal = 0.8;
constexpr double kHeadroomStops = 7;

inline double axisToScene(double u) {
    u = std::min(std::max(u, 0.0), 1.0);
    if (u <= kAxisNormal) return kNormalTop * ppDecode(u / kAxisNormal);
    return kNormalTop * std::exp2(kHeadroomStops * (u - kAxisNormal) / (1 - kAxisNormal));
}

inline double sceneToAxis(double x) {
    if (x <= kNormalTop) return kAxisNormal * ppEncode(x / kNormalTop);
    return std::min(kAxisNormal + (1 - kAxisNormal) * std::log2(x / kNormalTop) / kHeadroomStops, 1.0);
}

void note(const char *format, ...) {
    char text[200];
    va_list args;
    va_start(args, format);
    std::vsnprintf(text, sizeof(text), format, args);
    va_end(args);
#ifdef __ANDROID__
    __android_log_print(ANDROID_LOG_INFO, "MuseFilm", "%s", text);
#else
    std::fprintf(stderr, "MuseFilm: %s\n", text);
#endif
}

double msSince(std::chrono::steady_clock::time_point t) {
    return std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t).count();
}

// Print stocks by the code the settings use (FilmStyle.java).
const char *const kPrints[] = {"ektacolor-edge", "crystal-archive", "endura-premier",   "vision-2383", "vision-2393",
                               "eterna-cp",      "exr-5386",        "eterna-cp-3521xd", "agfa-cp30",   "ilfochrome-cps-1k",
                               "ilfochrome-clm-1k"};

std::shared_ptr<const fotufilm::Data> filmData() {
    static std::mutex mutex;
    static std::shared_ptr<const fotufilm::Data> data;
    std::lock_guard<std::mutex> lock(mutex);
    if (!data) {
        std::string error;
        data = fotufilm::loadData(engineDir() + "/fotufilm.bin", &error);
        if (!data) std::fprintf(stderr, "fotufilm: %s\n", error.c_str());
    }
    return data;
}

struct Selection {
    std::string film;
    fotufilm::Options options;
    bool operator==(const Selection &o) const { return film == o.film && options == o.options; }
};

bool selectionOf(const Key &key, const fotufilm::Data &data, Selection &out) {
    const std::string &id = key.look;
    out.film = id.substr(5);
    int stock = -1;
    for (size_t i = 0; i < data.stocks.size(); i++)
        if (data.stocks[i].id == out.film) stock = int(i);
    if (stock < 0) return false;
    fotufilm::Options &o = out.options;
    o = fotufilm::Options();
    o.stock = stock;
    const int medium = int(style::param(key, "medium", 2, 0, 2));
    o.medium = medium == 0 ? fotufilm::Medium::DigitalReference : medium == 1 ? fotufilm::Medium::Negative : fotufilm::Medium::Print;
    const int print = int(style::param(key, "print", -1, -1, 10));
    o.print = -1;
    if (print >= 0) o.print = data.printIndex(kPrints[print]);
    o.style = int(style::param(key, "style", 0, 0, 1));
    o.grade = style::param(key, "grade", 2, 0, 5);
    o.filter = int(style::param(key, "filter", -1, -1, int(data.filters.size()) - 1));
    o.push = style::param(key, "push", 0, -4, 4);
    o.expiredYears = style::param(key, "expired", 0, 0, 40);
    o.dir = style::param(key, "dir", 1, 0, 3);
    o.bleach = style::param(key, "bleach", 0, 0, 1);
    o.viewingKelvin = style::param(key, "light", 0, 0, 10000);
    o.printerPreflash = style::param(key, "preflash", 0, 0, 0.2f);
    o.cameraPreflash = style::param(key, "film_preflash", 0, 0, 0.25f);
    const float yellow = style::param(key, "head_y", 0, -0.3f, 0.3f), magenta = style::param(key, "head_m", 0, -0.3f, 0.3f);
    o.printerFilter = yellow != 0 || magenta != 0;
    o.yellow = 0.5 + yellow;
    o.magenta = 0.4 + magenta;
    o.printerLights[0] = 25 + style::param(key, "light_r", 0, -24, 25);
    o.printerLights[1] = 25 + style::param(key, "light_g", 0, -24, 25);
    o.printerLights[2] = 25 + style::param(key, "light_b", 0, -24, 25);
    o.shadowDetail = style::param(key, "shadow", 0, 0, 1);
    o.highlightDetail = style::param(key, "highlight", 0, 0, 1);
    return true;
}

// Evaluates the engine at every lattice node; null if the selection cannot be prepared.
Mat3 p3ToProPhotoMatrix() {
    return inv(mul(mul(inv(kP3ToXyz), cat(kD50, kD65)), kProPhotoToXyz));
}

double deliveryKnee(const fotufilm::Data &data, const fotufilm::Options &o) {
    const fotufilm::Stock &stock = data.stocks[o.stock];
    // FilmSDRDelivery (ColorScience.swift): a directly viewed transparency carries light above display white and rolls
    // off into [0, 1] from knee = 0.7; paper-bounded media (prints, reflection prints, viewed negatives) sit at knee = 1.0.
    const bool carriesHeadroom =
        stock.reversal && !stock.reflectionPrint &&
        (o.medium == fotufilm::Medium::DigitalReference || (o.medium == fotufilm::Medium::Print && o.print < 0));
    return carriesHeadroom ? 0.7 : 1.0;
}

void rollHighlightKnee(Vec3 &p3, double knee) {
    if (knee >= 1.0) return;
    const double room = 1.0 - knee;
    for (int c = 0; c < 3; c++) {
        if (p3[c] > knee) {
            const double over = p3[c] - knee;
            p3[c] = knee + room * over / (over + room);
        }
    }
}

std::shared_ptr<const std::vector<float>> buildLattice(const std::shared_ptr<const fotufilm::Data> &data, const fotufilm::Options &o, bool background,
                                                       int n) {
    std::string error;
    std::unique_ptr<fotufilm::Engine> engine = fotufilm::Engine::prepare(data, o, &error);
    if (!engine) {
        std::fprintf(stderr, "fotufilm: %s\n", error.c_str());
        return nullptr;
    }
    // display P3 (linear) -> ProPhoto D50 (linear): the inverse of the renderer's ProPhoto -> P3
    const Mat3 p3ToPp = p3ToProPhotoMatrix();
    const double knee = deliveryKnee(*data, o);
    auto lattice = std::make_shared<std::vector<float>>(size_t(n) * n * n * 3);
    std::atomic<int> next{0};
    auto worker = [&]() {
        // Warming runs while the camera starts: give way to it.
#ifdef __ANDROID__
        if (background) setpriority(PRIO_PROCESS, gettid(), 10);
#endif
        for (int b; (b = next.fetch_add(1)) < n;)
            for (int g = 0; g < n; g++)
                for (int r = 0; r < n; r++) {
                    const fotufilm::V3 scene = {axisToScene(r / double(n - 1)), axisToScene(g / double(n - 1)), axisToScene(b / double(n - 1))};
                    const fotufilm::V3 p3 = engine->render(scene);
                    Vec3 rolled = {p3[0], p3[1], p3[2]};
                    rollHighlightKnee(rolled, knee);
                    const Vec3 pp = xform(p3ToPp, rolled);
                    float *out = &(*lattice)[((size_t(b) * n + g) * n + r) * 3];
                    for (int c = 0; c < 3; c++) out[c] = float(std::min(std::max(ppEncode(std::max(pp[c], 0.0)), 0.0), 1.0));
                }
    };
    const unsigned threads = std::max(1u, std::min(8u, std::thread::hardware_concurrency()));
    std::vector<std::thread> pool;
    for (unsigned t = 1; t < threads; t++) pool.emplace_back(worker);
    worker();
    for (auto &t : pool) t.join();
    return lattice;
}

// ---------------------------------------------------------------- lattice cache
// Lattices are kept on disk: the camera is stopped whenever a setting changes, so memory alone would rebuild them every time.
// Bump kRevision with every change of the engine's maths; the data file's digest is part of the name.
constexpr uint32_t kRevision = 4;
constexpr size_t kLatticeFloats = size_t(kN) * kN * kN * 3;
constexpr int kKeepFiles = 16;             // disk: ~3.4 MB each, in the camera's own files, not the module package
constexpr size_t kKeepExactInMemory = 6;   // the detail sliders (shadow/highlight, printer filtration) are continuous and genuinely
constexpr size_t kKeepCoarseInMemory = 3;  // couple into the physics (see style_film.cpp's header comment): no setting here can be
                                            // pulled out of the lattice the way "exposure" is, so a bigger cache is the lever against
                                            // thrashing while a slider is nudged back and forth, not fewer distinct bakes.

uint64_t mix(uint64_t h, const void *data, size_t size) {
    const unsigned char *p = static_cast<const unsigned char *>(data);
    for (size_t i = 0; i < size; i++) h = (h ^ p[i]) * 1099511628211ULL;
    return h;
}

template <class T>
uint64_t mixValue(uint64_t h, const T &v) {
    return mix(h, &v, sizeof v);
}

uint64_t selectionHash(const Selection &s, uint64_t dataDigest) {
    const fotufilm::Options &o = s.options;
    uint64_t h = 1469598103934665603ULL;
    h = mixValue(h, kRevision);
    h = mixValue(h, dataDigest);
    h = mix(h, s.film.data(), s.film.size());
    h = mixValue(h, int(o.medium));
    for (int v : {o.print, o.style, o.filter}) h = mixValue(h, v);
    for (double v : {o.grade, o.push, o.expiredYears, o.shutterSeconds, o.dir, o.bleach, o.viewingKelvin, o.printerPreflash, o.cameraPreflash, o.magenta,
                     o.yellow, o.shadowDetail, o.highlightDetail})
        h = mixValue(h, v);
    for (double v : o.printerLights) h = mixValue(h, v);
    for (bool v : {o.printerFilter, o.negativeScanner}) h = mixValue(h, v);
    return h;
}

uint64_t checksum(const float *values, size_t count) {
    uint64_t h = 1469598103934665603ULL;
    const uint32_t *words = reinterpret_cast<const uint32_t *>(values);
    for (size_t i = 0; i < count; i++) h = (h ^ words[i]) * 1099511628211ULL;
    return h;
}

struct CacheHeader {
    char magic[8];
    uint64_t selection;
    uint64_t sum;
};

std::string cachePath(uint64_t selection) {
    char name[48];
    std::snprintf(name, sizeof(name), "/film_cache/%016llx.lat", static_cast<unsigned long long>(selection));
    return engineDir() + name;
}

std::shared_ptr<const std::vector<float>> readCache(uint64_t selection) {
    FILE *file = std::fopen(cachePath(selection).c_str(), "rb");
    if (!file) return nullptr;
    CacheHeader header{};
    auto lattice = std::make_shared<std::vector<float>>(kLatticeFloats);
    const bool ok = std::fread(&header, sizeof header, 1, file) == 1 && std::memcmp(header.magic, "FILMLAT1", 8) == 0 &&
                    header.selection == selection && std::fread(lattice->data(), sizeof(float), kLatticeFloats, file) == kLatticeFloats &&
                    std::fgetc(file) == EOF && header.sum == checksum(lattice->data(), kLatticeFloats);
    std::fclose(file);
    if (!ok) return nullptr;
    utime(cachePath(selection).c_str(), nullptr);  // most recently used
    return lattice;
}

void pruneCache() {
    const std::string dir = engineDir() + "/film_cache";
    DIR *handle = opendir(dir.c_str());
    if (!handle) return;
    std::vector<std::pair<time_t, std::string>> files;
    while (dirent *entry = readdir(handle)) {
        const std::string name = entry->d_name;
        if (name.size() < 4 || name.compare(name.size() - 4, 4, ".lat") != 0) {
            if (name.find(".tmp") != std::string::npos) unlink((dir + "/" + name).c_str());
            continue;
        }
        struct stat info;
        if (stat((dir + "/" + name).c_str(), &info) == 0) files.emplace_back(info.st_mtime, dir + "/" + name);
    }
    closedir(handle);
    std::sort(files.begin(), files.end(), [](const auto &a, const auto &b) { return a.first > b.first; });
    for (size_t i = kKeepFiles; i < files.size(); i++) unlink(files[i].second.c_str());
}

void writeCache(uint64_t selection, const std::vector<float> &lattice) {
#ifdef _WIN32
    mkdir((engineDir() + "/film_cache").c_str());
#else
    mkdir((engineDir() + "/film_cache").c_str(), 0700);
#endif
    const std::string path = cachePath(selection), temp = path + ".tmp" + std::to_string(getpid());
    FILE *file = std::fopen(temp.c_str(), "wb");
    if (!file) return;
    CacheHeader header{};
    std::memcpy(header.magic, "FILMLAT1", 8);
    header.selection = selection;
    header.sum = checksum(lattice.data(), kLatticeFloats);
    const bool ok = std::fwrite(&header, sizeof header, 1, file) == 1 && std::fwrite(lattice.data(), sizeof(float), kLatticeFloats, file) == kLatticeFloats;
    const bool closed = std::fclose(file) == 0;
    if (ok && closed && std::rename(temp.c_str(), path.c_str()) == 0) pruneCache();
    else unlink(temp.c_str());
}

// ---------------------------------------------------------------- lattices in use
using LatticePtr = std::shared_ptr<const std::vector<float>>;
using LatticeList = std::vector<std::pair<Selection, LatticePtr>>;

std::mutex memoryMutex;
LatticeList memory;  // the last few exact lattices (3.4 MB each)

LatticePtr memoryFind(const LatticeList &list, const Selection &selection) {
    std::lock_guard<std::mutex> lock(memoryMutex);
    for (const auto &entry : list)
        if (entry.first == selection) return entry.second;
    return nullptr;
}

void memoryPut(LatticeList &list, const Selection &selection, const LatticePtr &lattice, size_t keep) {
    std::lock_guard<std::mutex> lock(memoryMutex);
    if (list.size() >= keep) list.erase(list.begin());
    list.emplace_back(selection, lattice);
}

// The exact lattice if it is in memory or on disk. Never builds and never waits for a build.
LatticePtr exactIfReady(const Selection &selection, const std::shared_ptr<const fotufilm::Data> &data) {
    if (auto lattice = memoryFind(memory, selection)) return lattice;
    const auto start = std::chrono::steady_clock::now();
    if (auto lattice = readCache(selectionHash(selection, data->digest))) {
        note("%s: lattice from disk in %.0f ms", selection.film.c_str(), msSince(start));
        memoryPut(memory, selection, lattice, kKeepExactInMemory);
        return lattice;
    }
    return nullptr;
}

// The exact lattice, built if it has to be (one build at a time; the others wait for it).
LatticePtr latticeFor(const Selection &selection, const std::shared_ptr<const fotufilm::Data> &data, bool background = false) {
    if (auto lattice = exactIfReady(selection, data)) return lattice;
    static std::mutex building;
    std::lock_guard<std::mutex> lock(building);
    if (auto lattice = memoryFind(memory, selection)) return lattice;  // built while this call waited
    const auto start = std::chrono::steady_clock::now();
    auto lattice = buildLattice(data, selection.options, background, kN);
    if (!lattice) return nullptr;
    note("%s: lattice built in %.0f ms", selection.film.c_str(), msSince(start));
    writeCache(selectionHash(selection, data->digest), *lattice);
    memoryPut(memory, selection, lattice, kKeepExactInMemory);
    return lattice;
}

// A coarse lattice, quick to build, in memory only: what the preview shows until the exact one is ready.
LatticePtr coarseFor(const Selection &selection, const std::shared_ptr<const fotufilm::Data> &data) {
    static LatticeList coarse;
    static std::mutex building;
    if (auto lattice = memoryFind(coarse, selection)) return lattice;
    std::lock_guard<std::mutex> lock(building);
    if (auto lattice = memoryFind(coarse, selection)) return lattice;
    const auto start = std::chrono::steady_clock::now();
    auto lattice = buildLattice(data, selection.options, false, kCoarse);
    if (!lattice) return nullptr;
    note("%s: coarse lattice built in %.0f ms", selection.film.c_str(), msSince(start));
    memoryPut(coarse, selection, lattice, kKeepCoarseInMemory);
    return lattice;
}

class FilmRenderer : public Renderer {
public:
    std::shared_ptr<const std::vector<float>> lattice;
    int n = kN;
    Mat3 ppToP3{};
    double scale = 1;

    void render(const Vec3 &scene, float out[3]) const override {
        const Vec3 pp = {scene[0] * scale, scene[1] * scale, scene[2] * scale};
        const Vec3 r = cube(*lattice, n, {sceneToAxis(pp[0]), sceneToAxis(pp[1]), sceneToAxis(pp[2])});
        style::clampToDisplay(xform(ppToP3, {ppDecode(r[0]), ppDecode(r[1]), ppDecode(r[2])}), out);
    }
};

class FilmStyle : public Style {
public:
    const char *id() const override { return "film"; }
    bool ownsId(const std::string &look) const override { return look.rfind("film_", 0) == 0; }

    void warm(const Key &key, bool background) const override {
        const auto data = filmData();
        Selection selection;
        if (data && selectionOf(key, *data, selection)) latticeFor(selection, data, background);
    }

    std::unique_ptr<Renderer> prepare(const std::shared_ptr<const Look> &, const Key &key, const Vec3 &grey) const override {
        const auto data = filmData();
        Selection selection;
        if (!data || !selectionOf(key, *data, selection)) return nullptr;
        auto renderer = std::make_unique<FilmRenderer>();
        renderer->scale = style::proPhotoGain(grey, key);
        if (!(renderer->scale > 0)) return nullptr;
        // exact = 0: the camera is still waiting for the exact lattice; show the exact one if it is there, else a coarse stand-in
        // (the camera asks again with exact = 1 when the lattice is ready, see MasterColor.warm).
        if (style::param(key, "exact", 1, 0, 1) >= 0.5f) {
            renderer->lattice = latticeFor(selection, data);
        } else if ((renderer->lattice = exactIfReady(selection, data)) == nullptr) {
            renderer->lattice = coarseFor(selection, data);
            renderer->n = kCoarse;
        }
        if (!renderer->lattice) return nullptr;
        renderer->ppToP3 = mul(mul(inv(kP3ToXyz), cat(kD50, kD65)), kProPhotoToXyz);
        return renderer;
    }
};

#ifdef __ANDROID__
std::shared_ptr<const fotufilm::Data> filmDataFromAsset(AAssetManager *mgr) {
    static std::mutex mutex;
    static std::shared_ptr<const fotufilm::Data> data;
    std::lock_guard<std::mutex> lock(mutex);
    if (data || !mgr) return data;
    AAsset *asset = AAssetManager_open(mgr, "fotufilm.bin", AASSET_MODE_BUFFER);
    if (!asset) {
        note("fotufilm.bin asset not found");
        return nullptr;
    }
    const off_t length = AAsset_getLength(asset);
    std::vector<unsigned char> blob(length > 0 ? size_t(length) : 0);
    if (length > 0) {
        const void *buf = AAsset_getBuffer(asset);
        if (buf) {
            std::memcpy(blob.data(), buf, size_t(length));
        } else {
            size_t total = 0;
            while (total < size_t(length)) {
                const int got = AAsset_read(asset, blob.data() + total, size_t(length) - total);
                if (got <= 0) break;
                total += size_t(got);
            }
            blob.resize(total);
        }
    }
    AAsset_close(asset);
    std::string error;
    data = fotufilm::parseData(blob, &error);
    if (!data) note("fotufilm.bin parse failed: %s", error.c_str());
    return data;
}

bool renderFilmChartWithData(const std::shared_ptr<const fotufilm::Data> &data, const Key &key, float *out) {
    if (!data || !out) return false;
    Selection selection;
    if (!selectionOf(key, *data, selection)) return false;
    std::string error;
    std::unique_ptr<fotufilm::Engine> engine = fotufilm::Engine::prepare(data, selection.options, &error);
    if (!engine) {
        note("chart prepare failed: %s", error.c_str());
        return false;
    }

    constexpr int kCols = 11;
    constexpr int kRows = 9;
    constexpr double kBaseColors[kCols][3] = {
        {0.50, 0.50, 0.50},
        {0.56, 0.31, 0.23},
        {0.55, 0.14, 0.15},
        {0.60, 0.27, 0.10},
        {0.58, 0.47, 0.09},
        {0.36, 0.50, 0.13},
        {0.15, 0.46, 0.17},
        {0.12, 0.40, 0.50},
        {0.15, 0.18, 0.55},
        {0.31, 0.16, 0.48},
        {0.50, 0.17, 0.38},
    };

    const Mat3 p3ToPp = p3ToProPhotoMatrix();
    const double knee = deliveryKnee(*data, selection.options);
    const double evGain = std::exp2(double(style::exposure(key)));
    constexpr size_t kPatchFloats = size_t(kCols) * kRows * 3;

    for (int c = 0; c < kCols; c++) {
        const double *base = kBaseColors[c];
        const double luma = base[0] * kP3Luma[0] + base[1] * kP3Luma[1] + base[2] * kP3Luma[2];
        const double scale0 = luma > 0 ? 0.18 / luma : 1.0;
        for (int r = 0; r < kRows; r++) {
            const double ev = 2.4 - 0.6 * r;
            const double gain = scale0 * std::exp2(ev);
            const Vec3 refScene = {base[0] * gain, base[1] * gain, base[2] * gain};

            Vec3 refRolled = refScene;
            rollHighlightKnee(refRolled, 0.7);
            float refLin[3];
            style::clampToDisplay(refRolled, refLin);

            const Vec3 pp = xform(p3ToPp, refScene);
            const fotufilm::V3 filmIn = {pp[0] * evGain, pp[1] * evGain, pp[2] * evGain};
            const fotufilm::V3 rendered = engine->render(filmIn);
            Vec3 filmRolled = {rendered[0], rendered[1], rendered[2]};
            rollHighlightKnee(filmRolled, knee);
            float filmLin[3];
            style::clampToDisplay(filmRolled, filmLin);

            const size_t baseOffset = (size_t(r) * kCols + size_t(c)) * 3;
            for (int ch = 0; ch < 3; ch++) {
                out[baseOffset + ch] = float(srgbEncode(refLin[ch]));
                out[kPatchFloats + baseOffset + ch] = float(srgbEncode(filmLin[ch]));
            }
        }
    }
    return true;
}
#endif

}  // namespace

namespace {
double filmFrameMM(const Key &key) { return kFilmFrameMM[int(style::param(key, "frame", 0, 0, float(kFilmFrames - 1)) + 0.5f)]; }
}  // namespace

void filmPlans(const Key &key, int width, int height, FilmGrainPlan *grainOut, bool &hasGrain, FilmHalationPlan *haloOut, bool &hasHalo) {
    hasGrain = false;
    hasHalo = false;
    const bool wantGrain = grainOut != nullptr && key.look.rfind("film_", 0) == 0 && style::param(key, "grain", 1, 0, 1) >= 0.5f;
    const bool wantHalo = haloOut != nullptr && key.look.rfind("film_", 0) == 0 && style::param(key, "halation", 1, 0, 1) >= 0.5f;
    if (!wantGrain && !wantHalo) return;
    const auto data = filmData();
    Selection selection;
    if (!data || !selectionOf(key, *data, selection)) return;
    std::string error;
    const std::unique_ptr<fotufilm::Engine> engine = fotufilm::Engine::prepare(data, selection.options, &error);
    if (!engine) {
        note("film plans: %s", error.c_str());
        return;
    }
    const double frameMM = filmFrameMM(key);
    if (wantGrain) {
        std::vector<double> scene;
        Vec3 grey;
        const double cap = lmtInputCap();
        if (codeLatticeScene(key, kFilmLattice, cap, scene, grey)) {
            const double scale = style::proPhotoGain(grey, key);
            if (scale > 0) {
                for (double &v : scene) v *= scale;
                hasGrain = buildGrainPlan(*engine, *data, selection.options, scene, kFilmLattice, cap, width, height, frameMM, *grainOut);
            }
        }
    }
    if (wantHalo) {
        std::vector<double> scene;
        Vec3 grey;
        if (linearLatticeScene(key, kFilmLattice, kFilmLinearExponent, scene, grey)) {
            const double scale = style::proPhotoGain(grey, key);
            if (scale > 0) {
                for (double &v : scene) v *= scale;
                hasHalo = buildHalationPlan(*engine, scene, kFilmLattice, width, height, frameMM, *haloOut);
            }
        }
    }
}

bool filmGrainPlan(const Key &key, int width, int height, FilmGrainPlan &out) {
    bool hg = false, hh = false;
    filmPlans(key, width, height, &out, hg, nullptr, hh);
    return hg;
}

bool filmHalationPlan(const Key &key, int width, int height, FilmHalationPlan &out) {
    bool hg = false, hh = false;
    filmPlans(key, width, height, nullptr, hg, &out, hh);
    return hh;
}

const Style &filmStyle() {
    static const FilmStyle style;
    return style;
}

}  // namespace mastercolor

#ifdef __ANDROID__
extern "C" JNIEXPORT jboolean JNICALL Java_local_jiege_settings_FilmChartNative_nativeRenderChart(
    JNIEnv *env, jclass, jobject assetManager, jstring lookId, jobjectArray paramKeys, jfloatArray paramValues, jfloatArray outP3) {
    if (!assetManager || !lookId || !outP3) return JNI_FALSE;
    if (env->GetArrayLength(outP3) < 11 * 9 * 6) return JNI_FALSE;

    const char *lookChars = env->GetStringUTFChars(lookId, nullptr);
    if (!lookChars) return JNI_FALSE;
    mastercolor::Key key;
    key.look = lookChars;
    env->ReleaseStringUTFChars(lookId, lookChars);

    if (paramKeys && paramValues) {
        const jsize keyCount = env->GetArrayLength(paramKeys);
        const jsize valCount = env->GetArrayLength(paramValues);
        const jsize count = std::min(keyCount, valCount);
        jfloat *vals = count > 0 ? env->GetFloatArrayElements(paramValues, nullptr) : nullptr;
        if (vals) {
            for (jsize i = 0; i < count; i++) {
                jstring jkey = static_cast<jstring>(env->GetObjectArrayElement(paramKeys, i));
                if (jkey) {
                    const char *kchars = env->GetStringUTFChars(jkey, nullptr);
                    if (kchars) {
                        key.params[kchars] = vals[i];
                        env->ReleaseStringUTFChars(jkey, kchars);
                    }
                    env->DeleteLocalRef(jkey);
                }
            }
            env->ReleaseFloatArrayElements(paramValues, vals, JNI_ABORT);
        }
    }

    AAssetManager *mgr = AAssetManager_fromJava(env, assetManager);
    const auto data = mastercolor::filmDataFromAsset(mgr);
    std::vector<float> buf(11 * 9 * 6);
    if (!mastercolor::renderFilmChartWithData(data, key, buf.data())) return JNI_FALSE;
    env->SetFloatArrayRegion(outP3, 0, jsize(buf.size()), buf.data());
    return JNI_TRUE;
}
#endif
