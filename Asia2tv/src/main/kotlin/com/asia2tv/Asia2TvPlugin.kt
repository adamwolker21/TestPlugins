package com.asia2tv

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.PreferenceFragmentCompat
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class Asia2TvPlugin : Plugin() {
    companion object {
        var pluginContext: Context? = null
    }

    override fun load(context: Context) {
        pluginContext = context
        registerMainAPI(Asia2Tv())
    }

    // هذه هي الدالة الجديدة لفتح الإعدادات في إصدارات Cloudstream الحديثة
    // حيث نقوم بإرجاع كلاس Fragment الخاص بالإعدادات
    fun settingsFragment() = Asia2TvSettingsFragment::class.java
}

// بناء شاشة الإعدادات بشكل احترافي
class Asia2TvSettingsFragment : PreferenceFragmentCompat() {
    override fun onCreatePreferences(savedInstanceState: String?, rootKey: String?) {
        val context = preferenceManager.context
        val screen = preferenceManager.createPreferenceScreen(context)

        val usernamePref = androidx.preference.EditTextPreference(context).apply {
            key = "asia2tv_username"
            title = "اسم المستخدم أو البريد (Username)"
            summary = "أدخل اسم المستخدم الخاص بك في Asia2tv"
            setDefaultValue("kelly93")
        }

        val passwordPref = androidx.preference.EditTextPreference(context).apply {
            key = "asia2tv_password"
            title = "كلمة المرور (Password)"
            summary = "أدخل كلمة المرور الخاصة بك"
            setDefaultValue("kelly.brown93@")
        }

        screen.addPreference(usernamePref)
        screen.addPreference(passwordPref)
        preferenceScreen = screen
    }
}
