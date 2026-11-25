package local.jiege.ui

import android.content.Context
import android.util.Base64
import local.jiege.settings.MasterAlign
import local.jiege.settings.MasterStyle
import local.jiege.settings.MasterStyles
import local.jiege.settings.ModuleSettings
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * A recipe: every setting of one master engine that shapes the look, stored as that engine's own keys.
 * A choice is stored by the id of the option (stable across releases), a parameter by its setting value, and only
 * what differs from the default is kept, so a recipe applied on top of the defaults always reproduces the same state.
 */
internal data class LookRecipe(val id: String, val name: String, val values: Map<String, String>)

/**
 * The recipes of one engine ([styleIndex]): its own list, its own share code ([codeTag]). A recipe never reads or
 * writes another engine's settings, and a code from another engine is not taken in.
 */
internal class LookRecipes private constructor(
    private val styleIndex: Int,
    private val prefsName: String,
    codeTag: String,
    /** The engine as the user calls it, for the share heading and the page text. */
    val title: String,
) {
    companion object {
        private const val LIST = "list"
        val FILM = LookRecipes(STYLE_FILM, "film_recipes", "MUSEFILM1", "胶片")
        val X100VI = LookRecipes(STYLE_X100VI, "x100vi_recipes", "MUSEX100VI1", "X100VI")

        /** The recipes of an engine that has them, or null. */
        fun of(styleIndex: Int): LookRecipes? = listOf(FILM, X100VI).firstOrNull { it.styleIndex == styleIndex }
    }

    private val codePrefix = "$codeTag:"
    private val codePattern = Regex("$codeTag:([A-Za-z0-9_-]+)")

    private val style: MasterStyle get() = MasterStyles.byIndex(styleIndex)

    /** The settings outside the style's own cards that belong to the engine's look: exposure, white point, vignette. */
    private fun extraKeys(): List<String> = listOf(
        MasterAlign.exposureModeKey(styleIndex),
        MasterAlign.exposureKey(styleIndex),
        MasterAlign.alignKey(styleIndex),
        MasterAlign.wbTempKey(styleIndex),
        MasterAlign.wbTintKey(styleIndex),
        ModuleSettings.vignetteKey(styleIndex),
    )

    private fun allKeys(): List<String> =
        style.choices().map { it.key } + style.allParams().map { it.key } + extraKeys()

    /** The engine's current look as recipe values. */
    fun capture(store: SettingsStore): Map<String, String> {
        val selections = MasterStyle.IntLookup { key -> store.getInt(key) }
        val out = LinkedHashMap<String, String>()
        for (choice in style.choices()) {
            if (!choice.visible.test(selections)) continue
            val index = store.getInt(choice.key)
            if (index == ModuleSettings.defaultInt(choice.key)) continue
            if (index !in choice.ids.indices || !choice.offered(index, selections)) continue
            out[choice.key] = choice.ids[index]
        }
        for (param in style.params(selections)) {
            val value = store.getInt(param.key)
            if (value != ModuleSettings.defaultInt(param.key)) out[param.key] = value.toString()
        }
        for (key in extraKeys()) {
            val value = store.getInt(key)
            if (value != ModuleSettings.defaultInt(key)) out[key] = value.toString()
        }
        return out
    }

    /** Puts the engine into the recipe's state: everything the recipe does not name returns to its default. */
    fun apply(store: SettingsStore, recipe: LookRecipe) {
        val choices = style.choices().associateBy { it.key }
        for (key in allKeys()) {
            val stored = recipe.values[key]
            val choice = choices[key]
            val value = when {
                stored == null -> ModuleSettings.defaultInt(key)
                choice != null -> choice.ids.indexOf(stored).takeIf { it >= 0 } ?: ModuleSettings.defaultInt(key)
                else -> stored.toIntOrNull() ?: ModuleSettings.defaultInt(key)
            }
            store.setInt(key, value)
        }
        store.setInt(ModuleSettings.MASTER_LOOK, styleIndex)
    }

    /** A name for the current look: the first choice (the film, the simulation) and when it was saved. */
    fun defaultName(store: SettingsStore): String {
        val film = style.choices().first()
        val index = store.getInt(film.key).coerceIn(0, film.labels.lastIndex)
        val stamp = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date())
        return "${film.labels[index]} · $stamp"
    }

    /** One line saying what the recipe is: the first choice and how many other settings differ from the default. */
    fun summary(recipe: LookRecipe): String {
        val film = style.choices().first()
        val index = film.ids.indexOf(recipe.values[film.key] ?: film.ids[0]).coerceAtLeast(0)
        val others = recipe.values.size - (if (recipe.values.containsKey(film.key)) 1 else 0)
        return if (others > 0) "${film.labels[index]} · 另调 $others 项" else film.labels[index]
    }

    fun list(context: Context): List<LookRecipe> {
        val text = prefs(context).getString(LIST, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(text)
            (0 until array.length()).map { i ->
                val item = array.getJSONObject(i)
                val values = item.getJSONObject("values")
                LookRecipe(
                    id = item.getString("id"),
                    name = item.getString("name"),
                    values = values.keys().asSequence().associateWith { values.getString(it) },
                )
            }
        }.getOrDefault(emptyList())
    }

    /** Saves the recipe at the top of the list; false when an identical look is already saved. */
    fun add(context: Context, name: String, values: Map<String, String>): Boolean {
        val current = list(context)
        if (current.any { it.values == values }) return false
        write(context, listOf(LookRecipe(UUID.randomUUID().toString(), name, values)) + current)
        return true
    }

    fun rename(context: Context, id: String, name: String) {
        write(context, list(context).map { if (it.id == id) it.copy(name = name) else it })
    }

    fun remove(context: Context, id: String) {
        write(context, list(context).filter { it.id != id })
    }

    private fun write(context: Context, recipes: List<LookRecipe>) {
        val array = JSONArray()
        for (recipe in recipes) {
            array.put(
                JSONObject()
                    .put("id", recipe.id)
                    .put("name", recipe.name)
                    .put("values", JSONObject(recipe.values)),
            )
        }
        prefs(context).edit().putString(LIST, array.toString()).apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    /** The recipe as one line of text that anyone can paste into a chat. */
    fun encode(name: String, values: Map<String, String>): String {
        val body = buildString {
            append(name.replace('\n', ' '))
            for ((key, value) in values) append('\n').append(key).append('=').append(value)
        }
        val flags = Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        return codePrefix + Base64.encodeToString(body.toByteArray(Charsets.UTF_8), flags)
    }

    /** The shared message: a heading a person can read, then the code. */
    fun shareText(name: String, values: Map<String, String>): String =
        "Muse 胶片配方「$name」\n" + encode(name, values)

    /**
     * The recipe a text carries, or null when it carries none. Anything this engine does not know
     * (another key, an unknown option, a value out of range) is dropped, so a code from a newer or damaged
     * source can never write outside this engine.
     */
    fun decode(text: String): LookRecipe? {
        val payload = codePattern.find(text)?.groupValues?.get(1) ?: return null
        val lines = runCatching {
            String(Base64.decode(payload, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING), Charsets.UTF_8).split('\n')
        }.getOrNull() ?: return null
        val name = lines.firstOrNull()?.trim().orEmpty().ifEmpty { return null }
        val choices = style.choices().associateBy { it.key }
        val params = style.allParams().associateBy { it.key }
        val values = LinkedHashMap<String, String>()
        for (line in lines.drop(1)) {
            val at = line.indexOf('=')
            if (at <= 0) continue
            val key = line.substring(0, at)
            val value = line.substring(at + 1)
            val choice = choices[key]
            val param = params[key]
            when {
                choice != null -> if (value in choice.ids) values[key] = value
                param != null -> value.toIntOrNull()?.let { values[key] = it.coerceIn(param.min, param.max).toString() }
                key in extraKeys() -> value.toIntOrNull()?.let { values[key] = it.toString() }
            }
        }
        return LookRecipe(UUID.randomUUID().toString(), name, values)
    }
}
