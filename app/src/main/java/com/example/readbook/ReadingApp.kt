package com.example.readbook

import android.app.Application
import com.example.readbook.data.AppContainer

class ReadingApp : Application() {
    val container: AppContainer by lazy { AppContainer(this) }
}
