package org.koitharu.kotatsu.search.ui.suggestion.adapter

import androidx.lifecycle.LifecycleOwner
import coil3.ImageLoader
import coil3.request.error
import coil3.request.fallback
import coil3.request.placeholder
import com.hannesdorfmann.adapterdelegates4.dsl.adapterDelegateViewBinding
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.model.getSummary
import org.koitharu.kotatsu.core.model.getTitle
import org.koitharu.kotatsu.core.parser.favicon.faviconUri
import org.koitharu.kotatsu.core.ui.image.AnimatedFaviconDrawable
import org.koitharu.kotatsu.core.ui.image.FaviconDrawable
import org.koitharu.kotatsu.core.util.ext.enqueueWith
import org.koitharu.kotatsu.core.util.ext.mangaSourceExtra
import org.koitharu.kotatsu.core.util.ext.newImageRequest
import org.koitharu.kotatsu.databinding.ItemSearchSuggestionSourceTipBinding
import org.koitharu.kotatsu.search.ui.suggestion.SearchSuggestionListener
import org.koitharu.kotatsu.search.ui.suggestion.model.SearchSuggestionItem

fun searchSuggestionSourceTipAD(
	coil: ImageLoader,
	lifecycleOwner: LifecycleOwner,
	listener: SearchSuggestionListener,
) =
	adapterDelegateViewBinding<SearchSuggestionItem.SourceTip, SearchSuggestionItem, ItemSearchSuggestionSourceTipBinding>(
		{ inflater, parent -> ItemSearchSuggestionSourceTipBinding.inflate(inflater, parent, false) },
	) {

		binding.root.setOnClickListener {
			listener.onSourceClick(item.source)
		}

		bind {
			binding.textViewTitle.text = item.source.getTitle(context)
			binding.textViewSubtitle.text = item.source.getSummary(context)
			val fallbackIcon = FaviconDrawable(context, R.style.FaviconDrawable_Small, item.source.name)
			binding.imageViewCover.newImageRequest(lifecycleOwner, item.source.faviconUri())?.run {
				fallback(fallbackIcon)
				placeholder(AnimatedFaviconDrawable(context, R.style.FaviconDrawable_Small, item.source.name))
				error(fallbackIcon)
				mangaSourceExtra(item.source)
				enqueueWith(coil)
			}
		}
	}
