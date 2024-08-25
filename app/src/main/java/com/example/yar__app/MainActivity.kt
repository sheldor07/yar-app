package com.example.yar__app

import com.example.yar__app.databinding.ActivityMainBinding
import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.core.content.ContextCompat
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.core.Preview
import androidx.camera.core.CameraSelector
import android.util.Log
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.PermissionChecker
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit


class MainActivity : AppCompatActivity() {
    private lateinit var viewBinding: ActivityMainBinding //The lateinit keyword allows you to avoid initializing a property when an object is constructed.
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null

    private lateinit var cameraExecutor: ExecutorService
    private var client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()
    private lateinit var player: ExoPlayer

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewBinding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(viewBinding.root)
        // request camera persissions
        if(allPermissionsGranted()){
            startCamera()
        }else{
            requestPermissions()
        }
        // settting up listeners for video capture button
        viewBinding.captureBtn.setOnClickListener { captureVideo() }

        player = ExoPlayer.Builder(this).build()

        cameraExecutor = Executors.newSingleThreadExecutor()

    }
    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(
            baseContext, it) == PackageManager.PERMISSION_GRANTED
    }
    private fun captureVideo() {
        val videoCapture = this.videoCapture ?: return
        viewBinding.captureBtn.isEnabled = false
        val curRecording = recording
        if (curRecording != null) {
            // stop button
            curRecording.stop()
            recording = null
            return
        }
        // create and start a new recording session
        val name = SimpleDateFormat(FILENAME_FORMAT, Locale.US)
            .format(System.currentTimeMillis())
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/CameraX-Video")
            }
        }

        val mediaStoreOutputOptions = MediaStoreOutputOptions
            .Builder(contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            .setContentValues(contentValues)
            .build()
        recording = videoCapture.output
            .prepareRecording(this, mediaStoreOutputOptions)
            .apply {
                if (PermissionChecker.checkSelfPermission(
                        this@MainActivity,
                        Manifest.permission.RECORD_AUDIO
                    ) == PermissionChecker.PERMISSION_GRANTED
                ) {
                    withAudioEnabled()
                }
            }
            .start(ContextCompat.getMainExecutor(this)) { recordEvent ->
                when (recordEvent) {
                    is VideoRecordEvent.Start -> {
                        viewBinding.captureBtn.apply {
                            text = getString(R.string.stop_capture)
                            isEnabled = true
                        }
                    }

                    is VideoRecordEvent.Finalize -> {
                        if (!recordEvent.hasError()) {
                            val msg =
                                "Video capture succeeded: ${recordEvent.outputResults.outputUri}"
                            Toast.makeText(baseContext, msg, Toast.LENGTH_SHORT).show()
                            Log.d(TAG, msg)

                            //Upload the video
                            uploadVideo(recordEvent.outputResults.outputUri)
                        } else {
                            recording?.close()
                            recording = null
                            Log.e(TAG, "Video capture ends with error: ${recordEvent.error}")
                        }
                        viewBinding.captureBtn.apply {
                            text = getString(R.string.start_capture)
                            isEnabled = true
                        }
                    }
                }
            }
    }
    @OptIn(ExperimentalStdlibApi::class)
    private fun uploadVideo(videoUri: Uri) {
        Log.d(TAG, "Starting upload for video URI: $videoUri")

        try {
            val tempFile = File(cacheDir, "temp_video.mp4")
            contentResolver.openInputStream(videoUri)?.use { input ->
                tempFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }

            Log.d(TAG, "Copied video to temporary file: ${tempFile.absolutePath}")
            Log.d(TAG, "Temporary file size: ${tempFile.length()} bytes")

            val requestBody = RequestBody.create("video/mp4".toMediaType(), tempFile)


            val boardId = "100100100"
            Log.d(TAG, "Using board ID: $boardId")

            val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("video", "video.mp4", requestBody)
                .build()
            Log.d(TAG, "Created multipart request body")

            val request = Request.Builder()
                .url("https://e255-155-69-183-21.ngrok-free.app/video_processing/upload/")
                .header("X-Token", boardId)
                .post(body)
                .build()
            Log.d(TAG, "Built request with URL: ${request.url} and headers: ${request.headers}")

            Log.d(TAG, "Sending upload request")
            client.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    Log.e(TAG, "Network failure during upload", e)
                    runOnUiThread {
                        Toast.makeText(baseContext, "Upload failed: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                    tempFile.delete()
                }

                override fun onResponse(call: Call, response: Response) {
                    if (response.isSuccessful) {
                        val contentType = response.header("Content-Type")
                        Log.d(TAG, "Response Content-Type: $contentType")

                        if (contentType == "audio/mpeg") {
                            // Clone the response body to ensure we can read it multiple times
                            val responseBody = response.body?.bytes()
                            if (responseBody != null) {
                                // Log the first few bytes of the response for debugging
                                val preview = responseBody.take(100).toByteArray().toHexString()
                                Log.d(TAG, "Response body preview: $preview")

                                saveAndPlayAudio(responseBody)
                            } else {
                                Log.e(TAG, "Response body is null")
                                runOnUiThread {
                                    Toast.makeText(baseContext, "Failed to receive audio data", Toast.LENGTH_LONG).show()
                                }
                            }
                        } else {
                            Log.e(TAG, "Unexpected content type: $contentType")
                            runOnUiThread {
                                Toast.makeText(baseContext, "Received unexpected content type: $contentType", Toast.LENGTH_LONG).show()
                            }
                        }
                    } else {
                        Log.e(TAG, "Server error during upload. Code: ${response.code}")
                        runOnUiThread {
                            Toast.makeText(baseContext, "Upload failed: ${response.code} ${response.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                    tempFile.delete()
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "Exception during upload process", e)
            runOnUiThread {
                Toast.makeText(baseContext, "Upload failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
    private fun startCamera(){
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            //used to bind the lifecycle of cameras to the lifecycle owner
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()
            // preview
            val preview = Preview.Builder()
                .build()
                .also{
                    it.setSurfaceProvider(viewBinding.viewFinder.surfaceProvider)
                }
            val recorder = Recorder.Builder()
                .setQualitySelector(QualitySelector.from(Quality.HIGHEST))
                .build()
            videoCapture = VideoCapture.withOutput(recorder)
            val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA
            try{
                //unbind use cases before rebinding
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, videoCapture)
            }catch(exc: Exception){
                Log.e(TAG,"Use case binding failed",exc)
            }
        },ContextCompat.getMainExecutor((this)))
    }
    private fun saveAndPlayAudio(audioData: ByteArray) {
        try {
            val file = File(getExternalFilesDir(null), "downloaded_audio.mp3")
            file.writeBytes(audioData)
            val audioUri = Uri.fromFile(file)


            // Once the audio file is saved, play it
            runOnUiThread {
                playAudio(audioUri)
                Toast.makeText(baseContext, "Audio saved and playing", Toast.LENGTH_SHORT).show()

            }

        } catch (e: IOException) {
            Log.e(TAG, "Error saving or playing audio", e)
            runOnUiThread {
                Toast.makeText(baseContext, "Error saving or playing audio: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun playAudio(uri:Uri){
        try {
            player.stop()
            player.clearMediaItems()

            val mediaItem = MediaItem.fromUri(uri)
            player.setMediaItem(mediaItem)
            player.prepare()
            player.play()

            Log.d(TAG, "Playing audio from URI: $uri")
        } catch (e: Exception) {
            Log.e(TAG, "Error playing audio file", e)
            runOnUiThread {
                Toast.makeText(baseContext, "Error playing audio: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }
    private fun requestPermissions(){
        activityResultLauncher.launch(REQUIRED_PERMISSIONS)
    }
    override fun onDestroy() {
        super.onDestroy()
        player.release()
        cameraExecutor.shutdown()
    }
    private val activityResultLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions())
        { permissions ->
            // Handle Permission granted/rejected
            var permissionGranted = true
            permissions.entries.forEach {
                if (it.key in REQUIRED_PERMISSIONS && it.value == false)
                    permissionGranted = false
            }
            if (!permissionGranted) {
                Toast.makeText(baseContext,
                    "Permission request denied",
                    Toast.LENGTH_SHORT).show()
            } else {
                startCamera()
            }
        }
    companion object {
        private const val TAG = "yarApp"
        private const val FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS"
        private val REQUIRED_PERMISSIONS =
            mutableListOf(
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO,
            ).apply{
                if(Build.VERSION.SDK_INT <= Build.VERSION_CODES.P){
                    add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                }
            }.toTypedArray()
    }

}