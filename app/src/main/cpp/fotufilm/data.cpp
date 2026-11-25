#include <algorithm>
#include <cstdio>
#include <cstring>
#include <fstream>

#include "fotufilm.h"

namespace fotufilm {

// ---------------------------------------------------------------- curves
static double softplus(double z) { return z > 30 ? z : std::log1p(std::exp(std::min(z, 30.0))); }

void Curve::slopes() {
    m.assign(y.size(), 0.0);
    for (size_t i = 1; i + 1 < y.size(); i++) {
        const double h0 = x[i] - x[i - 1], h1 = x[i + 1] - x[i];
        const double d0 = (y[i] - y[i - 1]) / h0, d1 = (y[i + 1] - y[i]) / h1;
        if (d0 * d1 > 0) {
            const double w0 = 2 * h1 + h0, w1 = h1 + 2 * h0;
            m[i] = (w0 + w1) / (w0 / d0 + w1 / d1);
        }
    }
}

double Curve::density(double v) const {
    if (sampled) {
        const size_t n = x.size();
        if (v <= x[0]) return y[0];
        if (v >= x[n - 1]) return y[n - 1];
        size_t i = size_t(std::upper_bound(x.begin(), x.end(), v) - x.begin()) - 1;
        i = std::min(i, n - 2);
        const double x0 = x[i], x1 = x[i + 1], y0 = y[i], y1 = y[i + 1], m0 = m[i], m1 = m[i + 1];
        const double h = x1 - x0;
        const double t = std::min(std::max((v - x0) / h, 0.0), 1.0);
        const double dl = y1 - y0, a = h * m0, b = h * m1;
        return y0 + t * (a + t * (3 * dl - 2 * a - b + t * (-2 * dl + a + b)));
    }
    const double t = toeW * softplus((v - toe) / toeW);
    const double s = shW * softplus((v - sh) / shW);
    double out = dMin + gamma * std::min(std::max(t - s, 0.0), sh - toe);
    if (hasSec) {
        const double t2 = sToeW * softplus((v - sToe) / sToeW);
        const double s2 = sShW * softplus((v - sSh) / sShW);
        out += sGamma * std::min(std::max(t2 - s2, 0.0), sSh - sToe);
    }
    return out;
}

double Curve::dMax() const {
    if (sampled) return *std::max_element(y.begin(), y.end());
    double d = dMin + gamma * (sh - toe);
    if (hasSec) d += sGamma * (sSh - sToe);
    return d;
}

void Curve::shift(double stops, double fog) {
    toe += stops;
    sh += stops;
    dMin += fog;
    if (sampled)
        for (size_t i = 0; i < x.size(); i++) {
            x[i] += stops;
            y[i] += fog;
        }
    if (hasSec) {
        sToe += stops;
        sSh += stops;
    }
}

bool Options::operator==(const Options &o) const {
    return stock == o.stock && filter == o.filter && medium == o.medium && print == o.print && style == o.style && grade == o.grade && push == o.push &&
           expiredYears == o.expiredYears && shutterSeconds == o.shutterSeconds && dir == o.dir && bleach == o.bleach &&
           viewingKelvin == o.viewingKelvin && printerPreflash == o.printerPreflash && cameraPreflash == o.cameraPreflash && printerFilter == o.printerFilter &&
           magenta == o.magenta && yellow == o.yellow && negativeScanner == o.negativeScanner && shadowDetail == o.shadowDetail &&
           highlightDetail == o.highlightDetail && printerLights[0] == o.printerLights[0] && printerLights[1] == o.printerLights[1] &&
           printerLights[2] == o.printerLights[2];
}

// ---------------------------------------------------------------- file
namespace {

struct Reader {
    const std::vector<unsigned char> &b;
    size_t at = 0;
    bool ok = true;
    bool take(void *dst, size_t n) {
        if (!ok || at + n > b.size()) return ok = false;
        std::memcpy(dst, b.data() + at, n);
        at += n;
        return true;
    }
    uint32_t u32() {
        uint32_t v = 0;
        take(&v, 4);
        return v;
    }
    double f64() {
        double v = 0;
        take(&v, 8);
        return v;
    }
    bool flag() { return u32() != 0; }
    std::vector<double> arr() {
        const uint32_t n = u32();
        std::vector<double> v;
        if (!ok || n > (1u << 24) || at + size_t(n) * 8 > b.size()) {
            ok = false;
            return v;
        }
        v.resize(n);
        take(v.data(), size_t(n) * 8);
        return v;
    }
    template <size_t K>
    void fixed(std::array<double, K> &out) {
        const auto v = arr();
        if (v.size() != K) {
            ok = false;
            return;
        }
        std::copy(v.begin(), v.end(), out.begin());
    }
    void rows(Spec *out, int count) {
        const auto v = arr();
        if (v.size() != size_t(count) * N) {
            ok = false;
            return;
        }
        for (int i = 0; i < count; i++) std::copy(v.begin() + i * N, v.begin() + (i + 1) * N, out[i].begin());
    }
    std::string text() {
        const uint32_t n = u32();
        std::string s;
        if (!ok || at + n > b.size()) {
            ok = false;
            return s;
        }
        s.assign(reinterpret_cast<const char *>(b.data() + at), n);
        at += n;
        return s;
    }
    void curve(Curve &c) {
        c.dMin = f64();
        c.gamma = f64();
        c.toe = f64();
        c.toeW = f64();
        c.sh = f64();
        c.shW = f64();
        c.hasSec = flag();
        if (c.hasSec) {
            c.sGamma = f64();
            c.sToe = f64();
            c.sToeW = f64();
            c.sSh = f64();
            c.sShW = f64();
        }
        c.sampled = flag();
        if (c.sampled) {
            c.x = arr();
            c.y = arr();
            if (c.x.size() != c.y.size() || c.x.size() < 2) ok = false;
            else c.slopes();
        }
    }
};

}  // namespace

int Data::printIndex(const std::string &key) const {
    for (size_t i = 0; i < prints.size(); i++)
        if (prints[i].key == key) return int(i);
    return -1;
}

std::shared_ptr<const Data> parseData(const std::vector<unsigned char> &blob, std::string *error) {
    auto fail = [&](const char *why) -> std::shared_ptr<const Data> {
        if (error) *error = why;
        return nullptr;
    };
    if (blob.size() < 12 || std::memcmp(blob.data(), "FOTUFILM", 8) != 0) return fail("not a fotufilm data file");
    Reader r{blob, 8};
    if (r.u32() != 3) return fail("unsupported fotufilm data version");
    auto d = std::make_shared<Data>();
    {
        uint64_t h = 1469598103934665603ULL;
        for (size_t i = 0; i < blob.size(); i++) h = (h ^ blob[i]) * 1099511628211ULL;
        d->digest = h;
    }
    r.fixed(d->xbar);
    r.fixed(d->ybar);
    r.fixed(d->zbar);
    r.fixed(d->d65);
    r.rows(d->statusA, 3);
    r.fixed(d->d50);
    r.fixed(d->xenon);
    r.fixed(d->enlarger3200);
    r.fixed(d->daylight6504);
    r.fixed(d->tungsten2856);
    r.fixed(d->planck3200);
    r.rows(d->kodakDyes, 3);
    r.rows(d->edgeAnalytical, 3);
    r.rows(d->edgeDyes, 3);
    r.rows(d->edgeSens, 3);
    d->priorDim = int(r.u32());
    d->priorBands = int(r.u32());
    d->priorAnchor = r.f64();
    const size_t count = size_t(3) * d->priorDim * d->priorDim * d->priorBands;
    if (!r.ok || d->priorBands != N || d->priorDim < 2 || count * 4 > blob.size() - r.at) return fail("bad colour prior");
    d->prior.resize(count);
    r.take(d->prior.data(), count * 4);
    const uint32_t films = r.u32();
    for (uint32_t i = 0; r.ok && i < films; i++) {
        Stock s;
        s.id = r.text();
        s.reversal = r.flag();
        s.reflectionPrint = r.flag();
        s.monochrome = r.flag();
        if (s.monochrome) r.curve(s.paperCurve);
        r.fixed(s.refSpec);
        r.rows(s.sens, 3);
        r.rows(s.dye, 3);
        s.hasMin = r.flag();
        if (s.hasMin) r.fixed(s.minDensity);
        for (auto &c : s.curves) r.curve(c);
        s.hasDonor = r.flag();
        if (s.hasDonor) {
            r.fixed(s.donor.sens);
            r.curve(s.donor.curve);
            r.fixed(s.donor.inhibition);
            s.donor.releaseGamma = r.f64();
        }
        s.geometry = r.flag();
        if (s.geometry) {
            std::array<double, 2> t{};
            r.fixed(t);
            s.transmission[0] = t[0];
            s.transmission[1] = t[1];
            std::array<double, 3> rel{};
            r.fixed(rel);
            std::copy(rel.begin(), rel.end(), s.release);
            s.selfRetention = r.f64();
        } else {
            r.fixed(s.inhibition);
        }
        r.fixed(s.releaseGamma);
        s.hasReciprocity = r.flag();
        if (s.hasReciprocity) {
            s.reciprocity.threshold = r.f64();
            s.reciprocity.rate = r.f64();
            s.reciprocity.through = r.f64();
            s.reciprocity.hasWeights = r.flag();
            if (s.reciprocity.hasWeights) r.fixed(s.reciprocity.weights);
        }
        s.nativePrint = r.text();
        const uint32_t conds = r.u32();
        for (uint32_t k = 0; r.ok && k < conds; k++) {
            Condition c;
            c.stops = r.f64();
            for (auto &cc : c.curves) r.curve(cc);
            c.hasDonor = r.flag();
            if (c.hasDonor) r.curve(c.donor);
            s.conditions.push_back(std::move(c));
        }
        Grain &g = s.grain;
        g.strength = r.f64();
        g.sizeMM = r.f64();
        r.fixed(g.layerWeights);
        r.fixed(g.layerSizeRatio);
        g.luma = r.f64();
        g.mottleShare = r.f64();
        g.mottleRatio = r.f64();
        g.law = int(r.u32());
        {
            std::array<double, 18> rows{};
            r.fixed(rows);
            for (int k = 0; k < 18; k++) g.profile[k / 6][k % 6] = rows[k];
            std::array<double, 2> rev{};
            r.fixed(rev);
            g.reversal[0] = rev[0];
            g.reversal[1] = rev[1];
        }
        g.fog = r.f64();
        g.readGross = r.flag();
        Halation &h = s.halation;
        r.fixed(h.sigmaMM);
        {
            std::array<double, 9> w{}, m{};
            r.fixed(w);
            r.fixed(m);
            for (int k = 0; k < 9; k++) {
                h.weights[k / 3][k % 3] = w[k];
                h.matrix[k / 3][k % 3] = m[k];
            }
        }
        d->stocks.push_back(std::move(s));
    }
    const uint32_t prints = r.u32();
    for (uint32_t i = 0; r.ok && i < prints; i++) {
        PrintStock p;
        p.key = r.text();
        p.process = r.text();
        for (auto &c : p.curves) r.curve(c);
        r.rows(p.analyticalDyes, 3);
        r.rows(p.dyes, 3);
        r.rows(p.sens, 3);
        p.hasLad = r.flag();
        if (p.hasLad) r.fixed(p.lad);
        p.sensAboveMin = r.flag();
        r.fixed(p.trim);
        d->prints.push_back(std::move(p));
    }
    const uint32_t filters = r.u32();
    for (uint32_t i = 0; r.ok && i < filters; i++) {
        LensFilter f;
        f.id = r.text();
        f.name = r.text();
        r.fixed(f.transmittance);
        d->filters.push_back(std::move(f));
    }
    if (!r.ok || r.at != blob.size()) return fail("truncated or malformed fotufilm data");
    return d;
}

std::shared_ptr<const Data> loadData(const std::string &path, std::string *error) {
    std::ifstream in(path, std::ios::binary | std::ios::ate);
    if (!in) {
        if (error) *error = "cannot open " + path;
        return nullptr;
    }
    std::vector<unsigned char> blob(size_t(std::max<std::streamoff>(in.tellg(), 0)));
    in.seekg(0);
    in.read(reinterpret_cast<char *>(blob.data()), std::streamsize(blob.size()));
    if (!in) {
        if (error) *error = "cannot read " + path;
        return nullptr;
    }
    return parseData(blob, error);
}

}  // namespace fotufilm
