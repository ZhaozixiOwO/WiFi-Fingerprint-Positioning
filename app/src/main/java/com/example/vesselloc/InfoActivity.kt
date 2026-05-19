package com.example.vesselloc

import android.os.Bundle
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

class InfoActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_info)

        val root = findViewById<View>(R.id.root_container)
        applySystemBarInsets(root)

        findViewById<View>(R.id.btn_back).setOnClickListener {
            finish()
        }
    }
}
