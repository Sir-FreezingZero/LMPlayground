package com.druk.lmplayground.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A note the model saved with the "memory" tool. Persisted across chats,
 * local-only. [updatedAt] is the last edit; [category] is an optional light
 * tag the model may attach (e.g. "preferences", "projects").
 */
@Entity(tableName = "memory_notes")
data class MemoryNoteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val content: String,
    val createdAt: Long,
    val updatedAt: Long = 0L,
    val category: String? = null,
)
