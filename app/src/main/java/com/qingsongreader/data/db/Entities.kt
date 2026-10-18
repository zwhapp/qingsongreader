package com.qingsongreader.data.db

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Update
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

enum class BookFormat { EPUB, PDF, TXT, MD, MOBI, AZW3 }

@Entity(tableName = "books")
data class BookEntity(
    @androidx.room.PrimaryKey val id: String,
    val title: String,
    val author: String = "",
    val format: String = BookFormat.EPUB.name,
    /** Absolute path of the imported book inside app-private storage. */
    val filePath: String = "",
    /** Absolute path of the extracted cover, may be empty. */
    val coverPath: String = "",
    val addedAt: Long = System.currentTimeMillis(),
    val lastReadAt: Long = 0L,
    /** Last known chapter (or PDF page) index. */
    val chapterIndex: Int = 0,
    /** Last known page index inside the chapter. */
    val pageIndex: Int = 0,
    /** 0.0 - 1.0 */
    val progress: Float = 0f,
    /** Cached total character count, used for global progress estimation. */
    val totalChars: Long = 0L
)

@Entity(tableName = "highlights")
data class HighlightEntity(
    @androidx.room.PrimaryKey val id: String,
    val bookId: String,
    val chapterIndex: Int,
    val startOffset: Int,
    val endOffset: Int,
    val text: String,
    val color: String = "#FFE082",
    val note: String = "",
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "bookmarks")
data class BookmarkEntity(
    @androidx.room.PrimaryKey val id: String,
    val bookId: String,
    val chapterIndex: Int,
    val offset: Int,
    val label: String = "",
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "sessions")
data class SessionEntity(
    @androidx.room.PrimaryKey val id: String,
    val bookId: String,
    val startedAt: Long,
    val endedAt: Long = 0L,
    val durationMs: Long = 0L,
    val pages: Int = 0
)

@Dao
interface BookDao {
    @Query("SELECT * FROM books ORDER BY lastReadAt DESC, addedAt DESC")
    fun observeAll(): Flow<List<BookEntity>>

    @Query("SELECT * FROM books WHERE id = :id")
    suspend fun get(id: String): BookEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(book: BookEntity)

    @Update
    suspend fun update(book: BookEntity)

    @Query("DELETE FROM books WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT COUNT(*) FROM books")
    suspend fun count(): Int

    @Query("SELECT * FROM books")
    suspend fun all(): List<BookEntity>
}

@Dao
interface AnnotationDao {
    @Query("SELECT * FROM highlights WHERE bookId = :bookId ORDER BY chapterIndex ASC, startOffset ASC")
    fun observeHighlights(bookId: String): Flow<List<HighlightEntity>>

    @Query("SELECT * FROM highlights WHERE bookId = :bookId")
    suspend fun highlightsOf(bookId: String): List<HighlightEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(h: HighlightEntity)

    @Query("DELETE FROM highlights WHERE id = :id")
    suspend fun deleteHighlight(id: String)

    @Query("SELECT * FROM bookmarks WHERE bookId = :bookId ORDER BY chapterIndex ASC, offset ASC")
    fun observeBookmarks(bookId: String): Flow<List<BookmarkEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(b: BookmarkEntity)

    @Query("DELETE FROM bookmarks WHERE id = :id")
    suspend fun deleteBookmark(id: String)

    @Query("SELECT * FROM highlights")
    suspend fun allHighlights(): List<HighlightEntity>

    @Query("SELECT * FROM bookmarks")
    suspend fun allBookmarks(): List<BookmarkEntity>
}

/** Offline dictionary entry: imported by the user or cached from a lookup. */
@Entity(tableName = "dict_entries")
data class DictEntryEntity(
    @androidx.room.PrimaryKey val word: String,
    val phonetic: String = "",
    val definition: String = "",
    /** "local" for imported packs, "cache" for previous online lookups. */
    val source: String = "cache",
    val updatedAt: Long = System.currentTimeMillis()
)

@Dao
interface DictionaryDao {
    @Query("SELECT * FROM dict_entries WHERE word = :word")
    suspend fun get(word: String): DictEntryEntity?

    @Query("SELECT * FROM dict_entries WHERE word LIKE '%' || :q || '%' LIMIT 30")
    fun search(q: String): Flow<List<DictEntryEntity>>

    @Query("SELECT * FROM dict_entries ORDER BY updatedAt DESC LIMIT 30")
    fun recent(): Flow<List<DictEntryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: DictEntryEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entries: List<DictEntryEntity>)

    @Query("DELETE FROM dict_entries WHERE source = :source")
    suspend fun clearSource(source: String)

    @Query("SELECT COUNT(*) FROM dict_entries")
    fun count(): Flow<Int>
}

@Dao
interface StatsDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(s: SessionEntity)

    @Query("SELECT * FROM sessions WHERE bookId = :bookId ORDER BY startedAt DESC")
    fun observeSessions(bookId: String): Flow<List<SessionEntity>>

    @Query("SELECT * FROM sessions ORDER BY startedAt DESC LIMIT 500")
    fun observeRecent(): Flow<List<SessionEntity>>

    @Query("SELECT SUM(durationMs) FROM sessions")
    fun totalDuration(): Flow<Long?>

    @Query("SELECT SUM(durationMs) FROM sessions WHERE startedAt >= :from")
    fun durationSince(from: Long): Flow<Long?>
}

@Database(
    entities = [
        BookEntity::class,
        HighlightEntity::class,
        BookmarkEntity::class,
        SessionEntity::class,
        DictEntryEntity::class
    ],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun bookDao(): BookDao
    abstract fun annotationDao(): AnnotationDao
    abstract fun statsDao(): StatsDao
    abstract fun dictionaryDao(): DictionaryDao

    companion object {
        /** Adds the dictionary table introduced with the lookup feature. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `dict_entries` (" +
                        "`word` TEXT NOT NULL, " +
                        "`phonetic` TEXT NOT NULL, " +
                        "`definition` TEXT NOT NULL, " +
                        "`source` TEXT NOT NULL, " +
                        "`updatedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`word`))"
                )
            }
        }
    }
}
