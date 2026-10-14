package com.kaze.encryption

import android.content.Intent
import android.os.Bundle
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Black screen with presplash.png for 3 seconds, then fades into the app. */
class SplashActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)

        val logo = findViewById<ImageView>(R.id.logo)
        logo.alpha = 0f
        logo.scaleX = 0.88f
        logo.scaleY = 0.88f
        logo.animate()
            .alpha(1f).scaleX(1f).scaleY(1f)
            .setDuration(800)
            .setInterpolator(DecelerateInterpolator())
            .start()

        lifecycleScope.launch {
            delay(SPLASH_MS)
            startActivity(Intent(this@SplashActivity, MainActivity::class.java))
            @Suppress("DEPRECATION")
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
            finish()
        }
    }

    private companion object {
        const val SPLASH_MS = 3000L
    }
}
