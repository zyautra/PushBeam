package io.github.zyautra.pushbeam.data.inbox

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

/** 받은 알림 하나 (docs/02 7.1). message_id가 같으면 한 번만 저장된다. */
@Entity(tableName = "inbox")
data class InboxEntry(
    @PrimaryKey val messageId: String,
    val title: String,
    val body: String,
    val severity: String,
    val channel: String?,
    val quiet: Boolean,
    val data: String?,
    val sentAt: String,
    val receivedAt: Long,
    val readAt: Long? = null,
    /** normal, quiet, inbox (알림 없이 목록만) */
    val display: String = "normal",
    /** display가 inbox인 이유: muted, below_min */
    val reason: String? = null,
)

@Dao
interface InboxDao {
    /** 새로 저장했으면 rowId, 이미 있으면 -1. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertIfAbsent(entry: InboxEntry): Long

    @Query("SELECT * FROM inbox ORDER BY receivedAt DESC")
    fun observeAll(): Flow<List<InboxEntry>>

    @Query("SELECT * FROM inbox WHERE messageId = :id")
    suspend fun get(id: String): InboxEntry?

    @Query("UPDATE inbox SET readAt = :at WHERE messageId = :id AND readAt IS NULL")
    suspend fun markRead(id: String, at: Long)

    @Query("UPDATE inbox SET readAt = :at WHERE readAt IS NULL")
    suspend fun markAllRead(at: Long)

    @Query("DELETE FROM inbox WHERE messageId = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM inbox")
    suspend fun deleteAll()

    @Query("DELETE FROM inbox WHERE receivedAt < :before")
    suspend fun deleteOlderThan(before: Long)
}

@Database(entities = [InboxEntry::class], version = 2, exportSchema = false)
abstract class InboxDatabase : RoomDatabase() {
    abstract fun inbox(): InboxDao

    companion object {
        fun open(context: Context): InboxDatabase =
            Room.databaseBuilder(context, InboxDatabase::class.java, "inbox.db")
                // 수신 처리는 onMessageReceived 안에서 동기로 끝내야 하므로 main thread 검사를 끈다 (docs/01 6.4).
                .allowMainThreadQueries()
                .addMigrations(MIGRATION_1_2)
                .build()

        private val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE inbox ADD COLUMN display TEXT NOT NULL DEFAULT 'normal'")
                db.execSQL("ALTER TABLE inbox ADD COLUMN reason TEXT")
                db.execSQL("UPDATE inbox SET display = 'quiet' WHERE quiet = 1")
            }
        }
    }
}
