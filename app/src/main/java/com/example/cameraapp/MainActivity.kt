package com.example.cameraapp

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.util.Rational
import android.view.Surface.ROTATION_0
import android.view.View
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.ViewPort
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.example.cameraapp.databinding.ActivityMainBinding
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.roundToInt


class MainActivity : AppCompatActivity() {
    private lateinit var viewBinding: ActivityMainBinding
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var rectangle: View
    private lateinit var cropRectangleView: View

    private var imageCapture: ImageCapture? = null
    private var imageName: String = ""
    private val activityResultLauncher = registerActivityResult()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewBinding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(viewBinding.root)

        // Запрос на доступ к камере и хранилищу, если прав нет, иначе включаем камеру
        if (allPermissionsGranted()) {
            startCamera()
        } else {
            requestPermissions()
        }

        viewBinding.imageCaptureButton.setOnClickListener { takePhoto() }
        cameraExecutor = Executors.newSingleThreadExecutor()
        rectangle = findViewById(R.id.myRectangleView)
        cropRectangleView = findViewById(R.id.cropRectangleView)
        rectangle.layoutParams.width = resources.displayMetrics.widthPixels
        rectangle.layoutParams.height = (rectangle.layoutParams.width * VIEWPORT_HEIGHT * 1.0 / VIEWPORT_WIDTH).roundToInt()
    }

    private fun takePhoto() {
        // Get a stable reference of the modifiable image capture use case
        val imageCapture = imageCapture ?: return

        // Create time stamped name and MediaStore entry.
        val name = SimpleDateFormat(FILENAME_FORMAT, Locale.US)
            .format(System.currentTimeMillis())
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, MIME_TYPE)
            put(MediaStore.Images.Media.RELATIVE_PATH, RELATIVE_PATH)
        }

        // Create output options object which contains file + metadata
        val outputOptions = ImageCapture.OutputFileOptions
            .Builder(contentResolver,
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                contentValues)
            .build()

        // Set up image capture listener, which is triggered after photo has
        // been taken
        imageCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onError(exc: ImageCaptureException) {
                    Log.e(TAG, "Photo capture failed: ${exc.message}", exc)
                }

                override fun onImageSaved(output: ImageCapture.OutputFileResults){
                    val bitmap = MediaStore.Images.Media.getBitmap(contentResolver, output.savedUri)
                    val cropBitmap = cropImage(bitmap, rectangle, cropRectangleView)
                    val file = File(output.savedUri?.path!!)
                    file.delete(this@MainActivity)
                    if (cropBitmap != null) {
                        val imageUri = saveBitmapInStorage(cropBitmap, this@MainActivity)
                        startActivity(Intent(this@MainActivity, PhotoViewer::class.java).apply {
                            putExtra("image_uri", imageUri.toString())
                            putExtra("image_name", imageName)
                        })
                    }
                }
            }
        )
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            // Used to bind the lifecycle of cameras to the lifecycle owner
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            // Preview
            val preview = Preview.Builder()
                .build()
                .also {
                    it.setSurfaceProvider(viewBinding.viewFinder.surfaceProvider)
                }

            imageCapture = ImageCapture.Builder()
                .build()

            // Select back camera as a default
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                // Unbind use cases before rebinding
                cameraProvider.unbindAll()

                val viewPort =  ViewPort.Builder(Rational(VIEWPORT_WIDTH, VIEWPORT_HEIGHT), ROTATION_0).build()
                val useCaseGroup = UseCaseGroup.Builder()
                    .addUseCase(preview)
                    .addUseCase(imageCapture!!)
                    .setViewPort(viewPort)
                    .build()
                cameraProvider.bindToLifecycle(this, cameraSelector, useCaseGroup)
            } catch(exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }

        }, ContextCompat.getMainExecutor(this))
    }

    private fun requestPermissions() {
        activityResultLauncher.launch(REQUIRED_PERMISSIONS)
    }

    // Проходимся по всем запращиваемым правам и смотрим, есть ли права
    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(
            baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }

    private fun registerActivityResult(): ActivityResultLauncher<Array<String>> {
        return registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions())
        { permissions ->
            // Handle Permission granted/rejected
            var permissionGranted = true
            permissions.entries.forEach {
                if (it.key in REQUIRED_PERMISSIONS && !it.value)
                    permissionGranted = false
            }
            if (!permissionGranted) {
                Toast.makeText(baseContext,
                    "Нет прав к камере и хранилищу",
                    Toast.LENGTH_SHORT).show()
            } else {
                startCamera()
            }
        }
    }

    private fun cropImage(bitmap: Bitmap, window: View, rect: View): Bitmap? {
        val _scaleX = bitmap.width * 1.0 / window.width
        val _scaleY = bitmap.height * 1.0 / window.height
        val leftRect = abs(window.left - rect.left)
        val topRect = abs(window.top - rect.top)

        Log.d("Image Size: ", "window: ${window.width} x ${window.height}\n " +
                "bitmap: ${bitmap.width} x ${bitmap.height}\n" +
                "rect: ${rect.width} x ${rect.height}")

        val widthFinal = (rect.width * _scaleX).toInt()
        val heightFinal = (rect.height * _scaleY).toInt()
        val leftFinal = (leftRect * _scaleX).toInt()
        val topFinal = (topRect * _scaleY).toInt()

        Log.d("Image Real Size: ", "width: ${widthFinal}, height: ${heightFinal}, " +
                "left: ${leftFinal}, top: ${topFinal}")
        val bitmapFinal = Bitmap.createBitmap(
            bitmap,
            leftFinal, topFinal, widthFinal, heightFinal
        )
        val stream = ByteArrayOutputStream()
        bitmapFinal.compress(
            Bitmap.CompressFormat.JPEG,
            100,
            stream
        ) //100 is the best quality possible
        val imageData = stream.toByteArray()
        return BitmapFactory.decodeByteArray(imageData, 0, imageData.size)
    }

    fun saveBitmapInStorage(bitmap: Bitmap, context: Context): Uri? {
        imageName = SimpleDateFormat(FILENAME_FORMAT, Locale.US)
            .format(System.currentTimeMillis())
        var fos: OutputStream? = null
        var imageUri: Uri? = Uri.EMPTY

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            context.contentResolver?.also { resolver ->
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, imageName)
                    put(MediaStore.MediaColumns.MIME_TYPE, MIME_TYPE)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, RELATIVE_PATH)
                }
                imageUri =
                    resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
                fos = imageUri?.let { resolver.openOutputStream(it) }
            }
        } else {
            val imagesDir =
                Environment.getExternalStoragePublicDirectory(RELATIVE_PATH)
            val image = File(imagesDir, imageName)
            imageUri = image.toUri()
            fos = FileOutputStream(image)
        }
        fos?.use {
            bitmap.compress(Bitmap.CompressFormat.JPEG, 100, it)
        }
        return imageUri
    }

    companion object {
        private const val TAG = "Camera Crop"
        private const val FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS"
        private const val MIME_TYPE = "image/jpeg"
        const val RELATIVE_PATH = "Pictures/CameraCrop"
        private const val VIEWPORT_WIDTH = 35
        private const val VIEWPORT_HEIGHT = 10
        private val REQUIRED_PERMISSIONS =
            mutableListOf (
                Manifest.permission.CAMERA,
                Manifest.permission.INTERNET
            ).apply {
                if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                    add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    add(Manifest.permission.READ_EXTERNAL_STORAGE)
                }
            }.toTypedArray()
    }
}

fun File.delete(context: Context): Boolean {
    var selectionArgs = arrayOf(this.absolutePath)
    val contentResolver = context.contentResolver
    var where: String? = null
    var filesUri: Uri? = null
    if (Build.VERSION.SDK_INT >= 29) {
        filesUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        where = MediaStore.Images.Media._ID + "=?"
        selectionArgs = arrayOf(this.name)
    } else {
        where = MediaStore.MediaColumns.DATA + "=?"
        filesUri = MediaStore.Files.getContentUri("external")
    }
    contentResolver.delete(filesUri!!, where, selectionArgs)
    return !this.exists()
}