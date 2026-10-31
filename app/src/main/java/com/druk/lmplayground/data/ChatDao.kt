package com.druk.lmplayground.data

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update

@Dao
interface ChatDao {

    @Query("SELECT * FROM chat_sessions ORDER BY pinned DESC, updatedAt DESC")
    fun getAllSessions(): LiveData<List<ChatSessionEntity>>

    @Query("SELECT * FROM chat_messages WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    suspend fun getMessages(sessionId: String): List<ChatMessageEntity>

    @Insert
    suspend fun insertSession(session: ChatSessionEntity)

    @Insert
    suspend fun insertMessage(message: ChatMessageEntity)

    /** Delete a session's messages from [fromTimestamp] on (edit / regenerate). */
    @Query("DELETE FROM chat_messages WHERE sessionId = :sessionId AND timestamp >= :fromTimestamp")
    suspend fun deleteMessagesFrom(sessionId: String, fromTimestamp: Long)

    @Query("UPDATE chat_sessions SET updatedAt = :updatedAt WHERE id = :sessionId")
    suspend fun updateSessionTimestamp(sessionId: String, updatedAt: Long)

    @Query("UPDATE chat_sessions SET title = :title WHERE id = :sessionId")
    suspend fun updateSessionTitle(sessionId: String, title: String)

    @Query("UPDATE chat_sessions SET modelFilename = :filename, modelName = :name WHERE id = :sessionId")
    suspend fun updateSessionModel(sessionId: String, filename: String, name: String)

    @Query("UPDATE chat_sessions SET pinned = :pinned WHERE id = :sessionId")
    suspend fun updateSessionPinned(sessionId: String, pinned: Boolean)

    @Query("""UPDATE chat_sessions SET
        contextSize = :contextSize, temperature = :temperature, topP = :topP,
        repetitionPenalty = :repetitionPenalty, topK = :topK, minP = :minP, seed = :seed,
        thinkingBudget = :thinkingBudget
        WHERE id = :sessionId""")
    suspend fun updateSessionParams(
        sessionId: String,
        contextSize: Int, temperature: Float, topP: Float,
        repetitionPenalty: Float, topK: Int, minP: Float, seed: Int,
        thinkingBudget: Int
    )

    @Query("UPDATE chat_sessions SET systemPrompt = :systemPrompt WHERE id = :sessionId")
    suspend fun updateSessionSystemPrompt(sessionId: String, systemPrompt: String)

    @Query("UPDATE chat_sessions SET metadata = :metadata WHERE id = :sessionId")
    suspend fun updateSessionMetadata(sessionId: String, metadata: String)

    /** Metadata of every session whose metadata matches [pattern] (LIKE). */
    @Query("SELECT metadata FROM chat_sessions WHERE metadata LIKE :pattern")
    suspend fun getMetadataLike(pattern: String): List<String>

    /**
     * Ids of sessions whose title or any message matches [pattern] (LIKE,
     * with '!' as the escape character).
     */
    @Query(
        """SELECT id FROM chat_sessions WHERE title LIKE :pattern ESCAPE '!'
            OR id IN (SELECT sessionId FROM chat_messages WHERE content LIKE :pattern ESCAPE '!')"""
    )
    suspend fun searchSessionIds(pattern: String): List<String>

    // ── Folders ────────────────────────────────────────────────────────

    @Query("SELECT * FROM chat_folders ORDER BY name COLLATE NOCASE, createdAt")
    fun getAllFolders(): LiveData<List<ChatFolderEntity>>

    @Insert
    suspend fun insertFolder(folder: ChatFolderEntity)

    @Query("UPDATE chat_folders SET name = :name WHERE id = :folderId")
    suspend fun updateFolderName(folderId: String, name: String)

    @Query("DELETE FROM chat_folders WHERE id = :folderId")
    suspend fun deleteFolder(folderId: String)

    /** Put a chat in a folder, or take it out with null. */
    @Query("UPDATE chat_sessions SET folderId = :folderId WHERE id = :sessionId")
    suspend fun updateSessionFolder(sessionId: String, folderId: String?)

    /** Move every chat out of [folderId] (before the folder is deleted). */
    @Query("UPDATE chat_sessions SET folderId = NULL WHERE folderId = :folderId")
    suspend fun clearFolder(folderId: String)

    @Query("SELECT * FROM chat_sessions WHERE id = :sessionId")
    suspend fun getSession(sessionId: String): ChatSessionEntity?

    @Query("DELETE FROM chat_sessions WHERE id = :sessionId")
    suspend fun deleteSession(sessionId: String)
}
