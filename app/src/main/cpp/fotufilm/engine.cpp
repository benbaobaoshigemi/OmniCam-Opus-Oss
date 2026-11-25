#include "internal.h"

namespace fotufilm {
namespace detail {

FilmEngine::FilmEngine(std::shared_ptr<const Data> data, const Stock &film, double couplerScale)
    : data_(std::move(data)), film_(film), exposure_(*data_, film_), dev_(film_, couplerScale) {}

V3 FilmEngine::render(const V3 &prophoto) const {
    double e[4];
    const int n = exposure_.layer(mul(kProPhotoD50ToRec2020, prophoto), 1.0 / 0.18, e);
    return view(dev_.density(e, n));
}

}  // namespace detail

std::unique_ptr<Engine> Engine::prepare(const std::shared_ptr<const Data> &data, const Options &o, std::string *error) {
    using namespace detail;
    auto fail = [&](const std::string &why) -> std::unique_ptr<Engine> {
        if (error) *error = why;
        return nullptr;
    };
    if (!data || o.stock < 0 || o.stock >= int(data->stocks.size())) return fail("unknown film");
    Stock film;
    if (!applyProcessing(*data, data->stocks[o.stock], o, film, error)) return nullptr;
    switch (o.medium) {
    case Medium::DigitalReference:
        return film.reversal ? makePositive(data, film, o) : makeDigitalReference(data, film, o);
    case Medium::Negative:
        if (film.reversal) return fail("a reversal film has no negative view");
        return makeNegativeView(data, film, o);
    case Medium::Print: {
        if (film.reversal && o.print < 0) return makePositive(data, film, o);
        int index = o.print;
        if (index < 0) {
            // Match Film: the film's own print medium, Ektacolor Edge when it names none we have.
            const std::string native = film.nativePrint;
            if (native == "screen") return makeDigitalReference(data, film, o);
            index = data->printIndex(native.empty() ? "ektacolor-edge" : native);
            if (index < 0 || data->prints[index].positiveWorking()) index = data->printIndex("ektacolor-edge");
        }
        if (index < 0 || index >= int(data->prints.size())) return fail("unknown print stock");
        const PrintStock &ps = data->prints[index];
        if (film.reversal != ps.positiveWorking()) return fail(film.reversal ? "a reversal film prints on positive paper only" : "positive paper prints reversal film only");
        return makePrint(data, film, ps, o);
    }
    }
    return fail("unknown medium");
}

}  // namespace fotufilm
