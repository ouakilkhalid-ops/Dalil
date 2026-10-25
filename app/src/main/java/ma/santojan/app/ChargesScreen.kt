package ma.santojan.app

import android.content.Context
import android.view.View
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

val BASE_CATS = listOf(
    "🏠 الكرا", "🍲 الماكلة", "📚 القراية", "💡 الضو والما", "📶 التيليفون", "⛽ البنزين والنقل",
    "☕ القهوة", "👕 اللبسة", "💊 الصحة", "🏦 الكريدي", "🎁 الهدايا", "📦 أخرى"
)
val BASE_PEOPLE = listOf("👤 أنا", "🏠 الدار")

fun allCats(c: Context): List<String> = BASE_CATS + StrList.get(c, "cats_custom", emptyList())
fun allPeople(c: Context): List<String> = StrList.get(c, "people", BASE_PEOPLE)

class Charge(val id: Long, val amount: Double, val cat: String, val note: String, val date: Long, val who: String)

object Charges {
    private const val KEY = "charges"

    fun list(c: Context): List<Charge> {
        val a = Store.arr(c, KEY)
        return (0 until a.length()).mapNotNull {
            try {
                val o = a.getJSONObject(it)
                Charge(o.getLong("id"), o.getDouble("amount"), o.getString("cat"), o.optString("note"),
                    o.getLong("date"), o.optString("who", "👤 أنا"))
            } catch (e: Exception) { null }
        }
    }

    private fun save(c: Context, l: List<Charge>) {
        val a = JSONArray()
        l.forEach {
            a.put(JSONObject().put("id", it.id).put("amount", it.amount).put("cat", it.cat)
                .put("note", it.note).put("date", it.date).put("who", it.who))
        }
        Store.saveArr(c, KEY, a)
    }

    fun add(c: Context, amount: Double, cat: String, note: String, who: String) {
        val now = System.currentTimeMillis()
        save(c, list(c) + Charge(now, amount, cat, note, now, who))
    }

    fun remove(c: Context, id: Long) = save(c, list(c).filter { it.id != id })
}

private fun sameMonth(t: Long, offset: Int): Boolean {
    val ref = Calendar.getInstance().apply { add(Calendar.MONTH, offset) }
    val d = Calendar.getInstance().apply { timeInMillis = t }
    return d.get(Calendar.YEAR) == ref.get(Calendar.YEAR) && d.get(Calendar.MONTH) == ref.get(Calendar.MONTH)
}

/** Rangée de choix (chips) avec un bouton « + » pour en ajouter. */
private fun MainActivity.chipPicker(
    items: List<String>, selected: String, onSelect: (String) -> Unit,
    addLabel: String, onAdd: () -> Unit, onLongPress: ((String) -> Unit)? = null
): View {
    val ctx = this
    val row = Ui.row(ctx)
    val chips = mutableListOf<Button>()
    var sel = selected
    fun paint() = chips.forEach {
        it.background = Ui.round(if (it.tag == sel) Ui.ACCENT else Ui.CHIP, Ui.dp(ctx, 10).toFloat())
    }
    items.forEach { item ->
        val b = Ui.button(ctx, item, Ui.CHIP) {}
        b.tag = item
        b.setOnClickListener { sel = item; paint(); onSelect(item) }
        if (onLongPress != null) b.setOnLongClickListener { onLongPress(item); true }
        chips.add(b)
        row.addView(b, Ui.lp(ctx))
    }
    row.addView(Ui.button(ctx, addLabel, Ui.BLUE) { onAdd() }, Ui.lp(ctx))
    paint()
    return HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; addView(row) }
}

private fun MainActivity.barLine(parent: LinearLayout, label: String, sum: Double, total: Double, color: Int) {
    val ctx = this
    val pct = if (total > 0) (sum / total).toFloat() else 0f
    val line = Ui.row(ctx)
    line.addView(Ui.text(ctx, label, 13f), Ui.lp(ctx, 1f, 0))
    line.addView(Ui.text(ctx, "${Fmt.money(sum)}  (${(pct * 100).toInt()}%)", 13f, Ui.MUTED))
    parent.addView(line)
    val bar = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        background = Ui.round(Ui.CHIP, Ui.dp(ctx, 4).toFloat())
    }
    bar.addView(View(ctx).apply { background = Ui.round(color, Ui.dp(ctx, 4).toFloat()) },
        LinearLayout.LayoutParams(0, Ui.dp(ctx, 8), pct))
    bar.addView(View(ctx), LinearLayout.LayoutParams(0, Ui.dp(ctx, 8), 1f - pct))
    parent.addView(bar, LinearLayout.LayoutParams(Ui.MATCH, Ui.dp(ctx, 8)).apply { bottomMargin = Ui.dp(ctx, 8) })
}

fun MainActivity.chargesScreen(): View {
    val ctx = this
    val col = Ui.column(ctx)
    col.addView(Ui.title(ctx, "💰 المصاريف"))

    // ---- Ajouter une dépense
    val add = Ui.card(ctx)
    add.addView(Ui.text(ctx, "زيد مصروف", 16f, bold = true))
    val amount = Ui.input(ctx, "المبلغ بالدرهم", number = true)
    add.addView(amount)

    var cat = "📦 أخرى"
    add.addView(Ui.text(ctx, "شنو؟", 13f, Ui.MUTED))
    add.addView(chipPicker(allCats(ctx), cat, { cat = it }, "➕ فئة", {
        askText("فئة جديدة", "مثلا: 🏋️ الرياضة") { name ->
            val custom = StrList.get(ctx, "cats_custom", emptyList())
            if (name !in allCats(ctx)) StrList.set(ctx, "cats_custom", custom + name)
            refresh()
        }
    }, { item ->
        if (item !in BASE_CATS) confirm("نمسح الفئة «$item»؟") {
            StrList.set(ctx, "cats_custom", StrList.get(ctx, "cats_custom", emptyList()) - item); refresh()
        }
    }))

    var who = allPeople(ctx).first()
    add.addView(Ui.text(ctx, "ديال من؟", 13f, Ui.MUTED))
    add.addView(chipPicker(allPeople(ctx), who, { who = it }, "➕ شخص", {
        askText("شخص جديد", "مثلا: ياسين") { name ->
            val l = allPeople(ctx)
            if (name !in l) StrList.set(ctx, "people", l + name)
            refresh()
        }
    }, { item ->
        if (allPeople(ctx).size > 1) confirm("نمسح «$item» من اللائحة؟ (المصاريف ديالو كيبقاو)") {
            StrList.set(ctx, "people", allPeople(ctx) - item); refresh()
        }
    }))
    add.addView(Ui.text(ctx, "(ضغط مطوّل على شي اسم باش تمسحو)", 11f, Ui.MUTED))

    val note = Ui.input(ctx, "ملاحظة (اختياري) — مثلا: واجب الشهر")
    add.addView(note)
    add.addView(Ui.button(ctx, "➕ زيد", Ui.ACCENT) {
        val v = amount.text.toString().replace(',', '.').toDoubleOrNull()
        if (v == null || v <= 0) toast("كتب المبلغ")
        else {
            Charges.add(ctx, v, cat, note.text.toString().trim(), who)
            toast("تزاد ${Fmt.money(v)} — $cat — $who")
            refresh()
        }
    })
    col.addView(add)

    // ---- Statistiques du mois
    val all = Charges.list(ctx)
    val thisM = all.filter { sameMonth(it.date, 0) }
    val lastM = all.filter { sameMonth(it.date, -1) }
    val total = thisM.sumOf { it.amount }
    val lastTotal = lastM.sumOf { it.amount }
    val cal = Calendar.getInstance()
    val day = cal.get(Calendar.DAY_OF_MONTH)
    val daysInMonth = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
    val remainingDays = daysInMonth - day + 1
    val projection = if (day > 0) total / day * daysInMonth else total
    val byCat = thisM.groupBy { it.cat }.mapValues { e -> e.value.sumOf { it.amount } }
        .entries.sortedByDescending { it.value }
    val byWho = thisM.groupBy { it.who }.mapValues { e -> e.value.sumOf { it.amount } }
        .entries.sortedByDescending { it.value }

    val stats = Ui.card(ctx)
    stats.addView(Ui.text(ctx, "📊 هاد الشهر", 16f, bold = true))
    stats.addView(Ui.text(ctx, Fmt.money(total), 26f, Ui.ACCENT, true))
    stats.addView(Ui.text(ctx, "الشهر لي فات: ${Fmt.money(lastTotal)}", 13f, Ui.MUTED))
    stats.addView(Ui.text(ctx, "المعدل فاليوم: ${Fmt.money(if (day > 0) total / day else 0.0)}", 13f, Ui.MUTED))
    stats.addView(Ui.text(ctx, "على هاد الإيقاع، آخر الشهر: ${Fmt.money(projection)}", 13f, Ui.MUTED))
    if (byCat.isNotEmpty()) {
        stats.addView(Ui.gap(ctx, 8))
        stats.addView(Ui.text(ctx, "حسب الفئة", 14f, bold = true))
        byCat.forEach { (k, s) -> barLine(stats, k, s, total, Ui.ACCENT) }
    }
    if (byWho.size > 1 || (byWho.isNotEmpty() && byWho.first().key != "👤 أنا")) {
        stats.addView(Ui.gap(ctx, 4))
        stats.addView(Ui.text(ctx, "حسب الشخص", 14f, bold = true))
        byWho.forEach { (k, s) -> barLine(stats, k, s, total, Ui.BLUE) }
    }
    col.addView(stats)

    // ---- Budget
    val budget = Store.getD(ctx, "budget")
    val bc = Ui.card(ctx)
    bc.addView(Ui.text(ctx, "🎯 الميزانية ديال الشهر", 16f, bold = true))
    val bIn = Ui.input(ctx, "مثلا 5000", number = true).apply { if (budget > 0) setText(budget.toLong().toString()) }
    bc.addView(bIn)
    bc.addView(Ui.button(ctx, "حفظ الميزانية") {
        val v = bIn.text.toString().replace(',', '.').toDoubleOrNull() ?: 0.0
        Store.putD(ctx, "budget", v); toast("تحفظات"); refresh()
    })
    col.addView(bc)

    // ---- Conseils
    val tips = mutableListOf<String>()
    if (thisM.isEmpty()) {
        tips.add("زيد المصاريف ديالك كل نهار، ومن بعد غادي نعطيك إحصائيات ونصائح.")
    } else {
        if (budget > 0) {
            val left = budget - total
            if (projection > budget) {
                tips.add("⚠️ على هاد الإيقاع غادي تفوت الميزانية ب ${Fmt.money(projection - budget)}.")
                if (left > 0) tips.add("باقي ليك ${Fmt.money(left)} لـ $remainingDays يوم، يعني ما تفوتش ${Fmt.money(left / remainingDays)} فاليوم.")
                else tips.add("راك فتّي الميزانية ب ${Fmt.money(-left)}.")
            } else {
                tips.add("✅ نتا داخل الميزانية. تقدر تصرف حتى ${Fmt.money(left / remainingDays)} فاليوم.")
            }
        }
        if (lastTotal > 0) {
            val diff = ((projection - lastTotal) / lastTotal * 100).toInt()
            if (diff > 10) tips.add("📈 غادي تصرف كثر من الشهر لي فات ب $diff% تقريبا.")
            else if (diff < -10) tips.add("👏 مزيان! غادي تصرف أقل من الشهر لي فات ب ${-diff}% تقريبا.")
        }
        val top = byCat.firstOrNull()
        if (top != null && byCat.size > 1 && total > 0 && top.value / total > 0.4 && top.key != "🏠 الكرا") {
            tips.add("${top.key} واكلة ${(top.value / total * 100).toInt()}% من المصاريف. شوف واش تقدر تنقص منها شوية.")
        }
        val study = byCat.find { it.key == "📚 القراية" }?.value ?: 0.0
        if (study > 0) {
            tips.add("📚 القراية ديال الوليدات: ${Fmt.money(study)} هاد الشهر. خلي ليها بلاصة ثابتة فالميزانية، وحضّر لدخول المدرسي من دابا (مثلا ${Fmt.money(study / 4)} فالشهر على جنب).")
        }
        val coffee = byCat.find { it.key == "☕ القهوة" }?.value ?: 0.0
        if (coffee > 0) {
            val monthCoffee = coffee / day * daysInMonth
            tips.add("☕ القهوة غادي تكلفك تقريبا ${Fmt.money(monthCoffee)} هاد الشهر. إلا نقصتيها للنص، توفر ${Fmt.money(monthCoffee / 2)}.")
        }
        val credit = byCat.find { it.key == "🏦 الكريدي" }?.value ?: 0.0
        if (credit > 0 && total > 0 && credit / total > 0.3) {
            tips.add("🏦 الكريدي فايت 30% من المصاريف، حاول ما تزيدش كريدي جديد حتى يخف.")
        }
        val topWho = byWho.firstOrNull()
        if (topWho != null && byWho.size > 1 && total > 0) {
            tips.add("👥 ${topWho.key} هو لي كيتصرف عليه كثر: ${(topWho.value / total * 100).toInt()}% من المصاريف.")
        }
        tips.add("💡 نصيحة: حط على جنب 10% من المدخول أول ما تخلص، قبل ما تبدا تصرف.")
    }
    val tc = Ui.card(ctx)
    tc.addView(Ui.text(ctx, "🤖 الاقتراحات", 16f, bold = true))
    tips.forEach { tc.addView(Ui.text(ctx, "• $it", 14f).apply { setPadding(0, Ui.dp(ctx, 4), 0, Ui.dp(ctx, 4)) }) }
    col.addView(tc)

    // ---- Liste
    col.addView(Ui.text(ctx, "مصاريف هاد الشهر:", 15f, Ui.MUTED, true))
    thisM.sortedByDescending { it.date }.forEach { ch ->
        val c = Ui.card(ctx)
        val r = Ui.row(ctx)
        val info = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        info.addView(Ui.text(ctx, "${ch.cat}  —  ${Fmt.money(ch.amount)}", 15f, bold = true))
        info.addView(Ui.text(ctx, "ديال: ${ch.who}", 12f, Ui.BLUE))
        val sub = Fmt.date(ch.date) + if (ch.note.isNotBlank()) "  •  ${ch.note}" else ""
        info.addView(Ui.text(ctx, sub, 12f, Ui.MUTED))
        r.addView(info, Ui.lp(ctx, 1f, 0))
        r.addView(Ui.button(ctx, "مسح", Ui.RED) { confirm("نمسح هاد المصروف؟") { Charges.remove(ctx, ch.id); refresh() } })
        c.addView(r)
        col.addView(c)
    }
    return Ui.scroll(ctx, col)
}
