package com.tztvhelper.app.core.kb

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject

/**
 * 本地知识库。用系统自带 SQLite，不引额外依赖。
 * 表结构对应"方法 + 来源 + 验证记录 + 变更历史"四件套。
 */
class KbDatabase(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        createCoreTables(db)
        createSearchTables(db)
    }

    private fun createCoreTables(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE brands(
                id TEXT PRIMARY KEY,
                name TEXT NOT NULL,
                aliases TEXT NOT NULL DEFAULT '[]'
            )"""
        )
        db.execSQL(
            """CREATE TABLE models(
                id TEXT PRIMARY KEY,
                brand_id TEXT NOT NULL,
                model TEXT NOT NULL,
                display_name TEXT NOT NULL,
                android_versions TEXT NOT NULL DEFAULT '[]'
            )"""
        )
        db.execSQL(
            """CREATE TABLE methods(
                id TEXT PRIMARY KEY,
                brand_id TEXT NOT NULL,
                model_id TEXT,
                title TEXT NOT NULL,
                scope TEXT NOT NULL,
                os_min TEXT,
                os_max TEXT,
                steps TEXT NOT NULL DEFAULT '[]',
                notes TEXT NOT NULL DEFAULT '',
                status TEXT NOT NULL,
                confidence TEXT NOT NULL,
                success_count INTEGER NOT NULL DEFAULT 0,
                fail_count INTEGER NOT NULL DEFAULT 0,
                last_verified_at INTEGER,
                sources TEXT NOT NULL DEFAULT '[]',
                updated_at INTEGER NOT NULL DEFAULT 0
            )"""
        )
        db.execSQL(
            """CREATE TABLE verifications(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                method_id TEXT NOT NULL,
                result TEXT NOT NULL,
                os_version TEXT,
                device_model TEXT,
                reason TEXT,
                created_at INTEGER NOT NULL
            )"""
        )
        db.execSQL(
            """CREATE TABLE method_history(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                method_id TEXT NOT NULL,
                change TEXT NOT NULL,
                detail TEXT NOT NULL DEFAULT '',
                created_at INTEGER NOT NULL
            )"""
        )
        db.execSQL("CREATE INDEX idx_methods_brand ON methods(brand_id, model_id)")
        db.execSQL("CREATE INDEX idx_verifications_method ON verifications(method_id)")
    }

    /** 搜索结果缓存 + 原始来源留档（即使没转成方法，也要能追溯） */
    private fun createSearchTables(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS search_cache(
                cache_key TEXT PRIMARY KEY,
                provider TEXT NOT NULL,
                payload TEXT NOT NULL,
                is_mock INTEGER NOT NULL DEFAULT 0,
                created_at INTEGER NOT NULL,
                expires_at INTEGER NOT NULL
            )"""
        )
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS search_hits(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                cache_key TEXT NOT NULL,
                title TEXT NOT NULL DEFAULT '',
                url TEXT NOT NULL DEFAULT '',
                snippet TEXT NOT NULL DEFAULT '',
                source_type TEXT NOT NULL DEFAULT 'UNKNOWN',
                credibility TEXT NOT NULL DEFAULT 'UNVERIFIED',
                fetched_at INTEGER NOT NULL,
                is_mock INTEGER NOT NULL DEFAULT 0
            )"""
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_hits_key ON search_hits(cache_key)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // 只加表、不删数据：历史验证记录必须长期保留
        if (oldVersion < 2) createSearchTables(db)
    }

    // ---------- 搜索缓存 ----------

    fun cacheGet(key: String): CachedSearch? =
        readableDatabase.rawQuery("SELECT * FROM search_cache WHERE cache_key = ?", arrayOf(key))
            .use { c ->
                if (!c.moveToFirst()) null else CachedSearch(
                    key = c.getString(0),
                    provider = c.getString(1),
                    payload = c.getString(2),
                    isMock = c.getInt(3) == 1,
                    createdAt = c.getLong(4),
                    expiresAt = c.getLong(5)
                )
            }

    fun cachePut(c: CachedSearch) {
        val cv = ContentValues().apply {
            put("cache_key", c.key)
            put("provider", c.provider)
            put("payload", c.payload)
            put("is_mock", if (c.isMock) 1 else 0)
            put("created_at", c.createdAt)
            put("expires_at", c.expiresAt)
        }
        writableDatabase.insertWithOnConflict("search_cache", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun insertSearchHits(key: String, hits: List<SearchHitRow>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            hits.forEach { h ->
                val cv = ContentValues().apply {
                    put("cache_key", key)
                    put("title", h.title)
                    put("url", h.url)
                    put("snippet", h.snippet)
                    put("source_type", h.sourceType)
                    put("credibility", h.credibility)
                    put("fetched_at", h.fetchedAt)
                    put("is_mock", if (h.isMock) 1 else 0)
                }
                db.insert("search_hits", null, cv)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun searchHits(key: String): List<SearchHitRow> =
        readableDatabase.rawQuery(
            "SELECT title, url, snippet, source_type, credibility, fetched_at, is_mock " +
                "FROM search_hits WHERE cache_key = ? ORDER BY id",
            arrayOf(key)
        ).use { c -> c.map {
            SearchHitRow(
                it.getString(0), it.getString(1), it.getString(2), it.getString(3),
                it.getString(4), it.getLong(5), it.getInt(6) == 1
            )
        } }

    data class CachedSearch(
        val key: String,
        val provider: String,
        val payload: String,
        val isMock: Boolean,
        val createdAt: Long,
        val expiresAt: Long
    )

    data class SearchHitRow(
        val title: String,
        val url: String,
        val snippet: String,
        val sourceType: String,
        val credibility: String,
        val fetchedAt: Long,
        val isMock: Boolean
    )

    // ---------- 品牌 / 型号 ----------

    fun upsertBrand(b: Brand) {
        val cv = ContentValues().apply {
            put("id", b.id)
            put("name", b.name)
            put("aliases", JSONArray(b.aliases).toString())
        }
        writableDatabase.insertWithOnConflict("brands", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun upsertModel(m: TvModel) {
        val cv = ContentValues().apply {
            put("id", m.id)
            put("brand_id", m.brandId)
            put("model", m.model)
            put("display_name", m.displayName)
            put("android_versions", JSONArray(m.androidVersions).toString())
        }
        writableDatabase.insertWithOnConflict("models", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun brands(): List<Brand> = readableDatabase.rawQuery("SELECT * FROM brands ORDER BY name", null)
        .use { c -> c.map { Brand(it.getString(0), it.getString(1), it.jsonList(2)) } }

    fun models(brandId: String): List<TvModel> =
        readableDatabase.rawQuery(
            "SELECT * FROM models WHERE brand_id = ? ORDER BY model", arrayOf(brandId)
        ).use { c -> c.map { TvModel(it.getString(0), it.getString(1), it.getString(2), it.getString(3), it.jsonList(4)) } }

    fun model(id: String): TvModel? =
        readableDatabase.rawQuery("SELECT * FROM models WHERE id = ?", arrayOf(id))
            .use { c ->
                if (!c.moveToFirst()) null
                else TvModel(c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.jsonList(4))
            }

    fun modelIds(): Set<String> =
        readableDatabase.rawQuery("SELECT id FROM models", null).use { c -> c.map { it.getString(0) }.toSet() }

    // ---------- 方法 ----------

    fun upsertMethod(m: Method) {
        writableDatabase.insertWithOnConflict("methods", null, m.toValues(), SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun method(id: String): Method? =
        readableDatabase.rawQuery("SELECT * FROM methods WHERE id = ?", arrayOf(id))
            .use { c -> if (c.moveToFirst()) c.toMethod() else null }

    /** 取某品牌下：该型号的方法 + 品牌通用方法 */
    fun methodsFor(brandId: String, modelId: String?): List<Method> =
        readableDatabase.rawQuery(
            "SELECT * FROM methods WHERE brand_id = ? AND (model_id IS NULL OR model_id = ?)",
            arrayOf(brandId, modelId ?: "")
        ).use { c -> c.map { it.toMethod() } }

    fun methodCount(): Int =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM methods", null).use { c ->
            if (c.moveToFirst()) c.getInt(0) else 0
        }

    fun methodIds(): Set<String> =
        readableDatabase.rawQuery("SELECT id FROM methods", null).use { c -> c.map { it.getString(0) }.toSet() }

    // ---------- 验证记录 / 历史 ----------

    fun insertVerification(v: Verification) {
        val cv = ContentValues().apply {
            put("method_id", v.methodId)
            put("result", v.result.name)
            put("os_version", v.osVersion)
            put("device_model", v.deviceModel)
            put("reason", v.reason)
            put("created_at", v.createdAt)
        }
        writableDatabase.insert("verifications", null, cv)
    }

    fun verifications(methodId: String): List<Verification> =
        readableDatabase.rawQuery(
            "SELECT * FROM verifications WHERE method_id = ? ORDER BY created_at DESC",
            arrayOf(methodId)
        ).use { c -> c.map {
            Verification(
                it.getString(1), VerifyResult.valueOf(it.getString(2)),
                it.getString(3), it.getString(4), it.getString(5), it.getLong(6)
            )
        } }

    fun insertHistory(h: MethodHistory) {
        val cv = ContentValues().apply {
            put("method_id", h.methodId)
            put("change", h.change)
            put("detail", h.detail)
            put("created_at", h.createdAt)
        }
        writableDatabase.insert("method_history", null, cv)
    }

    fun history(methodId: String): List<MethodHistory> =
        readableDatabase.rawQuery(
            "SELECT * FROM method_history WHERE method_id = ? ORDER BY created_at DESC",
            arrayOf(methodId)
        ).use { c -> c.map { MethodHistory(it.getString(1), it.getString(2), it.getString(3), it.getLong(4)) } }

    // ---------- 映射 ----------

    private fun Method.toValues() = ContentValues().apply {
        put("id", id)
        put("brand_id", brandId)
        put("model_id", modelId)
        put("title", title)
        put("scope", scope)
        put("os_min", osMin)
        put("os_max", osMax)
        put("steps", stepsToJson(steps))
        put("notes", notes)
        put("status", status.name)
        put("confidence", confidence.name)
        put("success_count", successCount)
        put("fail_count", failCount)
        put("last_verified_at", lastVerifiedAt)
        put("sources", sourcesToJson(sources))
        put("updated_at", updatedAt)
    }

    private fun Cursor.toMethod(): Method = Method(
        id = getString(0),
        brandId = getString(1),
        modelId = getString(2),
        title = getString(3),
        scope = getString(4),
        osMin = getString(5),
        osMax = getString(6),
        steps = stepsFromJson(getString(7)),
        notes = getString(8),
        status = MethodStatus.valueOf(getString(9)),
        confidence = Credibility.valueOf(getString(10)),
        successCount = getInt(11),
        failCount = getInt(12),
        lastVerifiedAt = if (isNull(13)) null else getLong(13),
        sources = sourcesFromJson(getString(14)),
        updatedAt = getLong(15)
    )

    private fun Cursor.jsonList(col: Int): List<String> {
        val raw = getString(col) ?: return emptyList()
        val arr = JSONArray(raw)
        return List(arr.length()) { arr.getString(it) }
    }

    private fun stepsToJson(steps: List<TutorialStep>): String {
        val arr = JSONArray()
        steps.forEach { s ->
            arr.put(JSONObject().apply {
                put("n", s.n)
                put("title", s.title)
                put("detail", s.detail)
                put("path", JSONArray(s.path))
                put("note", s.note)
            })
        }
        return arr.toString()
    }

    private fun stepsFromJson(raw: String?): List<TutorialStep> {
        if (raw.isNullOrBlank()) return emptyList()
        val arr = JSONArray(raw)
        return List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            TutorialStep(
                o.optInt("n", i + 1),
                o.optString("title"),
                o.optString("detail"),
                o.optJSONArray("path")?.let { p -> List(p.length()) { p.getString(it) } } ?: emptyList(),
                o.optString("note")
            )
        }
    }

    private fun sourcesToJson(sources: List<SourceRef>): String {
        val arr = JSONArray()
        sources.forEach { s ->
            arr.put(JSONObject().apply {
                put("type", s.type.name)
                put("url", s.url)
                put("title", s.title)
                put("collectedAt", s.collectedAt)
                put("credibility", s.credibility.name)
            })
        }
        return arr.toString()
    }

    private fun sourcesFromJson(raw: String?): List<SourceRef> {
        if (raw.isNullOrBlank()) return emptyList()
        val arr = JSONArray(raw)
        return List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            SourceRef(
                SourceType.valueOf(o.optString("type", "UNKNOWN")),
                o.optString("url"),
                o.optString("title"),
                o.optLong("collectedAt"),
                Credibility.valueOf(o.optString("credibility", "UNVERIFIED"))
            )
        }
    }

    private inline fun <T> Cursor.map(f: (Cursor) -> T): List<T> {
        val out = ArrayList<T>(count)
        while (moveToNext()) out.add(f(this))
        return out
    }

    companion object {
        private const val DB_NAME = "kb.db"
        private const val DB_VERSION = 2
    }
}
