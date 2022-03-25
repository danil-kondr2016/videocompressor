package ru.danila.videocompressor

import android.content.ContentValues
import android.media.*
import android.media.MediaCodecList.REGULAR_CODECS
import android.net.Uri
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.View.GONE
import android.view.View.VISIBLE
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import android.widget.Toast.LENGTH_LONG
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts.GetContent

import com.otaliastudios.transcoder.Transcoder
import com.otaliastudios.transcoder.TranscoderListener
import com.otaliastudios.transcoder.common.Size
import com.otaliastudios.transcoder.resize.AtMostResizer
import com.otaliastudios.transcoder.resize.Resizer
import com.otaliastudios.transcoder.strategy.DefaultAudioStrategy
import com.otaliastudios.transcoder.strategy.DefaultVideoStrategies
import com.otaliastudios.transcoder.strategy.DefaultVideoStrategy
import java.util.*
import java.util.concurrent.Future

class MainActivity : AppCompatActivity() {
    companion object {
        const val PROGRESS_MAX = 10000

        const val WIDTH          = 176
        const val HEIGHT         = 144
        const val FRAME_RATE     = 12
        const val VIDEO_BIT_RATE = 192000L
        const val SAMPLE_RATE    = 8000
        const val CHANNELS       = 1
        const val AUDIO_BIT_RATE = 24000L
        const val I_FRAME_INTERVAL = 1F
    }

    private lateinit var codecList : MediaCodecList
    private lateinit var arCompress : ActivityResultLauncher<String>

    private lateinit var btnSelect : Button
    private lateinit var btnCancel : Button
    private lateinit var lProgress : TextView
    private lateinit var progressBar: ProgressBar

    private var task : Future<Void>? = null

    fun setProgressDisplay(progress: Double) {
        progressBar.progress = (progress * PROGRESS_MAX).toInt()
        lProgress.text = String.format(
            Locale.getDefault(),
            "%.2f%%",
            progress * 100
        )
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

        btnCancel.visibility = VISIBLE
        task = Transcoder.into(outputFd)
            .addDataSource(inputFd)
            .setVideoTrackStrategy(videoStrategy)
            .setAudioTrackStrategy(audioStrategy)
            .setListener(
                object : TranscoderListener {
                    override fun onTranscodeProgress(progress: Double) {
                        setProgressDisplay(progress)
                    }

                    override fun onTranscodeCompleted(successCode: Int) {
                        Toast.makeText(this@MainActivity, getString(R.string.done), LENGTH_LONG).show()
                        btnCancel.visibility = GONE
                        task = null
                    }

                    override fun onTranscodeCanceled() {
                        Toast.makeText(this@MainActivity, getString(R.string.canceled), LENGTH_LONG).show()
                        btnCancel.visibility = GONE
                        setProgressDisplay(0.0)
                        task = null
                    }

                    override fun onTranscodeFailed(exception: Throwable) {
                        val msg : String? = exception.localizedMessage
                        var text : String = ""
                        if (msg == null)
                            text = getString(R.string.unknown_error)
                        else
                            text = String.format(getString(R.string.error), msg)
                        Toast.makeText(this@MainActivity, text, LENGTH_LONG).show()
                        btnCancel.visibility = GONE
                        setProgressDisplay(0.0)
                        task = null
                    }

                }
            )
            .transcode()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        codecList = MediaCodecList(REGULAR_CODECS)
        btnSelect = findViewById(R.id.select_btn)
        btnCancel = findViewById(R.id.cancel_btn)

        lProgress = findViewById(R.id.progress_text)
        progressBar = findViewById(R.id.progress_bar)
        progressBar.max = PROGRESS_MAX
        arCompress = registerForActivityResult(GetContent()) { compressVideo(it) }
        btnSelect.setOnClickListener { arCompress.launch("video/*") }
        btnCancel.setOnClickListener { task?.cancel(true) }
    }
}