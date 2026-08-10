package com.asia2tv

import android.app.AlertDialog
import android.content.Context
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
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

    // هذه الدالة تنشئ زر الإعدادات في التطبيق بطريقة بسيطة لا تسبب أخطاء بناء
    override fun openSettings(context: Context) {
        val sharedPreferences = context.getSharedPreferences("Asia2TvAuth", Context.MODE_PRIVATE)
        
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 40, 50, 10)
        }

        val userField = EditText(context).apply {
            hint = "اسم المستخدم (Username)"
            setText(sharedPreferences.getString("username", ""))
        }

        val passField = EditText(context).apply {
            hint = "كلمة المرور (Password)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(sharedPreferences.getString("password", ""))
        }

        layout.addView(userField)
        layout.addView(passField)

        AlertDialog.Builder(context)
            .setTitle("تسجيل الدخول - Asia2Tv")
            .setView(layout)
            .setPositiveButton("حفظ البيانات") { _, _ ->
                sharedPreferences.edit()
                    .putString("username", userField.text.toString())
                    .putString("password", passField.text.toString())
                    .apply()
                Toast.makeText(context, "تم الحفظ بنجاح، قم بإعادة فتح التطبيق", Toast.LENGTH_LONG).show()
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }
}
