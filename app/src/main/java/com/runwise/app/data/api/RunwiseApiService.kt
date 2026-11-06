package com.runwise.app.data.api

import com.runwise.app.data.model.ActivityDetailResponse
import com.runwise.app.data.model.ActivityUploadRequest
import com.runwise.app.data.model.ActivityUploadResponse
import com.runwise.app.data.model.DemElevationRequest
import com.runwise.app.data.model.DemElevationResponse
import com.runwise.app.data.model.GenericStatusResponse
import com.runwise.app.data.model.HealthResponse
import com.runwise.app.data.model.SyncResponse
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

interface RunwiseApiService {

    @GET("api/mobile/health")
    suspend fun health(): HealthResponse

    @GET("api/mobile/sync")
    suspend fun sync(
        @Query("since") since: String? = null
    ): SyncResponse

    @POST("api/mobile/activities")
    suspend fun uploadActivity(
        @Body request: ActivityUploadRequest
    ): ActivityUploadResponse

    @GET("api/mobile/activities/{activityId}")
    suspend fun getActivityDetail(
        @Path("activityId") activityId: String
    ): ActivityDetailResponse

    @DELETE("api/mobile/activities/{activityId}")
    suspend fun deleteActivity(
        @Path("activityId") activityId: String
    ): GenericStatusResponse

    @POST("api/mobile/elevation/dem")
    suspend fun getDemElevation(
        @Body request: DemElevationRequest
    ): DemElevationResponse
}
