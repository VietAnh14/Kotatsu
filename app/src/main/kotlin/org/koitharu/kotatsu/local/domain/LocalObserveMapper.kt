package org.koitharu.kotatsu.local.domain

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.koitharu.kotatsu.core.model.isLocal
import org.koitharu.kotatsu.local.data.index.LocalMangaIndex
import org.koitharu.kotatsu.parsers.model.Manga

abstract class LocalObserveMapper<E : Any, R : Any>(
	private val localMangaIndex: LocalMangaIndex,
	private val limitStep: Int,
) {

	protected suspend fun List<E>.mapToLocal(): List<R> = coroutineScope {
		val dispatcher = Dispatchers.IO.limitedParallelism(6)
		map { item ->
			val m = toManga(item)
			async(dispatcher) {
				val mapped = if (m.isLocal) {
					m
				} else {
					localMangaIndex.get(m.id)?.manga
				}
				mapped?.let { mm -> toResult(item, mm) }
			}
		}.awaitAll().filterNotNull()
	}

	protected abstract fun toManga(e: E): Manga

	protected abstract fun toResult(e: E, manga: Manga): R
}
