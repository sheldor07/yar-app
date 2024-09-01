package com.example.yar__app

import com.example.yar__app.databinding.ActivityMainBinding
import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Color
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
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.core.Preview
import androidx.camera.core.CameraSelector
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.widget.FrameLayout
import android.widget.TextView
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.VideoRecordEvent
import androidx.core.app.ActivityCompat
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
import java.io.FileWriter
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean


class MainActivity : AppCompatActivity() {
    private lateinit var viewBinding: ActivityMainBinding //The lateinit keyword allows you to avoid initializing a property when an object is constructed.
    private lateinit var cameraExecutor: ExecutorService
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null

    private var client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()
    private lateinit var player: ExoPlayer
    private lateinit var debugTextView: TextView

    private var isRecording = AtomicBoolean(false)
    private var debounceTime = 1000L
    private var lastActionTime = 0L

    private lateinit var logFile: File

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewBinding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(viewBinding.root)

        // Add debugTextView to the layout
        debugTextView = TextView(this)
        debugTextView.setTextColor(Color.WHITE)
        debugTextView.setBackgroundColor(Color.parseColor("#80000000"))
        val params = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        )
        params.gravity = Gravity.BOTTOM
        viewBinding.root.addView(debugTextView, params)

        // request camera permissions
        if(allPermissionsGranted()){
            startCamera()
        }else{
            requestPermissions()
        }
        // setting up listeners for video capture button

        player = ExoPlayer.Builder(this).build()

        cameraExecutor = Executors.newSingleThreadExecutor()
        logFile = File(getExternalFilesDir(null), "app_log.txt")

    }
    private fun logAndDisplay(message: String) {
        Log.d(TAG, message)
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(Date())
        val logMessage = "[$timestamp] $message"
        try {
            FileWriter(logFile, true).use { writer ->
                writer.append(logMessage).append("\n")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error writing to log file", e)
        }

        runOnUiThread {
            debugTextView.append("$logMessage\n")
            // Scroll to the bottom
            val scrollAmount = debugTextView.layout.getLineTop(debugTextView.lineCount) - debugTextView.height
            if (scrollAmount > 0) {
                debugTextView.scrollTo(0, scrollAmount)
            } else {
                debugTextView.scrollTo(0, 0)
            }
        }
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(
            baseContext, it) == PackageManager.PERMISSION_GRANTED
    }
    private fun startVideoCapture() {
        try {
            logAndDisplay("Capture button pressed")
            if (isRecording.get()) {
                logAndDisplay("Already recording, ignoring start request")
                return
            }
            logAndDisplay("Preparing to start recording")
            val videoCapture = this.videoCapture
            if (videoCapture == null) {
                logAndDisplay("VideoCapture is null, returning")
                return
            }
            logAndDisplay("Preparing to start recording")
            val name = "CameraX-recording-" +
                    SimpleDateFormat(FILENAME_FORMAT, Locale.US)
                        .format(System.currentTimeMillis()) + ".mp4"
            val contentValues = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, name)
            }
            val mediaStoreOutput = MediaStoreOutputOptions.Builder(
                contentResolver,
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
                .setContentValues(contentValues)
                .build()

            isRecording.set(true)
            recording = videoCapture.output
                .prepareRecording(this, mediaStoreOutput)
                .apply {
                    if (PermissionChecker.checkSelfPermission(
                            this@MainActivity,
                            Manifest.permission.RECORD_AUDIO
                        ) ==
                        PermissionChecker.PERMISSION_GRANTED
                    ) {
                        withAudioEnabled()
                    }
                }
                .start(ContextCompat.getMainExecutor(this)) { recordEvent ->
                    when (recordEvent) {
                        is VideoRecordEvent.Start -> {
                            logAndDisplay("Recording started")
                            lastActionTime = System.currentTimeMillis()
                        }


                        is VideoRecordEvent.Finalize -> {
                            if (!recordEvent.hasError()) {
                                logAndDisplay("Recording stopped successfully")
                                val msg =
                                    "Video capture succeeded: ${recordEvent.outputResults.outputUri}"
                                logAndDisplay(msg)
                                uploadVideo(recordEvent.outputResults.outputUri)  // Don't forget to call this
                            } else {
                                recording?.close()
                                recording = null
                                logAndDisplay("Video capture failed: ${recordEvent.error}")
                                logAndDisplay("Error details: ${recordEvent.cause?.message}")
                            }
                            isRecording.set(false)
                        }
                    }

                }
        }catch (e:Exception){
            logAndDisplay("Error clicking camera button ${e.message}")
            isRecording.set(false)
        }



    }private fun stopVideoCapture(){
        try{
            if(!isRecording.get()){
                logAndDisplay("Not recording, ignoring stop request")
                return
            }

            logAndDisplay("Stopping video capture")
            val curRecording = recording
            if(curRecording != null && isRecording.get()){
                logAndDisplay("Stopping current recording")
                curRecording.stop()
                recording = null
                isRecording.set(false)
            }
        }catch (e: Exception){
            logAndDisplay("Error in stopVideoCapture: ${e.message}")
            isRecording.set(false)
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
                .url("https://9818-194-61-40-15.ngrok-free.app/video_processing/upload/")
                .header("X-Token", boardId)
                .header("X-Device-Type", "android")
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
    private fun requestPermissions() {
        ActivityCompat.requestPermissions(
            this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
    }
    override fun onDestroy() {
        super.onDestroy()
        player.release()
        cameraExecutor.shutdown()
    }
    companion object {
        private const val TAG = "yarApp"
        private const val FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS"
        private const val REQUEST_CODE_PERMISSIONS = 10
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

    private fun logBtnEvent(keyCode: Int, event: KeyEvent) {
        try {
            val action = when (event.action) {
                KeyEvent.ACTION_DOWN -> "pressed"
                KeyEvent.ACTION_UP -> "released"
                else -> "unknown action"
            }
            val keyCodeName = when (keyCode) {
                KeyEvent.KEYCODE_BUTTON_B -> "KEYCODE_BUTTON_B (139)"
                KeyEvent.KEYCODE_BACK -> "KEYCODE_BACK (4)"
                else -> "Unknown ($keyCode)"
            }
            logAndDisplay("Button event: $keyCodeName, action=$action, device=${event.device?.name}")
        } catch (e: Exception) {
            logAndDisplay("Error in logButtonEvent: ${e.message}")
        }
    }
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        logBtnEvent(keyCode, event)
        try{
            if (keyCode == 139 && event.device?.name != "Virtual") {
                val currentTime = System.currentTimeMillis()
                if (currentTime - lastActionTime >= debounceTime) {
                    lastActionTime = currentTime
                    logAndDisplay("Button pressed")
                    toggleRecording()
                }
                return super.onKeyDown(keyCode, event)

            }else{
                logBtnEvent(keyCode, event)
            }
        }catch(e: Exception){
            logAndDisplay("Error in onKeyDown $e")
        }
        return super.onKeyDown(keyCode, event)
    }
    private fun toggleRecording() {
        if (isRecording.get()) {
            logAndDisplay("Stopping video capture")
            stopVideoCapture()
        } else {
            logAndDisplay("Starting video capture")
            startVideoCapture()
        }
    }


}