package com.harshkatariya.fieldflow.data

import com.harshkatariya.fieldflow.core.*
import retrofit2.Response
import retrofit2.http.*

interface FieldApi {
    @POST("auth/login") suspend fun login(@Body request: LoginRequest): Session
    @POST("auth/logout") suspend fun logout(@Header("Authorization") token: String)
    @GET("sync/changes") suspend fun changes(@Header("Authorization") token: String): Changes
    @POST("sync/operations") suspend fun operation(@Header("Authorization") token: String, @Body operation: Operation): Response<SyncReply>
    @POST("attachments") suspend fun upload(@Header("Authorization") token: String, @Body upload: AttachmentUpload): AttachmentReceipt
}
