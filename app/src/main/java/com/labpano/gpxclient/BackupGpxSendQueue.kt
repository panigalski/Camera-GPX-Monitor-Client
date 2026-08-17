package com.labpano.gpxclient

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Locale

/** Persistent queue of Client-generated per-video GPX files awaiting manual camera upload. */
object BackupGpxSendQueue {
    data class Entry(
        val id: String,
        val dateFolder: String,
        val fileName: String,
        val documentUri: String,
        val sizeBytes: Long,
        val lastModified: Long,
        val sha256: String,
        val createdAt: Long
    )

    private val lock = Any()
    private val dateFolderRegex = Regex("^(0[1-9]|[12][0-9]|3[01])-(0[1-9]|1[0-2])-20[0-9]{2}$")
    private val backupFileRegex = Regex("^.+_backup(?: \\(\\d+\\))?\\.gpx$", RegexOption.IGNORE_CASE)

    fun enqueue(
        context: Context,
        dateFolder: String,
        fileName: String,
        uri: Uri,
        sizeBytes: Long,
        lastModified: Long = queryLastModified(context, uri),
        sha256: String = sha256(context, uri)
    ) {
        val entry = buildEntry(dateFolder, fileName, uri, sizeBytes, lastModified, sha256) ?: return
        synchronized(lock) {
            val prefs = context.getSharedPreferences(BackupGpsService.PREFS, Context.MODE_PRIVATE)
            val sent = prefs.getStringSet(KEY_SENT_IDS, emptySet()).orEmpty()
            if (entry.id in sent) return
            val pending = readPending(prefs.getString(KEY_PENDING_JSON, "[]").orEmpty()).toMutableList()
            mergeEntry(pending, entry)
            writePending(prefs, pending)
        }
    }

    fun pending(context: Context): List<Entry> = synchronized(lock) {
        val prefs = context.getSharedPreferences(BackupGpsService.PREFS, Context.MODE_PRIVATE)
        readPending(prefs.getString(KEY_PENDING_JSON, "[]").orEmpty())
    }

    fun pendingCount(context: Context): Int = pending(context).size

    fun markSent(context: Context, id: String) = synchronized(lock) {
        val prefs = context.getSharedPreferences(BackupGpsService.PREFS, Context.MODE_PRIVATE)
        val pending = readPending(prefs.getString(KEY_PENDING_JSON, "[]").orEmpty()).filterNot { it.id == id }
        val sent = LinkedHashSet(prefs.getStringSet(KEY_SENT_IDS, emptySet()).orEmpty())
        sent.remove(id)
        sent.add(id)
        while (sent.size > MAX_SENT_IDS) sent.remove(sent.first())
        prefs.edit()
            .putString(KEY_PENDING_JSON, encode(pending).toString())
            .putStringSet(KEY_SENT_IDS, sent)
            .apply()
    }

    fun removeMissing(context: Context, id: String) = synchronized(lock) {
        val prefs = context.getSharedPreferences(BackupGpsService.PREFS, Context.MODE_PRIVATE)
        val pending = readPending(prefs.getString(KEY_PENDING_JSON, "[]").orEmpty()).filterNot { it.id == id }
        writePending(prefs, pending)
    }

    /** One-time/occasional discovery makes pre-1.10.29 backup files available to the manual sender. */
    fun discoverExisting(context: Context) {
        val prefs = context.getSharedPreferences(BackupGpsService.PREFS, Context.MODE_PRIVATE)
        val treeText = prefs.getString(BackupGpsService.KEY_FOLDER, null).orEmpty()
        if (treeText.isBlank()) return
        val tree = runCatching { Uri.parse(treeText) }.getOrNull() ?: return
        val grant = context.contentResolver.persistedUriPermissions.firstOrNull { it.uri == tree }
        if (grant == null || !grant.isReadPermission) return
        val root = runCatching {
            DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        }.getOrNull() ?: return

        val discovered = mutableListOf<Entry>()
        listChildren(context, root).filter { it.isDirectory && dateFolderRegex.matches(it.name) }.forEach { folder ->
            listChildren(context, folder.uri)
                .filter { !it.isDirectory && backupFileRegex.matches(it.name) }
                .forEach { file ->
                    val digest = sha256(context, file.uri)
                    buildEntry(folder.name, file.name, file.uri, file.sizeBytes, file.lastModified, digest)?.let(discovered::add)
                }
        }
        if (discovered.isEmpty()) return
        synchronized(lock) {
            val currentPrefs = context.getSharedPreferences(BackupGpsService.PREFS, Context.MODE_PRIVATE)
            val sent = currentPrefs.getStringSet(KEY_SENT_IDS, emptySet()).orEmpty()
            val pending = readPending(currentPrefs.getString(KEY_PENDING_JSON, "[]").orEmpty()).toMutableList()
            discovered.asSequence().filterNot { it.id in sent }.forEach { mergeEntry(pending, it) }
            writePending(currentPrefs, pending)
        }
    }

    private fun buildEntry(
        dateFolder: String,
        fileName: String,
        uri: Uri,
        sizeBytes: Long,
        lastModified: Long,
        sha256: String
    ): Entry? {
        if (!dateFolderRegex.matches(dateFolder) || !backupFileRegex.matches(fileName)) return null
        val normalizedSha = sha256.lowercase(Locale.US)
        if (!normalizedSha.matches(SHA256_REGEX)) return null
        val id = fingerprint(dateFolder, fileName, uri.toString(), normalizedSha)
        return Entry(
            id = id,
            dateFolder = dateFolder,
            fileName = fileName,
            documentUri = uri.toString(),
            sizeBytes = sizeBytes.coerceAtLeast(0L),
            lastModified = lastModified.coerceAtLeast(0L),
            sha256 = normalizedSha,
            createdAt = System.currentTimeMillis()
        )
    }

    private fun mergeEntry(pending: MutableList<Entry>, entry: Entry) {
        pending.removeAll {
            it.id == entry.id ||
                (it.dateFolder == entry.dateFolder && it.fileName == entry.fileName && it.documentUri == entry.documentUri)
        }
        pending += entry
        while (pending.size > MAX_PENDING) pending.removeAt(0)
    }

    private data class Child(
        val uri: Uri,
        val name: String,
        val isDirectory: Boolean,
        val sizeBytes: Long,
        val lastModified: Long
    )

    private fun listChildren(context: Context, parent: Uri): List<Child> {
        val resolver = context.contentResolver
        val parentId = runCatching { DocumentsContract.getDocumentId(parent) }.getOrNull() ?: return emptyList()
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(parent, parentId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED
        )
        return runCatching {
            resolver.query(children, projection, null, null, null)?.use { cursor ->
                val out = mutableListOf<Child>()
                val idIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                val sizeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
                val modifiedIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                while (cursor.moveToNext()) {
                    if (idIndex < 0 || nameIndex < 0) continue
                    val name = cursor.getString(nameIndex) ?: continue
                    val uri = DocumentsContract.buildDocumentUriUsingTree(parent, cursor.getString(idIndex))
                    val mime = if (mimeIndex >= 0 && !cursor.isNull(mimeIndex)) cursor.getString(mimeIndex) else null
                    out += Child(
                        uri = uri,
                        name = name,
                        isDirectory = mime == DocumentsContract.Document.MIME_TYPE_DIR,
                        sizeBytes = if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) cursor.getLong(sizeIndex) else 0L,
                        lastModified = if (modifiedIndex >= 0 && !cursor.isNull(modifiedIndex)) cursor.getLong(modifiedIndex) else 0L
                    )
                }
                out
            } ?: emptyList()
        }.getOrDefault(emptyList())
    }

    private fun queryLastModified(context: Context, uri: Uri): Long = runCatching {
        context.contentResolver.query(
            uri,
            arrayOf(DocumentsContract.Document.COLUMN_LAST_MODIFIED),
            null,
            null,
            null
        )?.use { cursor ->
            if (!cursor.moveToFirst()) 0L else {
                val index = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                if (index >= 0 && !cursor.isNull(index)) cursor.getLong(index) else 0L
            }
        } ?: 0L
    }.getOrDefault(0L)

    private fun sha256(context: Context, uri: Uri): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val input = context.contentResolver.openInputStream(uri) ?: return ""
        input.use { stream ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(Locale.US, it.toInt() and 0xff) }
    }

    private fun fingerprint(dateFolder: String, fileName: String, uri: String, sha256: String): String =
        listOf(dateFolder, fileName, uri, sha256)
            .joinToString("|")
            .lowercase(Locale.US)

    private fun readPending(raw: String): List<Entry> {
        val array = runCatching { JSONArray(raw.ifBlank { "[]" }) }.getOrElse { JSONArray() }
        return (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val entry = Entry(
                id = item.optString("id"),
                dateFolder = item.optString("dateFolder"),
                fileName = item.optString("fileName"),
                documentUri = item.optString("documentUri"),
                sizeBytes = item.optLong("sizeBytes").coerceAtLeast(0L),
                lastModified = item.optLong("lastModified").coerceAtLeast(0L),
                sha256 = item.optString("sha256").lowercase(Locale.US),
                createdAt = item.optLong("createdAt").coerceAtLeast(0L)
            )
            if (
                entry.id.isBlank() || !dateFolderRegex.matches(entry.dateFolder) ||
                !backupFileRegex.matches(entry.fileName) || entry.documentUri.isBlank() ||
                !entry.sha256.matches(SHA256_REGEX)
            ) null else entry
        }.distinctBy { it.id }.takeLast(MAX_PENDING)
    }

    private fun encode(entries: List<Entry>): JSONArray = JSONArray().apply {
        entries.takeLast(MAX_PENDING).forEach { entry ->
            put(JSONObject().apply {
                put("id", entry.id)
                put("dateFolder", entry.dateFolder)
                put("fileName", entry.fileName)
                put("documentUri", entry.documentUri)
                put("sizeBytes", entry.sizeBytes)
                put("lastModified", entry.lastModified)
                put("sha256", entry.sha256)
                put("createdAt", entry.createdAt)
            })
        }
    }

    private fun writePending(prefs: android.content.SharedPreferences, entries: List<Entry>) {
        prefs.edit().putString(KEY_PENDING_JSON, encode(entries).toString()).apply()
    }

    private val SHA256_REGEX = Regex("^[0-9a-f]{64}$")
    private const val KEY_PENDING_JSON = "backup_gpx_camera_send_pending_v1"
    private const val KEY_SENT_IDS = "backup_gpx_camera_send_sent_ids_v1"
    private const val MAX_PENDING = 6_000
    private const val MAX_SENT_IDS = 12_000
}
