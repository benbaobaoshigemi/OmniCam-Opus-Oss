// fotufilm colour engine: scene light -> film -> print or digital reference -> display colour, per pixel.
// A native port of the engine in the workspace (实验/fotufilm色彩引擎), which follows fotufilm-engine (Apache-2.0, data CC BY-SA 4.0).
// Data comes from fotufilm.bin (tools/fotufilm/export_data.py). Settings are resolved once in Engine::prepare; render() is
// pure and thread safe.
#pragma once

#include <array>
#include <cstdint>
#include <cmath>
#include <memory>
#include <string>
#include <vector>

namespace fotufilm {

constexpr int N = 81;  // 380..780 nm in 5 nm steps
using Spec = std::array<double, N>;
using V3 = std::array<double, 3>;
using M3 = std::array<double, 9>;  // row major

struct Curve {
    double dMin = 0, gamma = 0, toe = 0, toeW = 1, sh = 0, shW = 1;
    bool hasSec = false;
    double sGamma = 0, sToe = 0, sToeW = 1, sSh = 0, sShW = 1;
    bool sampled = false;
    std::vector<double> x, y, m;
    double density(double logExposure) const;
    double dMax() const;
    void slopes();
    void shift(double stops, double fog);  // along the exposure axis (log10) and a fog density
};

struct Donor {
    Spec sens{};
    Curve curve;
    V3 inhibition{};
    double releaseGamma = 1;
};

struct Reciprocity {
    double threshold = 0, rate = 0, through = -1;
    bool hasWeights = false;
    V3 weights{};
};

struct Condition {
    double stops = 0;
    Curve curves[3];
    bool hasDonor = false;
    Curve donor;
};

// Grain of the developed film (fotufilm clump field): RMS granularity through the 48 um aperture, its clump, and how it
// varies with density.
struct Grain {
    double strength = 0, sizeMM = 0;       // published granularity (density), clump radius
    V3 layerWeights{1, 1, 1}, layerSizeRatio{1, 1, 1};
    double luma = 0;                       // share of each record's variance in a field common to all three
    double mottleShare = 0, mottleRatio = 3;
    int law = 0;                           // 0 dye cloud, 1 silver, 2 Selwyn, 3 dye-cloud reversal
    double profile[3][6] = {};             // dye cloud: per record amplitude, toe, decay, hump, hump density, hump width
    double reversal[2] = {1.1, 3};         // dye-cloud reversal: exponent, shoulder density
    double fog = 0.03;                     // developed fog, in the granularity's density
    bool readGross = false;                // the figure is read at gross (not net) density 1.0
};

// Halation on the 35 mm acetate support: three centred Gaussians (mm on the film) mixed per record, and the share of each
// source record's light each receiver gets back.
struct Halation {
    V3 sigmaMM{};
    double weights[3][3] = {};  // record x scale
    double matrix[3][3] = {};   // receiver x source
};

struct Stock {
    std::string id;
    bool reversal = false, reflectionPrint = false, monochrome = false;
    Curve paperCurve;
    Spec refSpec{};
    Spec sens[3], dye[3];
    bool hasMin = false;
    Spec minDensity{};
    Curve curves[3];
    bool hasDonor = false;
    Donor donor;
    bool geometry = false;
    double transmission[2] = {1, 1}, release[3] = {0, 0, 0}, selfRetention = 0.091;
    M3 inhibition{};  // when !geometry
    V3 releaseGamma{1, 1, 1};
    bool hasReciprocity = false;
    Reciprocity reciprocity;
    std::string nativePrint;
    std::vector<Condition> conditions;
    double cameraPreflash = 0;  // uniform pre-exposure of the taking film, linear scene units
    Grain grain;
    Halation halation;
    // Lens filter in front of the taking lens: its spectral transmittance and the through-the-lens exposure gain.
    bool hasFilter = false;
    Spec filterT{};
    double filterGain = 1;
};

struct PrintStock {
    std::string key, process;
    Curve curves[3];
    Spec analyticalDyes[3], dyes[3], sens[3];
    bool hasLad = false;
    V3 lad{};
    bool sensAboveMin = false;
    V3 trim{};
    bool projected() const { return process == "releasePrintFilm"; }
    bool positiveWorking() const { return process == "positivePaper"; }
};

struct LensFilter {
    std::string id, name;
    Spec transmittance{};
};

struct Data {
    Spec xbar, ybar, zbar, d65, statusA[3], d50, xenon, enlarger3200, daylight6504, tungsten2856, planck3200, kodakDyes[3];
    Spec edgeAnalytical[3], edgeDyes[3], edgeSens[3];
    int priorDim = 0, priorBands = 0;
    double priorAnchor = 0;
    std::vector<float> prior;
    std::vector<Stock> stocks;
    std::vector<PrintStock> prints;
    std::vector<LensFilter> filters;
    uint64_t digest = 0;  // of the file: cached results of this data are not reused for other data
    int printIndex(const std::string &key) const;
};

// Reads fotufilm.bin; null and `error` set on failure.
std::shared_ptr<const Data> loadData(const std::string &path, std::string *error);
std::shared_ptr<const Data> parseData(const std::vector<unsigned char> &blob, std::string *error);

enum class Medium { DigitalReference, Negative, Print };

// What the user chooses; every field has a neutral default.
struct Options {
    int stock = 0;
    Medium medium = Medium::DigitalReference;
    int print = -1;               // Medium::Print: index into Data::prints; -1 = the film's own (Match Film)
    int style = 0;                // digital reference: 0 reference exposure, 1 graded print
    double grade = 2.0;           // digital reference graded print: paper grade 0..5
    double push = 0;              // development, stops
    double expiredYears = 0;
    int filter = -1;              // index into Data::filters, -1 = none
    double shutterSeconds = 0;    // > 0: reciprocity failure for this exposure time
    double dir = 1;               // DIR coupler strength
    double bleach = 0;            // bleach bypass 0..1
    double viewingKelvin = 0;     // 0: the medium's own viewing light
    double printerPreflash = 0;
    double cameraPreflash = 0;    // pre-exposure of the taking film, 0..0.25
    bool printerFilter = false;   // enlarger colour head (reflection prints)
    double printerLights[3] = {25, 25, 25};  // release printer red/green/blue lights in printer points, 1..50 (cinema print film)
    double magenta = 0.4, yellow = 0.5;
    bool negativeScanner = false; // Medium::Negative: scanner instead of light box
    double shadowDetail = 0, highlightDetail = 0;
    bool operator==(const Options &o) const;
};

class Engine {
public:
    virtual ~Engine() = default;
    // Linear ProPhoto D50 scene light (18 % grey = 0.18) -> linear Display P3, unclamped.
    virtual V3 render(const V3 &prophoto) const = 0;
    // The stages render() runs through, for what happens on the film between them (grain, halation): the film with the
    // processing applied; three linear layer exposures (normal exposure of 18 % grey = 1) -> developed densities, DIR
    // couplers included and no donor layer; developed densities -> linear Display P3, unclamped.
    virtual const Stock &film() const = 0;
    // Linear ProPhoto D50 scene light (18 % grey = 0.18) -> the film's three linear layer exposures (normal exposure of grey = 1).
    virtual V3 exposure(const V3 &prophoto) const = 0;
    virtual V3 develop(const V3 &exposure) const = 0;
    virtual V3 display(const V3 &density) const = 0;
    // Null and `error` set if the options cannot be prepared (e.g. a push the film does not list).
    static std::unique_ptr<Engine> prepare(const std::shared_ptr<const Data> &data, const Options &options, std::string *error);
};

}  // namespace fotufilm
