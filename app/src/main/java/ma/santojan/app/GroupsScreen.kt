package ma.santojan.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.LinearLayout
import org.json.JSONArray
import org.json.JSONObject

class Member(val name: String, val number: String)
class Group(val name: String, val members: List<Member>)

object Groups {
    private const val KEY = "groups"
    private val DEFAULT = listOf("👨‍👩‍👧 العائلة", "💼 الخدمة", "🤝 الصحاب")

    fun list(c: Context): List<Group> {
        val a = Store.arr(c, KEY)
        if (a.length() == 0 && !Store.getB(c, "groups_init", false)) {
            Store.putB(c, "groups_init", true)
            val d = DEFAULT.map { Group(it, emptyList()) }
            save(c, d); return d
        }
        return (0 until a.length()).mapNotNull { i ->
            try {
                val o = a.getJSONObject(i)
                val m = o.getJSONArray("m")
                Group(o.getString("name"), (0 until m.length()).map {
                    val x = m.getJSONObject(it); Member(x.getString("n"), x.getString("p"))
                })
            } catch (e: Exception) { null }
        }
    }

    fun save(c: Context, l: List<Group>) {
        val a = JSONArray()
        l.forEach { g ->
            val m = JSONArray()
            g.members.forEach { m.put(JSONObject().put("n", it.name).put("p", it.number)) }
            a.put(JSONObject().put("name", g.name).put("m", m))
        }
        Store.saveArr(c, KEY, a)
    }

    fun addGroup(c: Context, name: String) {
        val l = list(c)
        if (l.none { it.name == name }) save(c, l + Group(name, emptyList()))
    }

    fun removeGroup(c: Context, name: String) = save(c, list(c).filter { it.name != name })

    fun addMember(c: Context, group: String, m: Member) = save(c, list(c).map { g ->
        if (g.name == group && g.members.none { it.number == m.number }) Group(g.name, g.members + m) else g
    })

    fun removeMember(c: Context, group: String, number: String) = save(c, list(c).map { g ->
        if (g.name == group) Group(g.name, g.members.filter { it.number != number }) else g
    })
}

/** Appel direct si l'autorisation est donnée, sinon on ouvre le clavier avec le numéro. */
fun MainActivity.callNow(number: String) {
    if (has(Manifest.permission.CALL_PHONE)) {
        openSafe(Intent(Intent.ACTION_CALL, Uri.parse("tel:$number")))
    } else {
        pendingCall = number
        requestPermissions(arrayOf(Manifest.permission.CALL_PHONE), 2)
    }
}

fun MainActivity.whatsapp(number: String) {
    var n = number.filter { it.isDigit() || it == '+' }.removePrefix("+")
    if (n.startsWith("00")) n = n.drop(2)
    if (n.startsWith("0")) n = "212" + n.drop(1)
    openSafe(Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$n")))
}

fun MainActivity.pickContactFor(group: String) {
    pendingGroup = group
    try {
        @Suppress("DEPRECATION")
        startActivityForResult(Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI), 3)
    } catch (e: Exception) {
        toast("ما قدرتش نحل جهات الاتصال")
    }
}

fun MainActivity.onContactPicked(uri: Uri?) {
    val g = pendingGroup ?: return
    pendingGroup = null
    if (uri == null) return
    try {
        contentResolver.query(
            uri,
            arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
            null, null, null
        )?.use { c ->
            if (c.moveToFirst()) {
                val name = c.getString(0) ?: ""
                val num = c.getString(1) ?: ""
                if (num.isNotBlank()) {
                    Groups.addMember(this, g, Member(name.ifBlank { num }, num))
                    toast("تزاد $name فـ $g")
                }
            }
        }
    } catch (e: Exception) {
        toast("وقع مشكل: ${e.message}")
    }
    openGroups.add(g)
    refresh()
}

private fun MainActivity.memberRow(group: String?, m: Member): View {
    val ctx = this
    val r = Ui.row(ctx).apply { setPadding(0, Ui.dp(ctx, 4), 0, Ui.dp(ctx, 4)) }
    val info = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    info.addView(Ui.text(ctx, m.name, 15f, bold = true))
    info.addView(Ui.text(ctx, if (group != null) m.number else "${m.number}", 12f, Ui.MUTED))
    r.addView(info, Ui.lp(ctx, 1f, 0))
    r.addView(Ui.button(ctx, "📞", Ui.ACCENT) { callNow(m.number) }, Ui.lp(ctx))
    r.addView(Ui.button(ctx, "💬", Ui.CHIP) { whatsapp(m.number) }, Ui.lp(ctx))
    if (group != null) {
        r.setOnLongClickListener {
            confirm("نحيد ${m.name} من $group؟") { Groups.removeMember(ctx, group, m.number); refresh() }
            true
        }
    }
    return r
}

fun MainActivity.groupsScreen(): View {
    val ctx = this
    val col = Ui.column(ctx)
    col.addView(Ui.title(ctx, "👥 المجموعات"))

    if (!has(Manifest.permission.READ_CONTACTS)) {
        val c = Ui.card(ctx)
        c.addView(Ui.text(ctx, "خاصني الإذن ديال جهات الاتصال.", color = Ui.MUTED))
        c.addView(Ui.button(ctx, "عطي الإذن") { askPermissions() })
        col.addView(c)
    }

    val groups = Groups.list(ctx)

    // ---- Recherche : on tape un nom et on appelle direct
    val search = Ui.input(ctx, "🔍 قلّب على شي واحد...")
    col.addView(search)
    val results = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    col.addView(results)
    val listBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    col.addView(listBox)

    search.addTextChangedListener(object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun afterTextChanged(s: Editable?) {
            val q = s.toString().trim().lowercase()
            results.removeAllViews()
            listBox.visibility = if (q.isEmpty()) View.VISIBLE else View.GONE
            if (q.isEmpty()) return
            val seen = HashSet<String>()
            val card = Ui.card(ctx)
            groups.forEach { g ->
                g.members.filter { it.name.lowercase().contains(q) || it.number.contains(q) }.forEach { m ->
                    if (seen.add(m.number)) {
                        card.addView(memberRow(null, m))
                        card.addView(Ui.text(ctx, "فـ ${g.name}", 11f, Ui.MUTED))
                    }
                }
            }
            // Aussi dans les contacts du téléphone
            if (has(Manifest.permission.READ_CONTACTS)) {
                try {
                    contentResolver.query(
                        ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                        arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
                        ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " LIKE ?", arrayOf("%$q%"),
                        ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
                    )?.use { c ->
                        var n = 0
                        while (c.moveToNext() && n < 15) {
                            val num = c.getString(1) ?: continue
                            val key = num.filter { it.isDigit() }.takeLast(9)
                            if (seen.none { it.filter { d -> d.isDigit() }.takeLast(9) == key } && seen.add(num)) {
                                card.addView(memberRow(null, Member(c.getString(0) ?: num, num))); n++
                            }
                        }
                    }
                } catch (_: Exception) {}
            }
            if (card.childCount == 0) card.addView(Ui.text(ctx, "ما لقيت حتى واحد", 14f, Ui.MUTED))
            results.addView(card)
        }
    })

    // ---- Liste des groupes
    groups.forEach { g ->
        val card = Ui.card(ctx)
        val head = Ui.row(ctx)
        val open = g.name in openGroups
        head.addView(Ui.text(ctx, (if (open) "▾ " else "▸ ") + g.name, 17f, bold = true), Ui.lp(ctx, 1f, 0))
        head.addView(Ui.text(ctx, "${g.members.size}", 14f, Ui.MUTED))
        head.setOnClickListener {
            if (open) openGroups.remove(g.name) else openGroups.add(g.name)
            refresh()
        }
        head.setOnLongClickListener {
            confirm("نمسح المجموعة «${g.name}»؟") { Groups.removeGroup(ctx, g.name); refresh() }
            true
        }
        card.addView(head)
        if (open) {
            if (g.members.isEmpty()) card.addView(Ui.text(ctx, "ما فيها حتى واحد", 13f, Ui.MUTED))
            g.members.sortedBy { it.name }.forEach { card.addView(memberRow(g.name, it)) }
            card.addView(Ui.button(ctx, "➕ زيد من جهات الاتصال", Ui.BLUE) { pickContactFor(g.name) })
            card.addView(Ui.text(ctx, "(ضغط مطوّل على شي واحد باش تحيدو)", 11f, Ui.MUTED))
        }
        listBox.addView(card)
    }
    listBox.addView(Ui.button(ctx, "➕ مجموعة جديدة", Ui.ACCENT) {
        askText("مجموعة جديدة", "مثلا: 🏫 المدرسة") { Groups.addGroup(ctx, it); openGroups.add(it); refresh() }
    })
    listBox.addView(Ui.text(ctx, "(ضغط مطوّل على اسم المجموعة باش تمسحها)", 11f, Ui.MUTED))

    return Ui.scroll(ctx, col)
}
