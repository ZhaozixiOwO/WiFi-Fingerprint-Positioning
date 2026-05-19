package com.example.vesselloc

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

abstract class BaseActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 在 setContentView 之后调用 applySystemBarInsets()
    }

    protected fun applySystemBarInsets(root: View) {
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val sysBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            // 只做顶部避让：把状态栏高度加到 paddingTop
            v.setPadding(
                v.paddingLeft,
                sysBars.top,
                v.paddingRight,
                v.paddingBottom
            )
            insets
        }
        // 触发一次 insets 分发
        ViewCompat.requestApplyInsets(root)
    }
}
