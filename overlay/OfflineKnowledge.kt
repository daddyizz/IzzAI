package com.example.llama

import android.content.Context
import org.json.JSONObject
import java.util.Locale

/**
 * Small, auditable offline retrieval index.
 * All facts and sources come from the bundled JSON, not model memory.
 * Retrieval is intentionally conservative: each independent anchor group
 * must match. It does not claim to verify current schedules or prices.
 */
object OfflineKnowledge {
    data class Evidence(val id: String, val fact: String, val source: String, val checked: String)

    fun retrieve(context: Context, question: String): Evidence? = runCatching {
        val q = question.lowercase(Locale.ROOT).replace(Regex("\\s+"), " ").trim()
        val doc = context.assets.open("izzai_knowledge.json").bufferedReader().use { it.readText() }
        val entries = JSONObject(doc).getJSONArray("facts")
        for (i in 0 until entries.length()) {
            val item = entries.getJSONObject(i)
            val groups = item.getJSONArray("anchor_groups")
            var matched = true
            for (g in 0 until groups.length()) {
                val variants = groups.getJSONArray(g)
                var groupMatched = false
                for (j in 0 until variants.length()) {
                    if (q.contains(variants.getString(j).lowercase(Locale.ROOT))) {
                        groupMatched = true
                        break
                    }
                }
                if (!groupMatched) {
                    matched = false
                    break
                }
            }
            if (matched) {
                return@runCatching Evidence(
                    item.getString("id"),
                    item.getString("fact"),
                    item.getString("source"),
                    item.getString("checked")
                )
            }
        }
        null
    }.getOrNull()

    fun instruction(evidence: Evidence): String =
        "\n\nFAKTA RUJUKAN OFFLINE YANG DISAHKAN (disimpan dalam aplikasi):" +
        "\n" + evidence.fact +
        "\nSumber: " + evidence.source +
        "\nDisemak: " + evidence.checked +
        "\nUtamakan fakta rujukan ini berbanding tekaan model atau jawapan AI terdahulu. " +
        "Jawab dengan nama perkhidmatan/tempat yang tepat apabila relevan. " +
        "Jangan reka jadual, harga atau status operasi langsung. " +
        "Jika soalan tidak menyebut lokasi bertolak, jelaskan syarat laluan yang dirujuk."
}
