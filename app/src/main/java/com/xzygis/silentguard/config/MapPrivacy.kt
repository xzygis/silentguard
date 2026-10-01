package com.xzygis.silentguard.config

import android.content.Context
import com.amap.api.maps.MapsInitializer

object MapPrivacy {
    fun isAllowed(context: Context): Boolean =
        context.getSharedPreferences("privacy", Context.MODE_PRIVATE).getBoolean("amap_consent", false)

    fun setAllowed(context: Context, allowed: Boolean) {
        check(context.getSharedPreferences("privacy", Context.MODE_PRIVATE).edit()
            .putBoolean("amap_consent", allowed).commit())
        if (allowed) initialize(context)
        else MapsInitializer.updatePrivacyAgree(context, false)
    }

    fun initialize(context: Context): Boolean {
        if (!isAllowed(context)) return false
        MapsInitializer.updatePrivacyShow(context, true, true)
        MapsInitializer.updatePrivacyAgree(context, true)
        return true
    }
}
