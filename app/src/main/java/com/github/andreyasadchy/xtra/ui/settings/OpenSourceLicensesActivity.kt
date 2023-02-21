package com.github.andreyasadchy.xtra.ui.settings

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.ui.Utils
import com.github.andreyasadchy.xtra.util.applyTheme
import com.mikepenz.aboutlibraries.LibsBuilder

class OpenSourceLicensesActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyTheme()
        setContentView(R.layout.activity_open_source_licenses)
        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        toolbar.navigationIcon = Utils.getNavigationIcon(this)
        toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
        val fragment = LibsBuilder().supportFragment()
        supportFragmentManager.beginTransaction().replace(R.id.open_source_licenses, fragment).commit()
    }
}