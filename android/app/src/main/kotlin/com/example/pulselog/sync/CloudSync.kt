package com.example.pulselog.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import com.example.pulselog.BuildConfig
import com.example.pulselog.core.Reading
import com.example.pulselog.core.UploadQueue
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.CancellationException

/** Writes readings to users/{uid}/readings/{readingId}. Shape and limits are enforced by firebase/firestore.rules. */
class FirestoreUploader private constructor(app: FirebaseApp) {
    private val auth = FirebaseAuth.getInstance(app)
    private val db = FirebaseFirestore.getInstance(app)

    val projectId: String = app.options.projectId.orEmpty()

    suspend fun upload(batch: List<Reading>) {
        val uid = auth.currentUser?.uid ?: requireNotNull(auth.signInAnonymously().await().user).uid
        val readings = db.collection("users").document(uid).collection("readings")
        val write = db.batch()
        // Same id, same data: a retried batch rewrites identical documents, which the rules allow
        // and which never creates duplicates (docs/adr/0003).
        batch.forEach { write.set(readings.document(it.id), it.toDocument(platform = "android")) }
        write.commit().await()
    }

    companion object {
        /** All three values are needed; a half-filled configuration runs local-only instead of failing at upload time. */
        fun isConfigured(projectId: String, applicationId: String, apiKey: String): Boolean =
            listOf(projectId, applicationId, apiKey).none { it.isBlank() }

        /** Returns null when no Firebase project is configured; the app then runs local-only (UC-11). */
        fun createOrNull(context: Context): FirestoreUploader? {
            if (!isConfigured(BuildConfig.FIREBASE_PROJECT_ID, BuildConfig.FIREBASE_APPLICATION_ID, BuildConfig.FIREBASE_API_KEY)) {
                return null
            }
            val options = FirebaseOptions.Builder()
                .setProjectId(BuildConfig.FIREBASE_PROJECT_ID)
                .setApplicationId(BuildConfig.FIREBASE_APPLICATION_ID)
                .setApiKey(BuildConfig.FIREBASE_API_KEY)
                .build()
            val app = FirebaseApp.getApps(context).firstOrNull() ?: FirebaseApp.initializeApp(context, options)
            return FirestoreUploader(app)
        }
    }
}

data class SyncUi(
    val cloudProject: String?,
    val pending: Int = 0,
    val dropped: Int = 0,
    val uploading: Boolean = false,
    val lastError: String? = null,
)

/**
 * Moves readings from the on-phone queue to Firestore.
 *
 * All queue access runs on [queueDispatcher], a single-threaded dispatcher, so the queue needs no locks.
 * A failed batch stays queued and is retried with backoff, and again whenever the network comes back.
 */
class SyncLoop(
    context: Context,
    private val scope: CoroutineScope,
    private val queue: UploadQueue,
    private val uploader: FirestoreUploader?,
    private val queueDispatcher: CoroutineDispatcher,
) {
    private val _ui = MutableStateFlow(SyncUi(cloudProject = uploader?.projectId))
    val ui: StateFlow<SyncUi> = _ui.asStateFlow()
    private var job: Job? = null

    init {
        context.getSystemService(ConnectivityManager::class.java)
            ?.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = kick()
            })
    }

    fun add(reading: Reading) {
        scope.launch(queueDispatcher) {
            queue.add(reading)
            publish()
            kick()
        }
    }

    fun kick() {
        if (uploader == null) {
            scope.launch(queueDispatcher) { publish() }
            return
        }
        scope.launch(queueDispatcher) {
            if (job?.isActive == true) return@launch
            job = scope.launch(queueDispatcher) { drain(uploader) }
        }
    }

    private suspend fun drain(uploader: FirestoreUploader) {
        var failures = 0
        while (true) {
            val batch = queue.nextBatch(limit = 100)
            if (batch.isEmpty()) break
            _ui.update { it.copy(uploading = true) }
            // Firestore keeps offline writes in its own cache and only resolves the task once the
            // server has them; the timeout stops a dead network from parking this loop forever.
            val error: String? = try {
                withTimeoutOrNull(20_000) { uploader.upload(batch) } ?: throw IllegalStateException("Upload timed out")
                queue.acknowledge(batch.map { it.id })
                null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e.message ?: e.javaClass.simpleName
            }
            publish()
            _ui.update { it.copy(lastError = error) }
            if (error == null) {
                failures = 0
                continue
            }
            failures += 1
            if (failures >= 5) break // the network callback or the next reading starts it again
            delay(2_000L shl (failures - 1))
        }
        _ui.update { it.copy(uploading = false) }
    }

    private fun publish() {
        _ui.update { it.copy(pending = queue.size, dropped = queue.dropped) }
    }
}
