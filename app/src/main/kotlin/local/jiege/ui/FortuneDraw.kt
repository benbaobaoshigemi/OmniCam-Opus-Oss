package local.jiege.ui

import android.content.Context
import android.content.SharedPreferences
import java.time.LocalDate
import kotlin.random.Random

/** Effective shutters of a day that pay for one card draw: a roll of 120 film is twelve frames. */
internal const val FORTUNE_DRAW_EVERY = 12

/**
 * The card draws of the home card's easter egg.
 *
 * A draw is paid for with shutters (the camera counts them, [FORTUNE_DRAW_EVERY] a day each) and puts a card
 * on the table. The card is kept only when it is taken (收下); one that is put back (放弃) is gone, and the
 * draw with it. A card left on the table stays there for the next time the egg opens. The collection and the
 * draws of the day are kept in the app's own preferences.
 */
internal class FortuneDraws(context: Context, private val deckSize: Int) {
    private val prefs: SharedPreferences = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private fun today(): String = LocalDate.now().toString()

    private fun spentToday(): Int = if (prefs.getString(KEY_DAY, "") == today()) prefs.getInt(KEY_SPENT, 0) else 0

    /** Draws left today for [shutterToday] effective shutters. */
    fun available(shutterToday: Long): Int = (shutterToday / FORTUNE_DRAW_EVERY - spentToday()).toInt().coerceAtLeast(0)

    /** Shutters still to take for the next draw. */
    fun shotsToNext(shutterToday: Long): Int = (FORTUNE_DRAW_EVERY - shutterToday % FORTUNE_DRAW_EVERY).toInt()

    /** The collection in the order the cards were taken, each once. */
    fun collected(): List<Int> = prefs.getString(KEY_COLLECTED, "").orEmpty()
        .split(',').mapNotNull { it.toIntOrNull() }.filter { it in 0 until deckSize }.distinct()

    /** The card on the table, or null. */
    fun pending(): Int? = prefs.getInt(KEY_PENDING, -1).takeIf { it in 0 until deckSize }

    /**
     * Spends a draw and puts a card on the table (most of the time one not collected yet); null when a card is
     * already on the table or no draw is left.
     */
    fun draw(shutterToday: Long): Int? {
        if (pending() != null || available(shutterToday) <= 0) return null
        val owned = collected().toSet()
        val fresh = (0 until deckSize).filter { it !in owned }
        val pool = if (fresh.isNotEmpty() && Random.nextFloat() < 0.7f) fresh else (0 until deckSize).toList()
        val card = pool.random()
        prefs.edit()
            .putString(KEY_DAY, today())
            .putInt(KEY_SPENT, spentToday() + 1)
            .putInt(KEY_PENDING, card)
            .apply()
        return card
    }

    /** Takes the card on the table into the collection. */
    fun take() {
        val card = pending() ?: return
        prefs.edit()
            .putString(KEY_COLLECTED, (collected() + card).distinct().joinToString(","))
            .remove(KEY_PENDING)
            .apply()
    }

    /** Puts the card on the table back: it is not collected. */
    fun putBack() {
        prefs.edit().remove(KEY_PENDING).apply()
    }

    private companion object {
        const val FILE = "fortune_draws"
        const val KEY_DAY = "day"
        const val KEY_SPENT = "spent"
        const val KEY_COLLECTED = "collected"
        const val KEY_PENDING = "pending"
    }
}
