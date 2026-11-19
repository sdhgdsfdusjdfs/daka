package cn.daka.local

import android.app.Application

class DakaApp : Application() {
    override fun onCreate() {
        super.onCreate(); instance = this
        config = Config(this); store = Store(this)
    }
    companion object {
        lateinit var instance: DakaApp; private set
        lateinit var config: Config; private set
        lateinit var store: Store; private set
    }
}
