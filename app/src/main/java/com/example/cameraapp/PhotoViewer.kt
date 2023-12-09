package com.example.cameraapp

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Log
import android.view.View
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okio.Buffer
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.File
import java.io.IOException




class PhotoViewer : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_photo_viewer)

        val imageView = findViewById<ImageView>(R.id.imageView)
        val uri = Uri.parse(intent.getStringExtra("image_uri"))
        val imageName = intent.getStringExtra("image_name")

        uploadImage(uri)

        imageView.setImageURI(uri)
        Log.d("Image Size", "${imageView.drawable.intrinsicWidth} x ${imageView.drawable.intrinsicHeight}")
    }

    fun clickBtn(view: View) {
        startActivity(Intent(this@PhotoViewer, MainActivity::class.java))
    }

    private fun uploadImage(imageUri: Uri) {
        val retrofit = Retrofit.Builder()
            .baseUrl("http://194.59.40.99:8009")
            .addConverterFactory(GsonConverterFactory.create())
            .build()

        val apiService = retrofit.create(ApiService::class.java)
        val imageFile = File(getAbsolutePathFromUri(this, imageUri)!!)
        if (!imageFile.exists()) {
            Log.d("IMAGE EXISTS", "NOT EXISTS FILE")
        }
        Log.d("IMAGE PATH", getAbsolutePathFromUri(this, imageUri)!!)
        val requestFile = imageFile.asRequestBody("image/*".toMediaType())
        Log.d("IMAGE REQUEST", bodyToString(requestFile))
        val imagePart = MultipartBody.Part.createFormData("image", imageFile.name, requestFile)

        val call = apiService.uploadImage(imagePart)
        call.enqueue(object : Callback<String> {
            override fun onResponse(call: Call<String>, response: Response<String>) {
                if (response.isSuccessful) {
                    val uploadResponse = response.body()
                    Toast.makeText(baseContext,
                        uploadResponse,
                        Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(baseContext,
                        "Ошибка обработки",
                        Toast.LENGTH_SHORT).show()
                }
            }

            override fun onFailure(call: Call<String>, t: Throwable) {
                Toast.makeText(baseContext,
                    "Ошибка передачи",
                    Toast.LENGTH_SHORT).show()
            }
        })

    }


    private fun bodyToString(request: RequestBody): String {
            val buffer = Buffer()
            request.writeTo(buffer)
            return buffer.readUtf8()
    }

    fun getAbsolutePathFromUri(context: Context, uri: Uri): String? {
        var absolutePath: String? = null

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            // Для Android Q и выше лучше использовать ContentResolver
            val projection = arrayOf(MediaStore.Images.Media.DATA)
            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val columnIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)
                    absolutePath = cursor.getString(columnIndex)
                }
            }
        } else {
            // Для более ранних версий Android
            when {
                DocumentsContract.isDocumentUri(context, uri) -> {
                    // Если URI представляет документ, обрабатываем его
                    val documentId = DocumentsContract.getDocumentId(uri)
                    when {
                        "com.android.providers.media.documents" == uri.authority -> {
                            // Для файлов из MediaProvider
                            val id = documentId.split(":")[1]
                            val selection = MediaStore.Images.Media._ID + "=?"
                            val selectionArgs = arrayOf(id)
                            val column = "_data"
                            val projection = arrayOf(column)
                            val contentUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                            context.contentResolver.query(contentUri, projection, selection, selectionArgs, null)?.use { cursor ->
                                if (cursor.moveToFirst()) {
                                    val columnIndex = cursor.getColumnIndexOrThrow(column)
                                    absolutePath = cursor.getString(columnIndex)
                                }
                            }
                        }
                        "com.android.providers.downloads.documents" == uri.authority -> {
                            // Для файлов из DownloadsProvider
                            val id = documentId.split(":")[1]
                            val contentUri = ContentUris.withAppendedId(Uri.parse("content://downloads/public_downloads"), id.toLong())
                            absolutePath = getDataColumn(context, contentUri, null, null)
                        }
                        "content" == uri.scheme?.lowercase() -> {
                            // Для общих типов content://
                            absolutePath = getDataColumn(context, uri, null, null)
                        }
                    }
                }
                "content" == uri.scheme?.lowercase() -> {
                    // Если не документ, но схема — content
                    absolutePath = getDataColumn(context, uri, null, null)
                }
            }
        }

        return absolutePath
    }

    private fun getDataColumn(context: Context, uri: Uri, selection: String?, selectionArgs: Array<String>?): String? {
        context.contentResolver.query(uri, arrayOf("_data"), selection, selectionArgs, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndexOrThrow("_data")
                return cursor.getString(index)
            }
        }
        return null
    }

}

