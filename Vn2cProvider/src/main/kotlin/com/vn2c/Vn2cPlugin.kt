#!/usr/bin/env kotlin

package com.vn2c

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class Vn2cPlugin : Plugin() {
    override fun load(context: Context) {
        // Đăng ký Provider của Vn2c vào ứng dụng
        registerMainAPI(Vn2cProvider())
    }
}