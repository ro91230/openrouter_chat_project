
package com.openrouter.chat

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "chat_sessions")
data class ChatSession(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val systemPrompt: String,
    val isArchived: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "chat_messages",
    foreignKeys = [
        ForeignKey(
            entity = ChatSession::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class ChatMessage(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val role: String,
    val timestamp: Long = System.currentTimeMillis(),
    val currentVersionId: Long = 0
)

@Entity(
    tableName = "message_versions",
    foreignKeys = [
        ForeignKey(
            entity = ChatMessage::class,
            parentColumns = ["id"],
            childColumns = ["messageId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class MessageVersion(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val messageId: Long,
    val content: String,
    val versionNumber: Int,
    val timestamp: Long = System.currentTimeMillis(),
    val contextOption: String = "BRANCH" // "BRANCH" or "UNIFIED"
)

@Entity(tableName = "api_logs")
data class ApiLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val endpoint: String,
    val requestTokens: Int,
    val responseTokens: Int,
    val cost: Double,
    val status: Int,
    val timestamp: Long = System.currentTimeMillis()
)

@Entity(tableName = "model_pricing")
data class ModelPricing(
    @PrimaryKey val id: String,
    val name: String,
    val promptCost: Double,
    val completionCost: Double,
    val lastUpdated: Long = System.currentTimeMillis()
)

data class MessageWithVersions(
    @Embedded val message: ChatMessage,
    @Relation(
        parentColumn = "id",
        entityColumn = "messageId"
    )
    val versions: List<MessageVersion>
)

@Dao
interface ChatDao {
    @Insert
    suspend fun insertSession(session: ChatSession): Long

    @Update
    suspend fun updateSession(session: ChatSession)

    @Query("DELETE FROM chat_sessions WHERE id = :sessionId")
    suspend fun deleteSession(sessionId: Long)

    @Query("SELECT * FROM chat_sessions WHERE isArchived = 0 ORDER BY createdAt DESC")
    fun getActiveSessions(): Flow<List<ChatSession>>

    @Query("SELECT * FROM chat_sessions WHERE isArchived = 1 ORDER BY createdAt DESC")
    fun getArchivedSessions(): Flow<List<ChatSession>>

    @Query("SELECT * FROM chat_sessions WHERE title LIKE :query OR systemPrompt LIKE :query")
    fun searchSessions(query: String): Flow<List<ChatSession>>

    @Insert
    suspend fun insertMessage(message: ChatMessage): Long

    @Update
    suspend fun updateMessage(message: ChatMessage)

    @Query("DELETE FROM chat_messages WHERE id = :messageId")
    suspend fun deleteMessage(messageId: Long)

    @Insert
    suspend fun insertVersion(version: MessageVersion): Long

    @Query("SELECT * FROM message_versions WHERE messageId = :messageId ORDER BY versionNumber ASC")
    suspend fun getVersionsForMessage(messageId: Long): List<MessageVersion>

    @Transaction
    @Query("SELECT * FROM chat_messages WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    fun getMessagesForSession(sessionId: Long): Flow<List<MessageWithVersions>>

    @Query("""
        SELECT DISTINCT s.* FROM chat_sessions s 
        INNER JOIN chat_messages m ON s.id = m.sessionId 
        INNER JOIN message_versions v ON m.id = v.messageId 
        WHERE v.content LIKE :query
    """)
    fun searchSessionsByMessageContent(query: String): Flow<List<ChatSession>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPricing(pricingList: List<ModelPricing>)

    @Query("SELECT * FROM model_pricing ORDER BY id ASC")
    fun getAllPricing(): Flow<List<ModelPricing>>

    @Query("SELECT * FROM model_pricing WHERE id = :modelId LIMIT 1")
    suspend fun getPricingForModel(modelId: String): ModelPricing?

    @Insert
    suspend fun insertLog(log: ApiLog)

    @Query("SELECT * FROM api_logs ORDER BY timestamp DESC")
    fun getAllLogs(): Flow<List<ApiLog>>

    @Query("DELETE FROM api_logs")
    suspend fun clearLogs()
}

@Database(
    entities = [ChatSession::class, ChatMessage::class, MessageVersion::class, ApiLog::class, ModelPricing::class],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun chatDao(): ChatDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "openrouter_chat_db"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}
