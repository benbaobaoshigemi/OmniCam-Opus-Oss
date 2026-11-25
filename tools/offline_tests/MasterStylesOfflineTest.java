import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import local.jiege.settings.HncsStyle;
import local.jiege.settings.MasterAlign;
import local.jiege.settings.MasterStyle;
import local.jiege.settings.MasterStyles;
import local.jiege.settings.ModuleSettings;

public final class MasterStylesOfflineTest {
    private static void check(boolean ok, String reason) {
        if (!ok) throw new AssertionError(reason);
    }

    private static MasterStyle style(String label) {
        for (MasterStyle s : MasterStyles.ALL)
            if (s.label.equals(label)) return s;
        throw new AssertionError("no style " + label);
    }

    private static MasterStyle.IntLookup selections(Map<String, Integer> chosen) {
        return key -> chosen.containsKey(key) ? chosen.get(key) : ModuleSettings.defaultInt(key);
    }

    public static void main(String[] args) {
        // Persisted master_look values stay with their styles.
        int[] persisted = {0, 10, 1, 2, 3, 4, 7, 8, 11, 12, 13, 14, 15};
        check(MasterStyles.ALL.length == persisted.length, "style count");
        Set<Integer> indices = new HashSet<>();
        for (int i = 0; i < persisted.length; i++) {
            check(MasterStyles.ALL[i].index == persisted[i], "persisted index " + i);
            check(indices.add(persisted[i]), "unique index");
            check(MasterStyles.byIndex(persisted[i]) == MasterStyles.ALL[i], "lookup by index " + i);
        }
        check(MasterStyles.ALL[0].label.equals("OPPO") && !MasterStyles.ALL[0].blendable(), "stock look is listed first and not blendable");
        check(MasterStyles.byIndex(6) == MasterStyles.DEFAULT && MasterStyles.byIndex(9) == MasterStyles.DEFAULT, "unknown index is the default style");
        check(ModuleSettings.defaultInt(ModuleSettings.MASTER_LOOK) == style("X2D").index, "default style is X2D");

        // Every option card has labels for its ids, summaries line up, and the first option is the default.
        for (MasterStyle s : MasterStyles.ALL) {
            check(s.label.length() > 0 && s.summary.length() > 0, s.label + " description");
            for (MasterStyle.Choice c : s.choices()) {
                check(c.ids.length == c.labels.length && c.ids.length > 0, s.label + " " + c.title + " labels");
                check(c.summaries == null || c.summaries.length == c.ids.length, s.label + " " + c.title + " summaries");
                check(ModuleSettings.defaultInt(c.key) == 0, s.label + " " + c.title + " default");
            }
            for (MasterStyle.Param p : s.allParams()) {
                check(p.min < p.max && p.def >= p.min && p.def <= p.max && p.scale > 0, s.label + " " + p.title + " range");
                check(ModuleSettings.defaultInt(p.key) == p.def, s.label + " " + p.title + " default");
            }
        }

        // Look ids of the defaults and of the choices.
        Map<String, Integer> none = new HashMap<>();
        check(style("X2D").lookId(selections(none)).equals("hncs"), "X2D default look");
        MasterStyle.Choice hncsChoice = style("X2D").choices()[0];
        check(hncsChoice.ids.length == 7, "HNCS choice count");
        Set<String> hncs = new HashSet<>();
        for (int i = 0; i < hncsChoice.ids.length; i++) {
            Map<String, Integer> m = new HashMap<>();
            m.put(hncsChoice.key, i);
            String id = style("X2D").lookId(selections(m));
            check(id.equals(i == 0 ? "hncs" : "hncs_" + hncsChoice.ids[i]), "HNCS look id " + i);
            hncs.add(id);
        }
        check(hncs.size() == 7, "HNCS look coverage");
        // HNCS Phocus film grain presets and blend/style activation isolation.
        MasterStyle hncsStyle = style("X2D");
        check(!HncsStyle.isGrainActive(hncsStyle, false, 100, 100, selections(none)), "HNCS grain default off");
        Map<String, Integer> grainMed = new HashMap<>();
        grainMed.put(HncsStyle.GRAIN_PRESET_KEY, 1); // Medium ISO
        int[] medResolved = HncsStyle.resolveGrain(selections(grainMed));
        check(medResolved[0] == 1 && medResolved[1] == 1 && medResolved[2] == 60
            && medResolved[3] == 40 && medResolved[4] == 15 && medResolved[5] == 0, "HNCS Medium ISO preset");
        check(HncsStyle.isGrainActive(hncsStyle, false, 100, 100, selections(grainMed)), "HNCS grain active on HNCS");
        check(!HncsStyle.isGrainActive(hncsStyle, true, 0, 0, selections(grainMed)), "HNCS grain off when blend 0/0");
        check(HncsStyle.isGrainActive(hncsStyle, true, 50, 0, selections(grainMed)), "HNCS grain on when blend active");
        check(!HncsStyle.isGrainActive(style("Lumix"), false, 100, 100, selections(grainMed)), "HNCS grain isolated from other styles");
        Map<String, Integer> grainCustom = new HashMap<>();
        grainCustom.put(HncsStyle.GRAIN_PRESET_KEY, HncsStyle.GRAIN_PRESET_CUSTOM);
        grainCustom.put(HncsStyle.GRAIN_TYPE_KEY, 1); // UI Fine -> C++ Type 0
        grainCustom.put(HncsStyle.GRAIN_AMOUNT_KEY, 77);
        grainCustom.put(HncsStyle.GRAIN_SIZE_KEY, 55);
        grainCustom.put(HncsStyle.GRAIN_ROUGHNESS_KEY, 66);
        grainCustom.put(HncsStyle.GRAIN_COLOR_KEY, 22);
        int[] customResolved = HncsStyle.resolveGrain(selections(grainCustom));
        check(customResolved[0] == 1 && customResolved[1] == 0 && customResolved[2] == 77
            && customResolved[3] == 55 && customResolved[4] == 66 && customResolved[5] == 22, "HNCS custom grain mapping");
        check(style("Lumix").choices()[0].ids.length == 15, "non-Log Lumix coverage");
        check(style("Canon").choices()[0].ids.length == 6, "Canon coverage");
        check(style("M9").lookId(selections(none)).equals("leica_m9"), "M9 selection");
        check(style("X100VI").choices()[0].ids.length == 20, "X100VI coverage");
        check(style("X100VI").lookId(selections(none)).equals("x100vi_provia"), "X100VI default look");
        check(MasterStyles.byIndex(12).choices()[0].ids.length == 20, "Adobe Fuji coverage");
        check(MasterStyles.byIndex(13).choices()[0].ids.length == 12, "Adobe Sigma coverage");
        check(MasterStyles.byIndex(14).choices()[0].ids.length == 8, "Adobe OM System coverage");
        check(MasterStyles.byIndex(15).choices()[0].ids.length == 9, "Adobe Sony coverage");
        Map<String, Integer> darktable = new HashMap<>();
        darktable.put("master_look_darktable", 2);
        check(style("Darktable").lookId(selections(darktable)).equals("agx"), "Darktable AgX selection");
        Map<String, Integer> gamma = new HashMap<>();
        gamma.put("master_look_basic", 3);
        check(style("基础").lookId(selections(gamma)).equals("basic_gamma"), "Basic Gamma selection");
        check(style("基础").params(selections(gamma)).length == 1 && style("基础").params(selections(none)).length == 0, "Gamma is the only Basic parameter");
        String[][] parametric = {{"sigmoid", "2"}, {"filmic", "2"}, {"agx", "4"}};
        for (int i = 0; i < parametric.length; i++) {
            Map<String, Integer> m = new HashMap<>();
            m.put("master_look_darktable", i);
            check(style("Darktable").lookId(selections(m)).equals(parametric[i][0]), parametric[i][0] + " selection");
            check(style("Darktable").params(selections(m)).length == Integer.parseInt(parametric[i][1]), parametric[i][0] + " parameter count");
        }
        MasterStyle.Param[] agx = style("Darktable").params(selections(darktable));
        check(agx[0].def == -1000 && agx[0].id.equals("black"), "AgX defaults are the engine's own");
        check(style("Darktable").params(selections(none)).length == 2 && style("Darktable").params(selections(none))[1].id.equals("hue"), "Sigmoid is the default tone mapper");

        // Film: the look id is the film; the finish is offered per kind of film, and a stale choice falls back to the film's own.
        MasterStyle film = style("胶片");
        MasterStyle.Choice films = film.choices()[0], finishes = film.choices()[1];
        int positive = 0;
        for (int f = 0; f < films.ids.length; f++) {
            Map<String, Integer> base = new HashMap<>();
            base.put("master_look_film", f);
            check(film.lookId(selections(base)).equals("film_" + films.ids[f]), "film look id " + f);
            int offered = 0;
            for (int p = 0; p < finishes.ids.length; p++) if (finishes.offered(p, selections(base))) offered++;
            // A normal reversal film offers match + 2 positive papers; a reflection-print one (instant film) only match
            // (positive paper is for projected slides); a negative offers everything up to the positive papers.
            boolean isPositive = offered == 3 || offered == 1;
            check(isPositive || offered == 12, "finishes offered for film " + f);
            if (isPositive) positive++;
            Map<String, Integer> stale = new HashMap<>(base);
            stale.put("master_look_finish", isPositive ? 2 : 12);  // never offered to a positive film either way
            Map<String, Float> settings = film.settings(selections(stale));
            check(settings.get("print") == -1f && settings.get("push") == 0f, "stale finish and push fall back for film " + f);
        }
        check(positive > 0 && positive < films.ids.length, "positive and negative films");
        // Exposure compensation: the offset is clamped to 4 EV.
        check(Math.abs(MasterAlign.evFor("film_portra400", MasterAlign.MODE_OFFSET, 100, 0) - 1.0f) < 1e-6, "offset +1 EV");
        check(MasterAlign.evFor("film_portra400", MasterAlign.MODE_OFFSET, 900, 0) == 4f, "clamped to 4 EV");
        System.out.println("PASS MasterStyles: persisted indices, " + films.ids.length + " films, " + style("Lumix").choices()[0].ids.length + " Lumix styles, "
            + style("Canon").choices()[0].ids.length + " Canon styles");
    }
}
