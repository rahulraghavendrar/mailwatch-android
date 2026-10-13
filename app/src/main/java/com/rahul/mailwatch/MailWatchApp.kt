package com.rahul.mailwatch

import android.app.Application

class MailWatchApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Store.init(this)
        Notify.createChannels(this)
        if (Store.settings.value.onboarded) Scheduler.start(this)
    }
}
