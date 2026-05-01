package com.echoai.util

import android.content.Context

object YamnetLabelLoader {
    fun loadAll(context: Context): List<String> = try {
        context.assets.open("yamnet_class_map.csv").bufferedReader().use { reader ->
            reader.lineSequence()
                .drop(1) // skip header: index,mid,display_name
                .mapNotNull { parseDisplayName(it) }
                .toList()
        }
    } catch (e: Exception) {
        emptyList()
    }

    // format: 0,/m/09x0r,Speech  or  1,/m/0ytgt,"Child speech, kid speaking"
    private fun parseDisplayName(line: String): String? {
        val firstComma = line.indexOf(',')
        if (firstComma < 0) return null
        val rest = line.substring(firstComma + 1)
        val secondComma = rest.indexOf(',')
        if (secondComma < 0) return null
        val raw = rest.substring(secondComma + 1).trim()
        return raw.trimStart('"').trimEnd('"').takeIf { it.isNotBlank() }
    }
}
