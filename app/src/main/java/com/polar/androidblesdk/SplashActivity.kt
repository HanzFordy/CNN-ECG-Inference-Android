package com.polar.androidblesdk

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity

class SplashActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash) // Layout Splash Screen

        // Timer untuk pindah ke MainActivity setelah 2 detik
        Handler(Looper.getMainLooper()).postDelayed({
            startActivity(Intent(this, MainActivity::class.java))
            finish() // Tutup SplashActivity agar tidak bisa balik ke sini
        }, 2000) // 2000 ms = 2 detik
    }
}
