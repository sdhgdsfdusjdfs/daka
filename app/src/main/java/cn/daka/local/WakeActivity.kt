package cn.daka.local

import android.app.Activity
import android.app.KeyguardManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.TextView

class WakeActivity : Activity() {
    companion object { var current: WakeActivity? = null; private set }
    @Suppress("DEPRECATION") override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); current = this
        android.util.Log.i("Daka", "WakeActivity created")
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD)
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true) }
        setContentView(TextView(this).apply { text = "定时打卡助手\n\n正在唤醒手机…"; textSize = 24f; setPadding(32, 80, 32, 32) })
    }
    override fun onResume() {
        super.onResume()
        android.util.Log.i("Daka", "WakeActivity resumed")
        if (Build.VERSION.SDK_INT >= 26) getSystemService(KeyguardManager::class.java).requestDismissKeyguard(this, null)
    }
    override fun onDestroy() { if (current === this) current = null; super.onDestroy() }
}
