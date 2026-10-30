package com.github.andreyasadchy.xtra.ui.common

import androidx.appcompat.content.res.AppCompatResources
import com.github.andreyasadchy.xtra.R
import com.google.android.material.appbar.MaterialToolbar

internal fun MaterialToolbar.showXtraBrand() {
    logo = AppCompatResources.getDrawable(context, R.drawable.ic_xtra_toolbar)
    title = context.getString(R.string.app_name)
}
