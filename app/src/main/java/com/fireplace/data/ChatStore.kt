package com.fireplace.data

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import java.io.File

/**
 * Offline chat-history store. Persists conversations and their messages to a
 * local SQLite database (`<filesDir>/chats.db`) using the androidx.sqlite
 * bundled driver, so chat history survives app restarts. This app is fully
 * offline: nothing here touches the network or any analytics - it is a plain
 * on-device store (bundled SQLite driver).
 *
 * One [SQLiteConnection] is opened in the constructor and kept for the store's
 * lifetime; call [close] when done. All public methods are [Synchronized] since
 * a single connection is shared across threads (UI + streaming).
 */

data class Conversation(
    val id: String,
    val title: String,
    val agentId: String,
    val createdAt: Long,
    val updatedAt: Long,
)

data class StoredMessage(
    val id: String,
    val convId: String,
    val role: String,          // "user" | "assistant"
    val text: String,
    val sourcesJson: String,   // JSON array string of sources, or "[]"
    val passRate: Double?,     // citation pass rate, nullable
    val ts: Long,
)

class ChatStore(context: android.content.Context) {

    // Single long-lived connection to <filesDir>/chats.db.
    private val conn: SQLiteConnection =
        BundledSQLiteDriver().open(File(context.filesDir, "chats.db").absolutePath)

    init {
        // Create schema if missing (idempotent across restarts).
        exec("CREATE TABLE IF NOT EXISTS conversations(id TEXT PRIMARY KEY, title TEXT, agent_id TEXT, created_at INTEGER, updated_at INTEGER)")
        exec("CREATE TABLE IF NOT EXISTS messages(id TEXT PRIMARY KEY, conv_id TEXT, role TEXT, text TEXT, sources_json TEXT, pass_rate REAL, ts INTEGER)")
        exec("CREATE INDEX IF NOT EXISTS idx_messages_conv ON messages(conv_id)")
    }

    /** Conversations, newest [Conversation.updatedAt] first. */
    @Synchronized
    fun listConversations(): List<Conversation> {
        val out = ArrayList<Conversation>()
        val s = conn.prepare("SELECT id, title, agent_id, created_at, updated_at FROM conversations ORDER BY updated_at DESC")
        try {
            while (s.step()) {
                out.add(Conversation(s.getText(0), s.getText(1), s.getText(2), s.getLong(3), s.getLong(4)))
            }
        } finally { s.close() }
        return out
    }

    /** Create an empty conversation; created == updated == now. Returns its new UUID. */
    @Synchronized
    fun createConversation(agentId: String, title: String): String {
        val id = newId(); val now = now()
        val s = conn.prepare("INSERT INTO conversations(id, title, agent_id, created_at, updated_at) VALUES(?,?,?,?,?)")
        try {
            s.bindText(1, id); s.bindText(2, title); s.bindText(3, agentId); s.bindLong(4, now); s.bindLong(5, now)
            s.step()
        } finally { s.close() }
        return id
    }

    @Synchronized
    fun renameConversation(id: String, title: String) {
        val s = conn.prepare("UPDATE conversations SET title=? WHERE id=?")
        try { s.bindText(1, title); s.bindText(2, id); s.step() } finally { s.close() }
    }

    /** Delete a conversation and all of its messages. */
    @Synchronized
    fun deleteConversation(id: String) {
        val m = conn.prepare("DELETE FROM messages WHERE conv_id=?")
        try { m.bindText(1, id); m.step() } finally { m.close() }
        val c = conn.prepare("DELETE FROM conversations WHERE id=?")
        try { c.bindText(1, id); c.step() } finally { c.close() }
    }

    /** Messages for a conversation, oldest [StoredMessage.ts] first. */
    @Synchronized
    fun messages(convId: String): List<StoredMessage> {
        val out = ArrayList<StoredMessage>()
        val s = conn.prepare("SELECT id, conv_id, role, text, sources_json, pass_rate, ts FROM messages WHERE conv_id=? ORDER BY ts ASC")
        try {
            s.bindText(1, convId)
            while (s.step()) {
                val passRate = if (s.isNull(5)) null else s.getDouble(5)
                out.add(StoredMessage(s.getText(0), s.getText(1), s.getText(2), s.getText(3), s.getText(4), passRate, s.getLong(6)))
            }
        } finally { s.close() }
        return out
    }

    /** Append a message (ts == now), bump the conversation's updatedAt, return its new UUID. */
    @Synchronized
    fun addMessage(convId: String, role: String, text: String, sourcesJson: String = "[]", passRate: Double? = null): String {
        val id = newId()
        val s = conn.prepare("INSERT INTO messages(id, conv_id, role, text, sources_json, pass_rate, ts) VALUES(?,?,?,?,?,?,?)")
        try {
            s.bindText(1, id); s.bindText(2, convId); s.bindText(3, role); s.bindText(4, text); s.bindText(5, sourcesJson)
            if (passRate == null) s.bindNull(6) else s.bindDouble(6, passRate)
            s.bindLong(7, now())
            s.step()
        } finally { s.close() }
        touch(convId)
        return id
    }

    /** Update a message in place (used for streaming updates + the final write); bumps its conversation's updatedAt. */
    @Synchronized
    fun updateMessage(id: String, text: String, sourcesJson: String = "[]", passRate: Double? = null) {
        val s = conn.prepare("UPDATE messages SET text=?, sources_json=?, pass_rate=? WHERE id=?")
        try {
            s.bindText(1, text); s.bindText(2, sourcesJson)
            if (passRate == null) s.bindNull(3) else s.bindDouble(3, passRate)
            s.bindText(4, id)
            s.step()
        } finally { s.close() }
        // Bump the owning conversation without needing its id passed in.
        val t = conn.prepare("UPDATE conversations SET updated_at=? WHERE id=(SELECT conv_id FROM messages WHERE id=?)")
        try { t.bindLong(1, now()); t.bindText(2, id); t.step() } finally { t.close() }
    }

    /** Mark a conversation as just-touched (updatedAt == now). */
    @Synchronized
    fun touch(convId: String) {
        val s = conn.prepare("UPDATE conversations SET updated_at=? WHERE id=?")
        try { s.bindLong(1, now()); s.bindText(2, convId); s.step() } finally { s.close() }
    }

    @Synchronized
    fun close() = conn.close()

    // --- helpers ---

    /** Run a one-shot DDL/statement. */
    private fun exec(sql: String) = conn.prepare(sql).use { it.step() }

    private fun newId() = java.util.UUID.randomUUID().toString()
    private fun now() = System.currentTimeMillis()
}
