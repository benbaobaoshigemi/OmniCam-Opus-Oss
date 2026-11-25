// The compiled-in style plugins. A new style is one style_<name>.cpp and one line here.
#include "master_style.h"

namespace mastercolor {

const Style &hncsStyle();
const Style &lumixStyle();
const Style &filmStyle();
const Style &canonStyle();
const Style &darktableStyle();
const Style &firmwareStyle();
const Style &x100viStyle();
const Style &m9Style();
const Style &m240Style();
const Style &m10rStyle();
const Style &basicStyle();
const Style &adobeStyle();
const Style &stockStyle();

namespace {

const Style *const *styles(size_t &count) {
    static const Style *const all[] = {&hncsStyle(),      &lumixStyle(),    &filmStyle(), &canonStyle(),
                                       &darktableStyle(), &firmwareStyle(), &x100viStyle(),  &m9Style(),  &m240Style(), &m10rStyle(),
                                       &basicStyle(),
                                       &adobeStyle(),     &stockStyle()};
    count = sizeof(all) / sizeof(all[0]);
    return all;
}

}  // namespace

const Style *styleForId(const std::string &look) {
    size_t count;
    const Style *const *all = styles(count);
    for (size_t i = 0; i < count; i++)
        if (all[i]->ownsId(look)) return all[i];
    return nullptr;
}

const Style *styleForKind(uint32_t kind) {
    size_t count;
    const Style *const *all = styles(count);
    for (size_t i = 0; i < count; i++)
        if (all[i]->ownsKind(kind)) return all[i];
    return nullptr;
}

}  // namespace mastercolor
