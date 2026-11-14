package com.readaloud.app.v2

import android.os.Parcel
import android.os.Parcelable
import java.util.UUID

@kotlinx.parcelize.Parcelize
data class SavedText(
    val title: String,
    val content: String,
    val createdAt: Long,
    val id: String = UUID.randomUUID().toString()
) : Parcelable
