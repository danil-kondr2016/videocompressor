package ru.danila.videocompressor

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.NotificationManager.IMPORTANCE_HIGH
import android.app.NotificationManager.IMPORTANCE_LOW
import android.content.ContentValues
import android.media.*
import android.media.MediaCodecList.REGULAR_CODECS
import android.net.Uri
import android.os.Build
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.View.GONE
import android.view.View.VISIBLE
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts.GetContent
import androidx.core.app.NotificationCompat

import com.otaliastudios.transcoder.Transcoder
import com.otaliastudios.transcoder.TranscoderListener
import com.otaliastudios.transcoder.resize.AtMostResizer
import com.otaliastudios.transcoder.strategy.DefaultAudioStrategy
import com.otaliastudios.transcoder.strategy.DefaultVideoStrategy
import java.util.*
import java.util.concurrent.Future

class MainActivity : AppCompatActivity(), TranscoderListener {
    companion object {
        /* Progress bar parameters */
        const val PROGRESS_MAX = 10000

        /* Video parameters */
        const val WIDTH          = 176
        const val HEIGHT         = 144
        const val FRAME_RATE     = 12
        const val VIDEO_BIT_RATE = 56000L
        const val SAMPLE_RATE    = 8000
        const val CHANNELS       = 1
        const val AUDIO_BIT_RATE = 24000L
        const val I_FRAME_INTERVAL = 3F

        /* Notification parameters */
        const val COMPRESS_STATE_ID = "CompressState"
        const val COMPRESS_MSG_ID = "CompressMessage"
        const val PROGRESS_NOTIFICATION = 100
        const val MESSAGE_NOTIFICATION  = 101
    }

    private lateinit var notificationManager : NotificationManager

    private lateinit var codecList : MediaCodecList
    private lateinit var arCompress : ActivityResultLauncher<String>

    private lateinit var btnSelect : Button
    private lateinit var lProgress : TextView
    private lateinit var progressBar: ProgressBar

    private lateinit var progressNotificationBuilder : NotificationCompat.Builder
    private lateinit var doneNotificationBuilder : NotificationCompat.Builder
    private lateinit var failNotificationBuilder : NotificationCompat.Builder

    private var task : Future<Void>? = null

    private var progressState : Boolean = false

    override fun onTranscodeProgress(progress: Double) {
        if (progress >= 0) {
            progressBar.isIndeterminate = false
            progressBar.progress = (progress * PROGRESS_MAX).toInt()
            lProgress.text = String.format(
                Locale.getDefault(),
                "%.2f%%",
                progress * 100
            )
            progressNotificationBuilder.setProgress(
                PROGRESS_MAX,
                (progress * PROGRESS_MAX).toInt(),
                false
            )
        } else {
            progressBar.isIndeterminate = true
            progressNotificationBuilder.setProgress(PROGRESS_MAX, 0, true)
        }

        notificationManager.notify(
            PROGRESS_NOTIFICATION,
            progressNotificationBuilder.build()
        )
    }

    override fun onTranscodeCompleted(successCode: Int) {
        setProgressState(false)
        doneNotify()
        task = null
    }

    override fun onTranscodeCanceled() {
        setProgressState(false)
        cancelNotify()
        task = null
    }

    override fun onTranscodeFailed(exception: Throwable) {
        val msg : String? = exception.localizedMessage
        var text : String = ""
        if (msg == null)
            text = getString(R.string.unknown_error)
        else
            text = String.format(getString(R.string.error), msg)
        setProgressState(false)
        failNotify(text)
        task = null
    }

    private fun setProgressState(newProgressState : Boolean) {
        val visibleState = if (newProgressState) VISIBLE else GONE

        progressBar.visibility = visibleState
        lProgress.visibility = visibleState

        if (newProgressState) {
            btnSelect.setText(R.string.cancel)
        } else {
            btnSelect.setText(R.string.compress_file)
        }

        if (!newProgressState)
            notificationManager.cancel(PROGRESS_NOTIFICATION)

        progressState = newProgressState
    }

    private fun createNotificationChannel(
        id : String,
        name : String,
        importance : Int,
        conf : (NotificationChannel) -> Unit = {}) : NotificationChannel? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(id, name, importance)
            conf(channel)
            notificationManager.createNotificationChannel(channel)
            return channel
        }
        return null
    }

    private fun doneNotify() {
        doneNotificationBuilder.setContentTitle(getString(R.string.done))
        notificationManager.notify(MESSAGE_NOTIFICATION, doneNotificationBuilder.build())
    }

    private fun cancelNotify() {
        doneNotificationBuilder.setContentTitle(getString(R.string.canceled))
        notificationManager.notify(MESSAGE_NOTIFICATION, doneNotificationBuilder.build())
    }

    private fun failNotify(text : String) {
        failNotificationBuilder.setContentTitle(text)
        notificationManager.notify(MESSAGE_NOTIFICATION, failNotificationBuilder.build())
    }

    private fun getOutputUri(srcUri : Uri) : Uri? {
        val cursor = contentResolver.query(
            srcUri,
            arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),
            null,
            null,
            null
        )
        cursor?.moveToFirst()
        val displayName = cursor?.getString(0).toString()
        cursor?.close()
        Log.i(LOG_TAG, "displayName == $displayName")

        val displayNameSep = displayName.split('.').toMutableList()
        if (displayNameSep.size > 1)
            displayNameSep.removeLast()

        displayNameSep[displayNameSep.size - 1] += "_compressed"
        displayNameSep.add("mp4")

        val newDisplayName = displayNameSep.joinToString(".")

        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, newDisplayName)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
        }

        val contentUri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        return contentResolver.insert(contentUri, contentValues)
    }

    private fun compressVideo(uri : Uri) {
        val outputUri = getOutputUri(uri)!!
        val outputFd = contentResolver.openFileDescriptor(outputUri, "rwt")?.fileDescriptor!!

        val inputFd = contentResolver.openFileDescriptor(uri, "r")?.fileDescriptor!!

        val videoStrategy = DefaultVideoStrategy.Builder().apply {
            addResizer(AtMostResizer(HEIGHT))
            bitRate(VIDEO_BIT_RATE)
            frameRate(FRAME_RATE)
            keyFrameInterval(I_FRAME_INTERVAL)
        }.build()

        val audioStrategy = DefaultAudioStrategy.builder().apply {
            bitRate(AUDIO_BIT_RATE)
            sampleRate(SAMPLE_RATE)
            channels(CHANNELS)
        }.build()

        setProgressState(true)
        task = Transcoder.into(outputFd)
            .addDataSource(inputFd)
            .setVideoTrackStrategy(videoStrategy)
            .setAudioTrackStrategy(audioStrategy)
            .setListener(this)
            .transcode()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        codecList = MediaCodecList(REGULAR_CODECS)
        btnSelect = findViewById(R.id.select_btn)

        lProgress = findViewById(R.id.progress_text)
        progressBar = findViewById(R.id.progress_bar)
        progressBar.max = PROGRESS_MAX

        notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

        createNotificationChannel(
            COMPRESS_STATE_ID,
            getString(R.string.compress_state),
            IMPORTANCE_LOW
        ) {
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                .build()
            it.setSound(Uri.EMPTY, audioAttributes)
        }
        createNotificationChannel(COMPRESS_MSG_ID, getString(R.string.compress_msg), IMPORTANCE_HIGH)

        progressNotificationBuilder = NotificationCompat.Builder(
            this,
            COMPRESS_STATE_ID)
            .apply {
            setContentTitle(getString(R.string.video_is_compressing))
            setSmallIcon(R.drawable.ic_baseline_video_library_24)
            setProgress(PROGRESS_MAX, 0, false)
            setSound(Uri.EMPTY) // На всякий пожарный случай
        }

        doneNotificationBuilder = NotificationCompat.Builder(this, COMPRESS_MSG_ID)
            .setSmallIcon(R.drawable.ic_baseline_video_library_24)

        failNotificationBuilder = NotificationCompat.Builder(this, COMPRESS_MSG_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)

        setProgressState(false)
        arCompress = registerForActivityResult(GetContent()) { compressVideo(it) }
        btnSelect.setOnClickListener {
            if (!progressState) {
                arCompress.launch("video/*")
            } else {
                task?.cancel(true)
            }
        }
    }
}