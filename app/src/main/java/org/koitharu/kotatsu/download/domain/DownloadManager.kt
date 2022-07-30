package org.koitharu.kotatsu.download.domain

import android.content.Context
import android.webkit.MimeTypeMap
import coil.ImageLoader
import coil.request.ImageRequest
import coil.size.Scale
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Semaphore
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.IOException
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.network.CommonHeaders
import org.koitharu.kotatsu.core.parser.MangaRepository
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.download.ui.service.PausingHandle
import org.koitharu.kotatsu.local.data.PagesCache
import org.koitharu.kotatsu.local.domain.CbzMangaOutput
import org.koitharu.kotatsu.local.domain.LocalMangaRepository
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.util.await
import org.koitharu.kotatsu.utils.ext.deleteAwait
import org.koitharu.kotatsu.utils.ext.printStackTraceDebug
import org.koitharu.kotatsu.utils.ext.referer
import org.koitharu.kotatsu.utils.progress.PausingProgressJob

private const val MAX_FAILSAFE_ATTEMPTS = 2
private const val DOWNLOAD_ERROR_DELAY = 500L
private const val SLOWDOWN_DELAY = 200L

class DownloadManager(
	private val coroutineScope: CoroutineScope,
	private val context: Context,
	private val imageLoader: ImageLoader,
	private val okHttp: OkHttpClient,
	private val cache: PagesCache,
	private val localMangaRepository: LocalMangaRepository,
	private val settings: AppSettings,
) {

	private val coverWidth = context.resources.getDimensionPixelSize(
		androidx.core.R.dimen.compat_notification_large_icon_max_width
	)
	private val coverHeight = context.resources.getDimensionPixelSize(
		androidx.core.R.dimen.compat_notification_large_icon_max_height
	)
	private val semaphore = Semaphore(settings.downloadsParallelism)

	fun downloadManga(
		manga: Manga,
		chaptersIds: LongArray?,
		startId: Int,
	): PausingProgressJob<DownloadState> {
		val stateFlow = MutableStateFlow<DownloadState>(
			DownloadState.Queued(startId = startId, manga = manga, cover = null)
		)
		val pausingHandle = PausingHandle()
		val job = downloadMangaImpl(manga, chaptersIds?.takeUnless { it.isEmpty() }, stateFlow, pausingHandle, startId)
		return PausingProgressJob(job, stateFlow, pausingHandle)
	}

	private fun downloadMangaImpl(
		manga: Manga,
		chaptersIds: LongArray?,
		outState: MutableStateFlow<DownloadState>,
		pausingHandle: PausingHandle,
		startId: Int,
	): Job = coroutineScope.launch(Dispatchers.Default + errorStateHandler(outState)) {
		@Suppress("NAME_SHADOWING")
		var manga = manga
		val chaptersIdsSet = chaptersIds?.toMutableSet()
		val cover = loadCover(manga)
		outState.value = DownloadState.Queued(startId, manga, cover)
		localMangaRepository.lockManga(manga.id)
		semaphore.acquire()
		coroutineContext[WakeLockNode]?.acquire()
		outState.value = DownloadState.Preparing(startId, manga, null)
		val destination = localMangaRepository.getOutputDir()
		checkNotNull(destination) { context.getString(R.string.cannot_find_available_storage) }
		val tempFileName = "${manga.id}_$startId.tmp"
		var output: CbzMangaOutput? = null
		try {
			if (manga.source == MangaSource.LOCAL) {
				manga = localMangaRepository.getRemoteManga(manga) ?: error("Cannot obtain remote manga instance")
			}
			val repo = MangaRepository(manga.source)
			outState.value = DownloadState.Preparing(startId, manga, cover)
			val data = if (manga.chapters.isNullOrEmpty()) repo.getDetails(manga) else manga
			output = CbzMangaOutput.get(destination, data)
			val coverUrl = data.largeCoverUrl ?: data.coverUrl
			downloadFile(coverUrl, data.publicUrl, destination, tempFileName).let { file ->
				output.addCover(file, MimeTypeMap.getFileExtensionFromUrl(coverUrl))
			}
			val chapters = checkNotNull(
				if (chaptersIdsSet == null) {
					data.chapters
				} else {
					data.chapters?.filter { x -> chaptersIdsSet.remove(x.id) }
				}
			) { "Chapters list must not be null" }
			check(chapters.isNotEmpty()) { "Chapters list must not be empty" }
			check(chaptersIdsSet.isNullOrEmpty()) {
				"${chaptersIdsSet?.size} of ${chaptersIds?.size} requested chapters not found in manga"
			}
			for ((chapterIndex, chapter) in chapters.withIndex()) {
				val pages = runFailsafe(outState, pausingHandle) {
					repo.getPages(chapter)
				}
				for ((pageIndex, page) in pages.withIndex()) {
					runFailsafe(outState, pausingHandle) {
						val url = repo.getPageUrl(page)
						val file = cache[url] ?: downloadFile(url, page.referer, destination, tempFileName)
						output.addPage(
							chapter = chapter,
							file = file,
							pageNumber = pageIndex,
							ext = MimeTypeMap.getFileExtensionFromUrl(url)
						)
					}
					outState.value = DownloadState.Progress(
						startId = startId,
						manga = data,
						cover = cover,
						totalChapters = chapters.size,
						currentChapter = chapterIndex,
						totalPages = pages.size,
						currentPage = pageIndex
					)

					if (settings.isDownloadsSlowdownEnabled) {
						delay(SLOWDOWN_DELAY)
					}
				}
			}
			outState.value = DownloadState.PostProcessing(startId, data, cover)
			output.mergeWithExisting()
			output.finalize()
			val localManga = localMangaRepository.getFromFile(output.file)
			outState.value = DownloadState.Done(startId, data, cover, localManga)
		} catch (e: CancellationException) {
			outState.value = DownloadState.Cancelled(startId, manga, cover)
			throw e
		} catch (e: Throwable) {
			e.printStackTraceDebug()
			outState.value = DownloadState.Error(startId, manga, cover, e, false)
		} finally {
			withContext(NonCancellable) {
				output?.cleanup()
				File(destination, tempFileName).deleteAwait()
				coroutineContext[WakeLockNode]?.release()
				semaphore.release()
				localMangaRepository.unlockManga(manga.id)
			}
		}
	}

	private suspend fun <R> runFailsafe(
		outState: MutableStateFlow<DownloadState>,
		pausingHandle: PausingHandle,
		block: suspend () -> R,
	): R {
		var countDown = MAX_FAILSAFE_ATTEMPTS
		failsafe@ while (true) {
			try {
				return block()
			} catch (e: IOException) {
				if (countDown <= 0) {
					val state = outState.value
					outState.value = DownloadState.Error(state.startId, state.manga, state.cover, e, true)
					countDown = MAX_FAILSAFE_ATTEMPTS
					pausingHandle.pause()
					pausingHandle.awaitResumed()
					outState.value = state
				} else {
					countDown--
					delay(DOWNLOAD_ERROR_DELAY)
				}
			}
		}
	}

	private suspend fun downloadFile(url: String, referer: String, destination: File, tempFileName: String): File {
		val request = Request.Builder()
			.url(url)
			.header(CommonHeaders.REFERER, referer)
			.cacheControl(CommonHeaders.CACHE_CONTROL_DISABLED)
			.get()
			.build()
		val call = okHttp.newCall(request)
		val file = File(destination, tempFileName)
		val response = call.clone().await()
		runInterruptible(Dispatchers.IO) {
			file.outputStream().use { out ->
				checkNotNull(response.body).byteStream().copyTo(out)
			}
		}
		return file
	}

	private fun errorStateHandler(outState: MutableStateFlow<DownloadState>) =
		CoroutineExceptionHandler { _, throwable ->
			val prevValue = outState.value
			outState.value = DownloadState.Error(
				startId = prevValue.startId,
				manga = prevValue.manga,
				cover = prevValue.cover,
				error = throwable,
				canRetry = false
			)
		}

	private suspend fun loadCover(manga: Manga) = runCatching {
		imageLoader.execute(
			ImageRequest.Builder(context)
				.data(manga.coverUrl)
				.referer(manga.publicUrl)
				.size(coverWidth, coverHeight)
				.scale(Scale.FILL)
				.build()
		).drawable
	}.getOrNull()

	class Factory(
		private val context: Context,
		private val imageLoader: ImageLoader,
		private val okHttp: OkHttpClient,
		private val cache: PagesCache,
		private val localMangaRepository: LocalMangaRepository,
		private val settings: AppSettings,
	) {

		fun create(coroutineScope: CoroutineScope) = DownloadManager(
			coroutineScope = coroutineScope,
			context = context,
			imageLoader = imageLoader,
			okHttp = okHttp,
			cache = cache,
			localMangaRepository = localMangaRepository,
			settings = settings
		)
	}
}