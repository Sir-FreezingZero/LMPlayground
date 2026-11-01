package com.druk.lmplayground.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.druk.lmplayground.data.AppDatabase
import com.druk.lmplayground.data.MemoryNoteEntity
import com.druk.lmplayground.storage.StoragePreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Backs Settings -> Memory. The DAO is blocking, so every change runs on
 * Dispatchers.IO; the list is Room LiveData, delivered off the main thread.
 */
class MemoryViewModel(app: Application) : AndroidViewModel(app) {

    private val dao = AppDatabase.getInstance(app).memoryDao()

    val notes: LiveData<List<MemoryNoteEntity>> = dao.getAllLive()

    private val prefs = StoragePreferences(app)
    private val _memoryDefault = MutableLiveData(prefs.isToolEnabledDefault(MEMORY_TOOL))

    /** Whether new chats start with the memory tool (and so the notes) on. */
    val memoryDefault: LiveData<Boolean> = _memoryDefault

    fun setMemoryDefault(enabled: Boolean) {
        prefs.setToolEnabledDefault(MEMORY_TOOL, enabled)
        _memoryDefault.value = enabled
    }

    private fun io(block: () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) { block() }
    }

    fun addNote(content: String) {
        if (content.isBlank()) return
        val now = System.currentTimeMillis()
        io { dao.insert(MemoryNoteEntity(content = content.trim(), createdAt = now, updatedAt = now)) }
    }

    fun updateNote(id: Long, content: String) {
        if (content.isBlank()) return deleteNote(id)
        io {
            val note = dao.getById(id) ?: return@io
            dao.update(note.copy(content = content.trim(), updatedAt = System.currentTimeMillis()))
        }
    }

    fun deleteNote(id: Long) = io { dao.deleteById(id) }

    fun clearAll() = io { dao.deleteAll() }
}

private const val MEMORY_TOOL = "memory"
