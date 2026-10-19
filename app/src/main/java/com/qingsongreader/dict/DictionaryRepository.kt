package com.qingsongreader.dict

import android.content.Context
import android.net.Uri
import com.qingsongreader.OpenReaderApp
import java.io.File
import com.qingsongreader.data.db.DictEntryEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class DictSense(
    val partOfSpeech: String = "",
    val definition: String = "",
    val example: String = ""
)

data class DictEntry(
    val word: String,
    val phonetic: String = "",
    val senses: List<DictSense> = emptyList(),
    /** "local" (imported pack), "cache" (previous online lookup) or "ai". */
    val source: String = "cache",
    val audioUrl: String = ""
) {
    val plainDefinition: String
        get() = senses.joinToString("\n") { sense ->
            buildString {
                if (sense.partOfSpeech.isNotBlank()) append("[${sense.partOfSpeech}] ")
                append(sense.definition)
                if (sense.example.isNotBlank()) append("\n    例：${sense.example}")
            }
        }
}

/**
 * Dictionary lookup with three tiers:
 *   1. an offline pack the user imported (works with no network at all)
 *   2. a free, key-less online dictionary (English: dictionaryapi.dev)
 *   3. the user's own AI endpoint as a fallback (Chinese words, rare words)
 *
 * Every online result is cached into Room, so the next lookup works offline.
 */
class DictionaryRepository(private val context: Context) {

    private val db get() = OpenReaderApp.instance.database
    private val dao get() = db.dictionaryDao()

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        // The dictionary packs are 1-18 MB; a 30 s total call timeout aborted
        // mid-download on anything slower than ~600 KB/s, which is exactly what
        // users on domestic mobile networks saw ("所有下载源均不可用"). Keep a
        // generous total budget and rely on the read timeout to catch stalls.
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    fun count() = dao.count()

    fun recent() = dao.recent()

    /**
     * @param aiFallback called when offline and online sources have nothing;
     *                   supplied by the caller so this class stays free of settings.
     */
    suspend fun lookup(
        rawWord: String,
        aiFallback: (suspend (String) -> Result<String>)? = null
    ): Result<DictEntry> = withContext(Dispatchers.IO) {
        val word = rawWord.trim()
        if (word.isBlank()) return@withContext Result.failure(IllegalArgumentException("没有选中内容"))

        // 1. offline pack / cache
        val cached = dao.get(word.lowercase()) ?: dao.get(word)
        if (cached != null) {
            return@withContext Result.success(cached.toEntry())
        }

        // 2. online (Latin script only for the free dictionary)
        if (!containsCjk(word)) {
            val online = runCatching { fetchEnglish(word) }.getOrNull()
            if (online != null) {
                dao.upsert(online.toEntity("cache"))
                return@withContext Result.success(online)
            }
        }

        // 3. AI fallback
        if (aiFallback != null) {
            val explained = aiFallback(word).getOrNull()
            if (!explained.isNullOrBlank()) {
                val entry = DictEntry(
                    word = word,
                    phonetic = "",
                    senses = listOf(DictSense(partOfSpeech = "AI", definition = explained)),
                    source = "ai"
                )
                dao.upsert(entry.toEntity("cache"))
                return@withContext Result.success(entry)
            }
        }

        Result.failure(
            IllegalStateException("本地词库与在线词典都没找到「$word」。可在设置里导入离线词库，或配置 AI 后重试。")
        )
    }

    private suspend fun fetchEnglish(word: String): DictEntry {
        val request = Request.Builder()
            .url("https://api.dictionaryapi.dev/api/v2/entries/en/" + Uri.encode(word))
            .get()
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code}")
            val body = response.body?.string().orEmpty()
            if (body.isBlank()) error("empty response")
            val array = JSONArray(body)
            if (array.length() == 0) error("not found")
            val first = array.getJSONObject(0)
            val senses = ArrayList<DictSense>()
            val meanings = first.optJSONArray("meanings") ?: JSONArray()
            for (i in 0 until meanings.length()) {
                val meaning = meanings.getJSONObject(i)
                val pos = meaning.optString("partOfSpeech")
                val definitions = meaning.optJSONArray("definitions") ?: JSONArray()
                for (j in 0 until minOf(definitions.length(), 3)) {
                    val definition = definitions.getJSONObject(j)
                    senses.add(
                        DictSense(
                            partOfSpeech = pos,
                            definition = definition.optString("definition"),
                            example = definition.optString("example")
                        )
                    )
                }
            }
            var phonetic = first.optString("phonetic")
            if (phonetic.isBlank()) {
                val phonetics = first.optJSONArray("phonetics") ?: JSONArray()
                for (i in 0 until phonetics.length()) {
                    val text = phonetics.getJSONObject(i).optString("text")
                    if (text.isNotBlank()) {
                        phonetic = text
                        break
                    }
                }
            }
            var audio = ""
            val phonetics = first.optJSONArray("phonetics") ?: JSONArray()
            for (i in 0 until phonetics.length()) {
                val item = phonetics.getJSONObject(i)
                if (item.optString("audio").isNotBlank()) {
                    audio = item.optString("audio")
                    break
                }
            }
            return DictEntry(
                word = first.optString("word", word),
                phonetic = phonetic.trim('/', ' '),
                senses = senses,
                source = "online",
                audioUrl = audio
            )
        }
    }

    /** Imports an offline pack: JSON array or CSV (word,phonetic,definition). */
    suspend fun import(uri: Uri): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val text = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?.toString(Charsets.UTF_8) ?: error("无法读取文件")
            val entries = parsePack(text)
            if (entries.isEmpty()) error("文件里没有解析到词条")
            dao.upsertAll(entries.map { it.toEntity("local") })
            entries.size
        }
    }

    /**
     * Imports the built-in character table shipped in assets
     * (derived from the Unicode® Unihan database).
     */
    suspend fun importBuiltInHanzi(): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val text = context.assets.open("dict/hanzi_basic.json")
                .use { it.readBytes().toString(Charsets.UTF_8) }
            val entries = parsePack(text)
            if (entries.isEmpty()) error("内置字表为空")
            dao.upsertAll(entries.map { it.toEntity("local") })
            entries.size
        }
    }

    /**
     * Imports the built-in Chinese-English / English-Chinese dictionary
     * (CC-CEDICT, CC BY-SA 4.0). Copied out of assets first so it can be
     * streamed line by line instead of loaded into memory.
     */
    suspend fun importBuiltInCedict(onProgress: (Int) -> Unit = {}): Result<Int> =
        withContext(Dispatchers.IO) {
            runCatching {
                val temp = File(context.cacheDir, "cedict_import.tsv")
                context.assets.open("dict/cedict.tsv").use { input ->
                    temp.outputStream().use { output -> input.copyTo(output) }
                }
                val result = importCsv(temp, onProgress)
                runCatching { temp.delete() }
                result.getOrThrow()
            }
        }

    /** Streams a CSV pack (word,definition) with quoted fields and batch inserts. */
    suspend fun importCsv(source: java.io.File, onProgress: (Int) -> Unit = {}): Result<Int> =
        withContext(Dispatchers.IO) {
            runCatching {
                val batch = ArrayList<DictEntryEntity>()
                var count = 0
                csvRecords(source).forEach { record ->
                    val cells = parseCsvLine(record)
                    if (cells.size >= 2) {
                        val word = cells[0].trim()
                        val phonetic = if (cells.size >= 3) cells[1].trim() else ""
                        val definition = if (cells.size >= 3) {
                            cells.drop(2).joinToString(" ").trim()
                        } else {
                            cells[1].trim()
                        }
                        if (word.isNotBlank() && definition.isNotBlank() && word.length <= 40) {
                            batch.add(
                                DictEntry(
                                    word = word,
                                    phonetic = phonetic,
                                    senses = listOf(DictSense(definition = definition))
                                ).toEntity("local")
                            )
                            count++
                            if (batch.size >= 2000) {
                                dao.upsertAll(batch)
                                batch.clear()
                                onProgress(count)
                            }
                        }
                    }
                }
                if (batch.isNotEmpty()) dao.upsertAll(batch)
                onProgress(count)
                if (count == 0) error("没有解析到词条")
                count
            }
        }

    /** Imports a downloaded JSON pack from a local file. */
    suspend fun importJsonFile(source: java.io.File): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val text = source.readText()
            val entries = parsePack(text)
            if (entries.isEmpty()) error("没有解析到词条")
            entries.chunked(2000).forEach { chunk -> dao.upsertAll(chunk.map { it.toEntity("local") }) }
            entries.size
        }
    }

    /**
     * Yields whole CSV records: a quoted field may contain newlines, so lines
     * are joined until the double quotes balance again (ci.csv relies on this).
     */
    private fun csvRecords(file: java.io.File): Sequence<String> = sequence {
        java.io.BufferedReader(java.io.InputStreamReader(file.inputStream(), Charsets.UTF_8)).use { reader ->
            val buffer = StringBuilder()
            while (true) {
                val line = reader.readLine() ?: break
                buffer.append(line).append('\n')
                val text = buffer.toString()
                var quotes = 0
                var i = 0
                while (i < text.length) {
                    if (text[i] == '"') quotes++
                    i++
                }
                if (quotes % 2 == 0) {
                    yield(text.trimEnd('\n'))
                    buffer.setLength(0)
                }
            }
            if (buffer.isNotEmpty()) yield(buffer.toString().trimEnd('\n'))
        }
    }

    /** Splits one CSV row, honouring double quotes and escaped "". */
    fun parseCsvLine(line: String): List<String> {
        val out = ArrayList<String>()
        val current = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val ch = line[i]
            when {
                ch == '"' -> {
                    if (inQuotes && i + 1 < line.length && line[i + 1] == '"') {
                        current.append('"')
                        i++
                    } else {
                        inQuotes = !inQuotes
                    }
                }
                (ch == ',' || ch == '\t') && !inQuotes -> {
                    out.add(current.toString())
                    current.setLength(0)
                }
                else -> current.append(ch)
            }
            i++
        }
        out.add(current.toString())
        return out
    }

    fun parsePack(text: String): List<DictEntry> {
        val trimmed = text.trimStart()
        if (trimmed.startsWith("[") || trimmed.startsWith("{")) {
            return parseJson(trimmed)
        }
        return parseCsv(text)
    }

    private fun parseJson(text: String): List<DictEntry> {
        val out = ArrayList<DictEntry>()
        val array = when {
            text.trimStart().startsWith("[") -> JSONArray(text)
            else -> {
                val obj = JSONObject(text)
                if (obj.has("words")) obj.getJSONArray("words")
                else buildArrayFromObject(obj)
            }
        }
        for (i in 0 until array.length()) {
            val item = array.opt(i) ?: continue
            when (item) {
                is JSONObject -> out.add(item.toEntry())
                is String -> out.add(DictEntry(word = item))
            }
        }
        return out
    }

    private fun buildArrayFromObject(obj: JSONObject): JSONArray {
        // {"apple": "a fruit", "book": {...}} — word -> definition mapping
        val array = JSONArray()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = obj.get(key)
            val mapped = when (value) {
                is JSONObject -> value.put("word", key)
                else -> JSONObject().put("word", key).put("definition", value.toString())
            }
            array.put(mapped)
        }
        return array
    }

    private fun parseCsv(text: String): List<DictEntry> {
        val out = ArrayList<DictEntry>()
        text.lineSequence().forEach { line ->
            if (line.isBlank()) return@forEach
            val cells = if (line.contains('\t')) line.split('\t') else line.split(',')
            val word = cells.getOrNull(0)?.trim()?.trim('"').orEmpty()
            if (word.isBlank()) return@forEach
            val phonetic = cells.getOrNull(1)?.trim()?.trim('"').orEmpty()
            val definition = cells.drop(2)
                .joinToString(" ")
                .ifBlank { cells.getOrNull(1)?.trim()?.trim('"').orEmpty() }
                .trim('"')
            out.add(DictEntry(word = word, phonetic = phonetic, senses = listOf(DictSense(definition = definition))))
        }
        return out
    }

    suspend fun clearCache() = withContext(Dispatchers.IO) { dao.clearSource("cache") }

    suspend fun clearLocal() = withContext(Dispatchers.IO) { dao.clearSource("local") }

    private fun containsCjk(value: String): Boolean =
        value.any { it in '\u4e00'..'\u9fff' }

    private fun JSONObject.toEntry(): DictEntry {
        val word = first("word", "name", "ci", "term", "title")
        if (word.isBlank()) return DictEntry(word = "")
        val definition = first("definition", "meaning", "explanation", "translation", "content", "释义")
        val example = first("example", "例句")
        val pos = first("partOfSpeech", "pos", "词性")
        return DictEntry(
            word = word,
            phonetic = first("phonetic", "pinyin", "pronunciation", "拼音"),
            senses = listOf(
                DictSense(
                    partOfSpeech = pos,
                    definition = stripMarkup(definition),
                    example = stripMarkup(example)
                )
            ).filter { it.definition.isNotBlank() }
        )
    }

    private fun JSONObject.first(vararg keys: String): String {
        for (key in keys) {
            val value = optString(key)
            if (value.isNotBlank()) return value.trim()
        }
        return ""
    }

    /** Dictionary packs often store HTML; keep the visible text only. */
    private fun stripMarkup(value: String): String {
        if (value.isBlank()) return ""
        return if (value.contains('<')) {
            runCatching { Jsoup.parse(value).text() }.getOrDefault(value)
        } else {
            value
        }
    }

    private fun DictEntry.toEntity(source: String): DictEntryEntity = DictEntryEntity(
        word = word.lowercase(),
        phonetic = phonetic,
        definition = if (plainDefinition.isNotBlank()) plainDefinition else senses.firstOrNull()?.definition.orEmpty(),
        source = source,
        updatedAt = System.currentTimeMillis()
    )

    private fun DictEntryEntity.toEntry(): DictEntry = DictEntry(
        word = word,
        phonetic = phonetic,
        senses = definition.lineSequence().map { line ->
            if (line.startsWith("[")) {
                val end = line.indexOf(']')
                if (end > 0) DictSense(
                    partOfSpeech = line.substring(1, end),
                    definition = line.substring(end + 1).trim()
                ) else DictSense(definition = line)
            } else {
                DictSense(definition = line)
            }
        }.filter { it.definition.isNotBlank() }.toList()
            .ifEmpty { listOf(DictSense(definition = definition)) },
        source = source
    )
}
