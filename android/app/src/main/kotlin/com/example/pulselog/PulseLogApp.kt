package com.example.pulselog

import android.app.Application
import android.content.Context
import com.example.pulselog.ble.ConnectionController
import com.example.pulselog.core.FileQueueStore
import com.example.pulselog.core.HeartRateSampler
import com.example.pulselog.core.Reading
import com.example.pulselog.core.ReadingKind
import com.example.pulselog.core.UploadQueue
import com.example.pulselog.sync.FirestoreUploader
import com.example.pulselog.sync.SyncLoop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope

class PulseLogApp : Application() {
    val graph: AppGraph by lazy { AppGraph(this) }
}

/** The app's objects, wired by hand. Small enough that a DI framework would cost more than it saves. */
class AppGraph(context: Context) {
    private val scope = MainScope()
    private val queueDispatcher = Dispatchers.IO.limitedParallelism(1)
    private val sampler = HeartRateSampler(intervalMs = 5_000)

    val sync = SyncLoop(
        context = context,
        scope = scope,
        queue = UploadQueue(FileQueueStore(context.filesDir.resolve("queue/pending.jsonl"))),
        uploader = FirestoreUploader.createOrNull(context),
        queueDispatcher = queueDispatcher,
    )

    val controller = ConnectionController(context, scope, onPayload = { deviceId, kind, payload ->
        val now = System.currentTimeMillis()
        // Every blood pressure reading is kept. Heart rate is thinned to one value per interval.
        if (kind == ReadingKind.BLOOD_PRESSURE || sampler.shouldKeep(deviceId, now)) {
            sync.add(Reading.from(deviceId, kind, now, payload))
        }
    })

    init {
        controller.start()
        sync.kick()
    }
}
