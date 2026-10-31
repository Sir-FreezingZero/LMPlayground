package com.druk.lmplayground.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/** A folder in the chat list. Chats point at it through [ChatSessionEntity.folderId]. */
@Entity(tableName = "chat_folders")
data class ChatFolderEntity(
    @PrimaryKey val id: String,
    val name: String,
    val createdAt: Long,
)
