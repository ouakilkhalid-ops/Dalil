package ma.santojan.app

import android.app.AlertDialog
import android.content.Context
import android.widget.LinearLayout
import org.json.JSONArray

/** Petite fenêtre pour écrire un texte (nom d'un groupe, d'une personne, d'une catégorie...). */
fun MainActivity.askText(title: String, hint: String, initial: String = "", onOk: (String) -> Unit) {
    val box = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(Ui.dp(this@askText, 20), Ui.dp(this@askText, 8), Ui.dp(this@askText, 20), 0)
    }
    val input = Ui.input(this, hint).apply { setText(initial) }
    box.addView(input)
    AlertDialog.Builder(this)
        .setTitle(title)
        .setView(box)
        .setPositiveButton("زيد") { _, _ ->
            val t = input.text.toString().trim()
            if (t.isNotEmpty()) onOk(t)
        }
        .setNegativeButton("لغي", null)
        .show()
}

fun MainActivity.confirm(msg: String, onYes: () -> Unit) {
    AlertDialog.Builder(this)
        .setMessage(msg)
        .setPositiveButton("إيه") { _, _ -> onYes() }
        .setNegativeButton("لا", null)
        .show()
}

/** Liste de textes enregistrée (personnes, catégories perso...). */
object StrList {
    fun get(c: Context, key: String, def: List<String>): List<String> {
        val a = Store.arr(c, key)
        if (a.length() == 0) return def
        return (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() }
    }

    fun set(c: Context, key: String, l: List<String>) {
        val a = JSONArray(); l.forEach { a.put(it) }; Store.saveArr(c, key, a)
    }
}
