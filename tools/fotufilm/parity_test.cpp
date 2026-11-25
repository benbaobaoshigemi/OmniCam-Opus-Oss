// Native engine against the Python engine: parity_test <fotufilm.bin> <cases.txt>
#include <cstdio>
#include <fstream>
#include <sstream>
#include <string>

#include "../../app/src/main/cpp/fotufilm/fotufilm.h"

int main(int argc, char **argv) {
    if (argc != 3) return 2;
    std::string error;
    auto data = fotufilm::loadData(argv[1], &error);
    if (!data) {
        std::fprintf(stderr, "load: %s\n", error.c_str());
        return 1;
    }
    std::ifstream in(argv[2]);
    std::string line;
    double worst = 0;
    int cases = 0, points = 0, failed = 0;
    std::unique_ptr<fotufilm::Engine> engine;
    std::string head;
    double caseWorst = 0;
    auto finish = [&]() {
        if (!engine) return;
        std::printf("%-60s max error %.2e%s\n", head.c_str(), caseWorst, caseWorst > 2e-3 ? "  <<<" : "");
        if (caseWorst > 2e-3) failed++;
    };
    while (std::getline(in, line)) {
        if (line.rfind("case ", 0) == 0) {
            finish();
            head = line.substr(5);
            std::istringstream s(head);
            fotufilm::Options o;
            int medium, pfilter, scanner;
            double lights[3];
            s >> o.stock >> medium >> o.print >> o.style >> o.grade >> o.push >> o.expiredYears >> o.shutterSeconds >> o.dir >> o.bleach >>
                o.viewingKelvin >> o.printerPreflash >> pfilter >> o.magenta >> o.yellow >> scanner >> o.shadowDetail >> o.highlightDetail >> o.filter >> lights[0] >> lights[1] >> lights[2];
            for (int c = 0; c < 3; c++) o.printerLights[c] = lights[c];
            o.medium = medium == 0 ? fotufilm::Medium::DigitalReference : medium == 1 ? fotufilm::Medium::Negative : fotufilm::Medium::Print;
            o.printerFilter = pfilter != 0;
            o.negativeScanner = scanner != 0;
            engine = fotufilm::Engine::prepare(data, o, &error);
            if (!engine) {
                std::printf("prepare failed (%s): %s\n", head.c_str(), error.c_str());
                failed++;
            }
            cases++;
            caseWorst = 0;
            continue;
        }
        if (!engine) continue;
        std::istringstream s(line);
        double v[3], r[3];
        char gt;
        s >> v[0] >> v[1] >> v[2] >> gt >> r[0] >> r[1] >> r[2];
        const auto out = engine->render({v[0], v[1], v[2]});
        points++;
        for (int c = 0; c < 3; c++) {
            const double e = std::fabs(out[c] - r[c]) / std::max(1.0, std::fabs(r[c]));
            caseWorst = std::max(caseWorst, e);
            worst = std::max(worst, e);
            if (e > 2e-3 && failed < 3) std::printf("   in %g %g %g ch%d native %.6f python %.6f\n", v[0], v[1], v[2], c, out[c], r[c]);
        }
    }
    finish();
    std::printf("%d cases, %d points, worst %.3e, %d failing\n", cases, points, worst, failed);
    return failed ? 1 : 0;
}
