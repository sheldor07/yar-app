package com.example.yar__app

import com.example.yar__app.databinding.ActivityMainBinding
import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.StatFs
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
import android.widget.FrameLayout
import android.widget.TextView
import androidx.camera.video.FileOutputOptions
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
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit


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
        viewBinding.captureBtn.setOnClickListener { captureVideo() }

        player = ExoPlayer.Builder(this).build()

        cameraExecutor = Executors.newSingleThreadExecutor()

    }
    private fun logAndDisplay(message: String) {
        Log.d(TAG, message)
        runOnUiThread {
            debugTextView.append("$message\n")
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
    private fun captureVideo() {
        logAndDisplay("Capture button pressed")
        val videoCapture = this.videoCapture
        if (videoCapture == null) {
            logAndDisplay("VideoCapture is null, returning")
            return
        }

        viewBinding.captureBtn.isEnabled = false

        val curRecording = recording
        if (curRecording != null) {
            logAndDisplay("Stopping current recording")
            curRecording.stop()
            recording = null
        } else {
            logAndDisplay("Starting new recording")
        val availableStorage = getAvailableInternalMemorySize()
            logAndDisplay("Available storage : $availableStorage bytes")
            if(availableStorage< 10*1024*1024){
                logAndDisplay("Not enough storage")
                viewBinding.captureBtn.isEnabled = true
                return
            }

            val videoFile = createFile(getOutputDirectory())
            logAndDisplay("Video will be saved to: $videoFile")
            val outputOptions = FileOutputOptions.Builder(videoFile).build()
            recording = videoCapture.output
                .prepareRecording(this, outputOptions)
                .apply {
                    if (PermissionChecker.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO) ==
                        PermissionChecker.PERMISSION_GRANTED
                    ) {
                        withAudioEnabled()
                    }
                }
                .start(ContextCompat.getMainExecutor(this)) { recordEvent ->
                    when (recordEvent) {
                        is VideoRecordEvent.Start -> {
                            logAndDisplay("Recording started")
                            viewBinding.captureBtn.apply {
                                text = getString(R.string.stop_capture)
                                isEnabled = true
                            }
                        }
                        is VideoRecordEvent.Finalize -> {
                            if (!recordEvent.hasError()) {
                                logAndDisplay("Recording stopped successfully")
                                val msg = "Video capture succeeded: ${recordEvent.outputResults.outputUri}"
                                logAndDisplay(msg)
                                uploadVideo(recordEvent.outputResults.outputUri)  // Don't forget to call this
                            } else {
                                recording?.close()
                                recording = null
                                logAndDisplay("Video capture failed: ${recordEvent.error}")
                                logAndDisplay("Error details: ${recordEvent.cause?.message}")
                            }
                            viewBinding.captureBtn.text = getString(R.string.start_capture)
                        }
                    }
                    viewBinding.captureBtn.isEnabled = true
                }
        }
    }private fun getAvailableInternalMemorySize(): Long {
        val stat = StatFs(filesDir.path)
        return stat.availableBlocksLong * stat.blockSizeLong
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
                .url("https://0a27-111-65-39-70.ngrok-free.app/video_processing/upload/")
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
    private fun requestPermissions() {
        ActivityCompat.requestPermissions(
            this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
    }
    override fun onDestroy() {
        super.onDestroy()
        player.release()
        cameraExecutor.shutdown()
    }

    private fun getOutputDirectory(): File{
        val mediaDir = externalMediaDirs.firstOrNull()?.let{
            File(it, resources.getString(R.string.app_name)).apply { mkdirs() }
        }
        return if(mediaDir!=null && mediaDir.exists()) mediaDir else filesDir
    }
    private fun createFile(baseFolder:File, format:String = FILENAME_FORMAT, extension:String = ".mp4") =
        File(baseFolder,SimpleDateFormat(format, Locale.US).format(System.currentTimeMillis())+extension)
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

}