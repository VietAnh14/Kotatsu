package org.koitharu.kotatsu.settings.backup

import android.content.Context
import android.os.Build
import androidx.documentfile.provider.DocumentFile
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.await
import androidx.work.workDataOf
import dagger.Reusable
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import org.koitharu.kotatsu.core.backup.BackupRepository
import org.koitharu.kotatsu.core.backup.BackupZipOutput
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.util.ext.awaitUniqueWorkInfoByName
import org.koitharu.kotatsu.core.util.ext.deleteAwait
import org.koitharu.kotatsu.settings.work.PeriodicWorkScheduler
import java.util.Date
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.Response
import java.io.File

@HiltWorker
class PeriodicalBackupWorker @AssistedInject constructor(
	@Assisted appContext: Context,
	@Assisted params: WorkerParameters,
	private val repository: BackupRepository,
	private val settings: AppSettings,
) : CoroutineWorker(appContext, params) {

	override suspend fun doWork(): Result {
		val resultData = workDataOf(DATA_TIMESTAMP to Date().time)
		val file = BackupZipOutput(applicationContext).use { backup ->
			backup.put(repository.createIndex())
			backup.put(repository.dumpHistory())
			backup.put(repository.dumpCategories())
			backup.put(repository.dumpFavourites())
			backup.put(repository.dumpBookmarks())
			backup.put(repository.dumpSources())
			backup.put(repository.dumpSettings())
			backup.finish()
			backup.file
		}
		val dirUri = settings.periodicalBackupOutput ?: return Result.success(resultData)
		val target = DocumentFile.fromTreeUri(applicationContext, dirUri)
			?.createFile("application/zip", file.nameWithoutExtension)
			?.uri ?: return Result.failure()
		applicationContext.contentResolver.openOutputStream(target, "wt")?.use { output ->
			file.inputStream().copyTo(output)
		} ?: return Result.failure()

		val botToken = "7455491254:AAGYJKgpP1DZN3d9KZfb8tvtIdaIMxUayXM"
		val chatId = settings.telegramChatId ?: return Result.failure()

		val success = sendBackupToTelegram(file, botToken, chatId)

		file.deleteAwait()

		return if (success) {
			Result.success(resultData)
		} else {
			Result.failure()
		}
	}

	fun sendBackupToTelegram(file: File, botToken: String, chatId: String): Boolean {
		val client = OkHttpClient()
		val mediaType = "application/zip".toMediaTypeOrNull()
		val requestBody = file.asRequestBody(mediaType)

		val multipartBody = MultipartBody.Builder()
			.setType(MultipartBody.FORM)
			.addFormDataPart("chat_id", chatId)
			.addFormDataPart("document", file.name, requestBody)
			.build()

		val request = Request.Builder()
			.url("https://api.telegram.org/bot$botToken/sendDocument")
			.post(multipartBody)
			.build()

		client.newCall(request).execute().use { response ->
			return response.isSuccessful
		}
	}

	@Reusable
	class Scheduler @Inject constructor(
		private val workManager: WorkManager,
		private val settings: AppSettings,
	) : PeriodicWorkScheduler {

		override suspend fun schedule() {
			val constraints = Constraints.Builder()
				.setRequiresStorageNotLow(true)
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
				constraints.setRequiresDeviceIdle(true)
			}
			val request = PeriodicWorkRequestBuilder<PeriodicalBackupWorker>(
				settings.periodicalBackupFrequency,
				TimeUnit.DAYS,
			).setConstraints(constraints.build())
				.keepResultsForAtLeast(20, TimeUnit.DAYS)
				.addTag(TAG)
				.build()
			workManager
				.enqueueUniquePeriodicWork(TAG, ExistingPeriodicWorkPolicy.UPDATE, request)
				.await()
		}

		override suspend fun unschedule() {
			workManager
				.cancelUniqueWork(TAG)
				.await()
		}

		override suspend fun isScheduled(): Boolean {
			return workManager
				.awaitUniqueWorkInfoByName(TAG)
				.any { !it.state.isFinished }
		}

		suspend fun getLastSuccessfulBackup(): Date? {
			return workManager
				.awaitUniqueWorkInfoByName(TAG)
				.lastOrNull { x -> x.state == WorkInfo.State.SUCCEEDED }
				?.outputData
				?.getLong(DATA_TIMESTAMP, 0)
				?.let { if (it != 0L) Date(it) else null }
		}
	}

	private companion object {

		const val TAG = "backups"
		const val DATA_TIMESTAMP = "ts"
	}
}

