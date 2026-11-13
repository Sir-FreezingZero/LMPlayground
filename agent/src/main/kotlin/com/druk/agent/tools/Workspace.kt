package com.druk.agent.tools

import com.druk.agent.memory.Text
import com.druk.agent.store.ProjectStore
import java.io.File

/**
 * A project's private directory. All agent file access goes through here:
 * paths are resolved relative to [root] and anything that would escape it
 * (`..`, absolute paths, symlinks out) is refused. The agent never sees the
 * user's shared storage, other projects, or the app's databases.
 */
class Workspace(val root: File, val maxFileBytes: Long = 2L * 1024 * 1024, val maxTotalBytes: Long = 200L * 1024 * 1024) {

    init { root.mkdirs() }

    class AccessDenied(msg: String) : Exception(msg)

    fun resolve(path: String): File {
        val clean = path.trim().trim('"', '\'', '`').replace('\\', '/').removePrefix("./").removePrefix("workspace/")
        if (clean.isEmpty()) throw AccessDenied("empty path")
        if (clean.startsWith("/")) throw AccessDenied("absolute paths are not allowed: $path")
        val f = File(root, clean).canonicalFile
        val base = root.canonicalFile
        if (f != base && !f.path.startsWith(base.path + File.separator)) throw AccessDenied("path escapes the project workspace: $path")
        return f
    }

    fun relative(f: File): String = f.canonicalFile.relativeTo(root.canonicalFile).path.replace(File.separatorChar, '/')

    fun exists(path: String) = runCatching { resolve(path).isFile }.getOrDefault(false)

    fun read(path: String): String {
        val f = resolve(path)
        if (!f.isFile) throw java.io.FileNotFoundException("no such file: $path")
        return f.readText()
    }

    fun write(path: String, content: String): File {
        val bytes = content.toByteArray()
        if (bytes.size > maxFileBytes) throw AccessDenied("file too large (${bytes.size} bytes, limit $maxFileBytes)")
        val f = resolve(path)
        val existing = if (f.isFile) f.length() else 0
        if (totalBytes() - existing + bytes.size > maxTotalBytes) throw AccessDenied("workspace quota exceeded")
        ProjectStore.atomicWrite(f, bytes)
        return f
    }

    fun delete(path: String): Boolean = resolve(path).let { it.isFile && it.delete() }

    fun move(from: String, to: String) {
        val a = resolve(from); val b = resolve(to)
        if (!a.isFile) throw java.io.FileNotFoundException("no such file: $from")
        b.parentFile?.mkdirs()
        if (!a.renameTo(b)) throw java.io.IOException("move failed")
    }

    fun list(): List<Pair<String, Long>> = root.walkTopDown().filter { it.isFile && !it.name.startsWith(".") }
        .map { relative(it) to it.length() }.sortedBy { it.first }.toList()

    fun totalBytes(): Long = root.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    fun hash(path: String): String? = runCatching { resolve(path).takeIf { it.isFile }?.let { Text.sha256(it.readBytes()) } }.getOrNull()
}
