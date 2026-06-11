package com.mamba.picme.features.gallery.components

import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import com.mamba.picme.R
import com.mamba.picme.domain.model.GroupTitleType
import com.mamba.picme.domain.model.GroupedMedia
import com.mamba.picme.agent.core.api.context.MediaAsset
import com.mamba.picme.agent.core.api.context.MediaType

fun resolveGroupTitle(context: Context, group: GroupedMedia): String {
    return when (group.titleType) {
        GroupTitleType.NONE -> group.titleValue
        GroupTitleType.DATE -> group.titleValue
        GroupTitleType.WITH_FACES -> context.getString(R.string.with_faces)
        GroupTitleType.NO_FACES -> context.getString(R.string.no_faces)
        GroupTitleType.PERSON -> context.getString(R.string.person_group, group.titleValue)
        GroupTitleType.LANDSCAPE -> context.getString(R.string.landscape)
        GroupTitleType.SWIMWEAR -> context.getString(R.string.swimwear)
        GroupTitleType.SEXY -> context.getString(R.string.sexy)
    }
}

fun shareMediaAssets(context: Context, assets: List<MediaAsset>) {
    if (assets.isEmpty()) {
        return
    }

    val uris = assets.map { mediaAsset -> mediaAsset.uri.toUri() }
    val shareIntent = if (uris.size == 1) {
        val firstAsset = assets.first()
        Intent(Intent.ACTION_SEND).apply {
            putExtra(Intent.EXTRA_STREAM, uris.first())
            type = if (firstAsset.type == MediaType.VIDEO) "video/*" else "image/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    } else {
        Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            type = "*/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    context.startActivity(Intent.createChooser(shareIntent, null))
}
