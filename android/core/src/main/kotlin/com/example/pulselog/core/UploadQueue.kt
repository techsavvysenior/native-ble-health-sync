package com.example.pulselog.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.io.File

/** Where the queue lives between app launches. */
interface QueueStore {
    fun load(): List<Reading>
    fun save(readings: List<Reading>)
}

/**
 * Readings waiting to be uploaded, oldest first.
 *
 * - Adding a reading whose id is already queued does nothing, so a BLE indication that is
 *   re-sent after a reconnect is stored once.
 * - The queue is capped. When full, the oldest readings are dropped and counted, so a phone
 *   that stays offline for weeks can't fill its storage.
 * - A batch is removed only after the uploader acknowledges it. A crash between upload and
 *   acknowledge re-sends the batch, which is safe because the document ids are deterministic.
 */
class UploadQueue(private val store: QueueStore, private val capacity: Int = 5_000) {
    private val items = LinkedHashMap<String, Reading>()

    var dropped: Int = 0
        private set

    init {
        require(capacity > 0)
        store.load().forEach { items[it.id] = it }
    }

    val size: Int get() = items.size

    /** Returns false when the reading was already queued. */
    fun add(reading: Reading): Boolean {
        if (items.containsKey(reading.id)) return false
        items[reading.id] = reading
        while (items.size > capacity) {
            items.remove(items.keys.first())
            dropped += 1
        }
        store.save(items.values.toList())
        return true
    }

    fun nextBatch(limit: Int = 100): List<Reading> = items.values.take(limit)

    fun acknowledge(ids: Collection<String>) {
        var changed = false
        ids.forEach { if (items.remove(it) != null) changed = true }
        if (changed) store.save(items.values.toList())
    }
}

/** One JSON object per line, written to a temp file and renamed so a crash never leaves half a file. */
class FileQueueStore(private val file: File) : QueueStore {
    override fun load(): List<Reading> {
        if (!file.exists()) return emptyList()
        return file.readLines().filter { it.isNotBlank() }.mapNotNull { line ->
            runCatching {
                val o = Json.parseToJsonElement(line).jsonObject
                val kind = ReadingKind.entries.first { it.wire == o.getValue("kind").jsonPrimitive.content }
                Reading(
                    id = o.getValue("id").jsonPrimitive.content,
                    deviceId = o.getValue("deviceId").jsonPrimitive.content,
                    kind = kind,
                    receivedAtMs = o.getValue("receivedAtMs").jsonPrimitive.long,
                    payloadHex = o.getValue("payloadHex").jsonPrimitive.content,
                )
            }.getOrNull() // a damaged line is skipped, not fatal
        }
    }

    override fun save(readings: List<Reading>) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(readings.joinToString("") { r ->
            buildJsonObject {
                put("id", JsonPrimitive(r.id))
                put("deviceId", JsonPrimitive(r.deviceId))
                put("kind", JsonPrimitive(r.kind.wire))
                put("receivedAtMs", JsonPrimitive(r.receivedAtMs))
                put("payloadHex", JsonPrimitive(r.payloadHex))
            }.toString() + "\n"
        })
        if (!tmp.renameTo(file)) {
            file.delete()
            check(tmp.renameTo(file)) { "could not replace ${file.name}" }
        }
    }
}

class MemoryQueueStore(initial: List<Reading> = emptyList()) : QueueStore {
    var saved: List<Reading> = initial
        private set
    var saves = 0
        private set

    override fun load() = saved
    override fun save(readings: List<Reading>) {
        saved = readings
        saves += 1
    }
}
