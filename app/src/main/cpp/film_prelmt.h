// Film grain before the LMT (film_prelmt.cpp builds the plan, film_proproc.cpp runs it inside the capture's ProProc pass).
#pragma once

#include <cstdint>
#include <memory>
#include <vector>

#include "fotufilm/fotufilm.h"
#include "master_core.h"

constexpr int kFilmLattice = 33;   // code lattice nodes per axis
constexpr int kFilmMaxRadius = 6;  // taps per side the ProProc kernel reads

// One clump population of the frame (fotufilm clump field): the noise draws, the per-record blur, the amplitude at the read density.
struct FilmGrainPopulation {
    float lambda = 1, limit = 0, norm = 1;
    float own = 1, shared = 0;         // sqrt(1 - rho), sqrt(rho)
    uint32_t layerBase = 0, sharedLayer = 3;
    int radius = 0;
    std::vector<float> taps[3];        // 2 * radius + 1 each, summing to 1
    float amp[3] = {0, 0, 0};
};

struct FilmGrainPlan {
    float axisMax = 1;                 // input code of the lattice's last node
    std::vector<float> gain;           // kFilmLattice^3 nodes [B][G][R], 3 x 3 each: code change of the input code per unit of grain
    std::vector<FilmGrainPopulation> populations;
};

// scene: ProPhoto D50 light (18 % grey = 0.18) of each node of a code lattice (master_core.h codeLatticeScene, scaled by the style).
bool buildGrainPlan(const fotufilm::Engine &engine, const fotufilm::Data &data, const fotufilm::Options &options,
                    const std::vector<double> &scene, int n, double axisMax, int width, int height, double frameMM, FilmGrainPlan &out);

// The frame the picture is taken as (the style's "frame" setting, in its order): the frame's diagonal in mm. The first is the default.
constexpr double kFilmFrameMM[] = {
    69.7,        // 645: 56 x 41.5
    30.0,        // half frame: 18 x 24
    43.2666153,  // 135: 36 x 24
    79.2,        // 6 x 6: 56 x 56
    89.6,        // 6 x 7: 56 x 70
    162.9,       // 4 x 5: 102 x 127
};
constexpr int kFilmFrames = sizeof(kFilmFrameMM) / sizeof(kFilmFrameMM[0]);

constexpr double kFilmLinearExponent = 2.2;  // the linear lattice's axis is lin = u^2.2
constexpr int kFilmMinCell = 8;              // the returned light is kept on a grid of cells at least this many pixels wide

// Halation (fotufilm Halation): each source record's light is scattered by a mixture of three Gaussians on the film base and each
// receiver takes a share of what the sources return. It is added to the frame's linear light, before the encode, and the LMT develops it.
struct FilmHalationPlan {
    std::vector<float> exposure;       // kFilmLattice^3 nodes [B][G][R] of the linear light: log10 layer exposures, 3 each
    std::vector<float> inverse;        // the same nodes: the inverse of d(layer exposure)/d(linear light), 3 x 3 each
    int cell = kFilmMinCell, gridW = 0, gridH = 0;
    float pxPerMM = 0;                 // frame pixels per mm on the film
    float sigmaCells[3] = {0, 0, 0};   // the three Gaussians on the grid
    float weights[3][3] = {};          // record x scale
    float matrix[3][3] = {};           // receiver x source
    float source[3] = {0, 0, 0};       // 1 for a record that returns light
};

// scene: ProPhoto D50 light (18 % grey = 0.18) of each node of the linear lattice (master_core.h linearLatticeScene, scaled by the style).
bool buildHalationPlan(const fotufilm::Engine &engine, const std::vector<double> &scene, int n, int width, int height, double frameMM,
                       FilmHalationPlan &out);

namespace mastercolor {
// Computes whichever of the grain and halation plans are enabled in a single Engine::prepare pass.
void filmPlans(const Key &key, int width, int height, FilmGrainPlan *grainOut, bool &hasGrain, FilmHalationPlan *haloOut, bool &hasHalo);
// The grain plan of a film look's key for a frame of this size: false for any other look, a film without grain or the grain switched off.
bool filmGrainPlan(const Key &key, int width, int height, FilmGrainPlan &out);
// The halation plan of a film look's key for a frame of this size: false for any other look, a film without halation or the effect switched off.
bool filmHalationPlan(const Key &key, int width, int height, FilmHalationPlan &out);
}  // namespace mastercolor
