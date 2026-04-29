package com.github.andreyasadchy.xtra.model.ui

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "translate_all_messages")
class TranslatedChannel(
    @PrimaryKey
    val channelId: String,
)