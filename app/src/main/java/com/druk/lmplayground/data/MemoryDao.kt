package com.druk.lmplayground.data

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update

/**
 * DAO for the memory notes. The memory tool runs off the main thread (the
 * generation/tool loop), so its blocking queries are safe; the Memory screen
 * (MemoryViewModel) runs its changes on Dispatchers.IO and observes
 * [getAllLive], which Room delivers off the main thread.
 */
@Dao
interface MemoryDao {
    @Insert
    fun insert(note: MemoryNoteEntity): Long

    @Update
    fun update(note: MemoryNoteEntity)

    @Query("SELECT * FROM memory_notes ORDER BY createdAt DESC")
    fun getAll(): List<MemoryNoteEntity>

    /** The Memory screen's list and the chat injection: most recently edited first. */
    @Query("SELECT * FROM memory_notes ORDER BY updatedAt DESC, createdAt DESC")
    fun getAllLive(): LiveData<List<MemoryNoteEntity>>

    @Query("SELECT * FROM memory_notes WHERE id = :id")
    fun getById(id: Long): MemoryNoteEntity?

    @Query("DELETE FROM memory_notes WHERE id = :id")
    fun deleteById(id: Long): Int

    @Query("DELETE FROM memory_notes")
    fun deleteAll(): Int

    @Query("SELECT COUNT(*) FROM memory_notes")
    fun count(): Int
}
