package com.example.cameraapp

import okhttp3.MultipartBody
import retrofit2.Call
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part


interface ApiService {
    @Multipart
    @POST("/uploadfiles")
    fun uploadImage(
        @Part image: MultipartBody.Part
    ): Call<String>

    @GET("/get")
    fun getStringData(): Call<String>

}