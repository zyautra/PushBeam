package com.zyautra.pushbeam.push

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.google.firebase.messaging.FirebaseMessaging
import com.zyautra.pushbeam.App
import com.zyautra.pushbeam.BuildConfig
import com.zyautra.pushbeam.data.ApiException
import com.zyautra.pushbeam.shared.api.RegisterDeviceRequest
import kotlinx.coroutines.tasks.await
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/** 기기 등록. 실패하면 WorkManager가 다시 시도한다 (docs/01 6.3). */
class RegistrationWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val graph = App.graph
        if (!graph.session.isSignedIn) return Result.success()
        return try {
            val token = FirebaseMessaging.getInstance().token.await()
            val zone = ZoneId.systemDefault().id
            graph.api.registerDevice(
                graph.registration.installationId(),
                RegisterDeviceRequest(
                    fcmToken = token,
                    model = android.os.Build.MODEL,
                    appVersion = BuildConfig.VERSION_CODE,
                    timeZone = zone,
                ),
            )
            graph.registration.markRegistered(System.currentTimeMillis(), BuildConfig.VERSION_CODE, zone)
            Result.success()
        } catch (e: ApiException) {
            if (e.isAccessDenied) {
                graph.onAccessDenied(e.code)
                Result.success()
            } else Result.retry()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    companion object {
        private const val NAME = "device-registration"

        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<RegistrationWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(NAME, ExistingWorkPolicy.REPLACE, request)
        }
    }
}
