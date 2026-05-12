package com.cylonid.nativealpha

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.TimePickerDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Process
import android.provider.OpenableColumns
import android.text.Html
import android.text.method.LinkMovementMethod
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.TimePicker
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import com.cylonid.nativealpha.activities.ToolbarBaseActivity
import com.cylonid.nativealpha.databinding.WebappSettingsBinding
import com.cylonid.nativealpha.model.DataManager
import com.cylonid.nativealpha.model.UserScript
import com.cylonid.nativealpha.model.WebApp
import com.cylonid.nativealpha.util.Const
import com.cylonid.nativealpha.util.DateUtils.convertStringToCalendar
import com.cylonid.nativealpha.util.DateUtils.getHourMinFormat
import com.cylonid.nativealpha.util.ProcessUtils.closeAllWebAppsAndProcesses
import com.cylonid.nativealpha.util.Utility
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import java.util.Calendar

class WebAppSettingsActivity : ToolbarBaseActivity<WebappSettingsBinding>() {
    var webappID: Int = -1
    var webapp: WebApp? = null
    private var isGlobalWebApp: Boolean = false
    private var pendingReplaceIndex: Int = -1

    private val pickJsLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) {
            pendingReplaceIndex = -1
            return@registerForActivityResult
        }
        handlePickedJsFile(uri)
    }

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setToolbarTitle(getString(R.string.web_app_settings))

        webappID = intent.getIntExtra(Const.INTENT_WEBAPPID, -1)
        Utility.Assert(webappID != -1, "WebApp ID could not be retrieved.")
        isGlobalWebApp = webappID == DataManager.getInstance().settings.globalWebApp.ID

        if (isGlobalWebApp) {
            webapp = DataManager.getInstance().settings.globalWebApp
            prepareGlobalWebAppScreen()
        } else webapp = DataManager.getInstance().getWebAppIgnoringGlobalOverride(webappID, true)

        if (webapp == null) {
            finish()
            return
        }
        val modifiedWebapp = WebApp(webapp!!)
        modifiedWebapp.migrateLegacyCustomJs()
        binding.webapp = modifiedWebapp
        binding.activity = this@WebAppSettingsActivity

        setupSaveAndCancel(modifiedWebapp)
        setupDarkModeElements()
        setupPlusSettings()
        setupDesktopUserAgentHint()
        setupShortcutButton()
        setupSwitchListeners(webapp!!)
        setupCustomJsSection(modifiedWebapp)
    }

    private fun setupCustomJsSection(webapp: WebApp) {
        binding.btnUploadJs.setOnClickListener {
            pendingReplaceIndex = -1
            launchJsPicker()
        }
        renderCustomJsList(webapp)
    }

    private fun launchJsPicker() {
        try {
            pickJsLauncher.launch(arrayOf("*/*"))
        } catch (e: Exception) {
            Toast.makeText(this, R.string.js_file_read_error, Toast.LENGTH_SHORT).show()
        }
    }

    private fun handlePickedJsFile(uri: Uri) {
        val name = queryDisplayName(uri) ?: "script.js"
        val content = try {
            contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: ""
        } catch (e: Exception) {
            Toast.makeText(this, R.string.js_file_read_error, Toast.LENGTH_SHORT).show()
            pendingReplaceIndex = -1
            return
        }
        val mod = binding.webapp ?: return
        if (pendingReplaceIndex in mod.customJsFiles.indices) {
            val existing = mod.customJsFiles[pendingReplaceIndex]
            existing.name = name
            existing.content = content
        } else {
            mod.customJsFiles.add(UserScript(name, content, true))
        }
        pendingReplaceIndex = -1
        renderCustomJsList(mod)
    }

    private fun queryDisplayName(uri: Uri): String? {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) return c.getString(0)
        }
        return null
    }

    private fun renderCustomJsList(webapp: WebApp) {
        val container = binding.customJsList
        container.removeAllViews()
        binding.customJsEmptyText.visibility = if (webapp.customJsFiles.isEmpty()) View.VISIBLE else View.GONE
        val inflater = LayoutInflater.from(this)
        webapp.customJsFiles.forEachIndexed { index, script ->
            val row = inflater.inflate(R.layout.item_custom_js, container, false)
            row.findViewById<TextView>(R.id.scriptName).text = script.name
            val enabledSwitch = row.findViewById<MaterialSwitch>(R.id.scriptEnabled)
            enabledSwitch.isChecked = script.enabled
            enabledSwitch.setOnCheckedChangeListener { _, checked -> script.enabled = checked }
            row.findViewById<MaterialButton>(R.id.scriptReplace).setOnClickListener {
                pendingReplaceIndex = index
                launchJsPicker()
            }
            row.findViewById<MaterialButton>(R.id.scriptDelete).setOnClickListener {
                AlertDialog.Builder(this)
                    .setTitle(R.string.delete_js_file_title)
                    .setMessage(getString(R.string.delete_js_file_msg, script.name))
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        webapp.customJsFiles.removeAt(index)
                        renderCustomJsList(webapp)
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
            container.addView(row)
        }
    }

    override fun inflateBinding(layoutInflater: LayoutInflater): WebappSettingsBinding {
        return WebappSettingsBinding.inflate(layoutInflater)
    }

    private fun setupSwitchListeners(webapp: WebApp) {
        webapp.onSwitchExpertSettingsChanged(
            binding.switchExpertSettings,
            webapp.isShowExpertSettings
        )
        webapp.onSwitchOverrideGlobalSettingsChanged(
            binding.switchOverrideGlobal,
            webapp.isOverrideGlobalSettings
        )
    }
    
    private fun setupSaveAndCancel(modifiedWebapp: WebApp) {
        binding.btnSave.setOnClickListener {
            val activityManager =
                getSystemService(ACTIVITY_SERVICE) as ActivityManager
            // Global web app => close all webview activities, save to global settings
            if (isGlobalWebApp) {
                closeAllWebAppsAndProcesses(
                    activityManager
                )
                DataManager.getInstance().settings.globalWebApp = modifiedWebapp
                DataManager.getInstance().saveGlobalSettings()
            } else {
                for (task in activityManager.appTasks) {
                    val id = task.taskInfo.baseIntent.getIntExtra(
                        Const.INTENT_WEBAPPID,
                        -1
                    )
                    if (id == webappID) task.finishAndRemoveTask()
                }
                for (processInfo in activityManager.runningAppProcesses) {
                    if (processInfo.processName.contains("web_sandbox_" + modifiedWebapp.containerId)) {
                        Process.killProcess(processInfo.pid)
                    }
                }
                DataManager.getInstance().replaceWebApp(modifiedWebapp)
            }

            val i = Intent(this@WebAppSettingsActivity, MainActivity::class.java)
            i.putExtra(Const.INTENT_WEBAPP_CHANGED, true)
            finish()
            startActivity(i)
        }

        binding.btnCancel.setOnClickListener { finish() }
    }

    private fun setupDarkModeElements() {
        val txtBeginDarkMode = binding.textDarkModeBegin
        val txtEndDarkMode = binding.textDarkModeEnd

        txtBeginDarkMode.setOnClickListener {
            showTimePicker(
                txtBeginDarkMode
            )
        }
        txtEndDarkMode.setOnClickListener {
            showTimePicker(
                txtEndDarkMode
            )
        }

    }

    private fun setupDesktopUserAgentHint() {
        val txt = binding.txthintUserAgent
        txt.text = Html.fromHtml(getString(R.string.hint_user_agent), Html.FROM_HTML_MODE_LEGACY)
        txt.movementMethod = LinkMovementMethod.getInstance()
    }

    private fun setupShortcutButton() {
        binding.btnRecreateShortcut.setOnClickListener {
            val frag = ShortcutDialogFragment.newInstance(webapp)
            frag.show(supportFragmentManager, "SCFetcher-" + webapp!!.ID)
        }
    }

    private fun setupPlusSettings() {
        if (!BuildConfig.FLAVOR.contains("extended")) {
            binding.sectionDarkmode.visibility = View.GONE
            binding.sectionSandbox.visibility = View.GONE
            binding.sectionKioskMode.visibility = View.GONE
            binding.sectionAccessRestriction.visibility = View.GONE
        }
    }

    private fun showTimePicker(txtField: EditText) {
        val c = convertStringToCalendar(txtField.text.toString())
        val timePickerDialog = TimePickerDialog(
            this@WebAppSettingsActivity, R.style.AppTheme,
            { timePicker: TimePicker?, selectedHour: Int, selectedMinute: Int ->
                val datetime = Calendar.getInstance()
                datetime[Calendar.HOUR_OF_DAY] = selectedHour
                datetime[Calendar.MINUTE] = selectedMinute
                txtField.setText(getHourMinFormat().format(datetime.time))
            }, c!![Calendar.HOUR_OF_DAY], c[Calendar.MINUTE], true
        )
        timePickerDialog.show()
    }

    private fun prepareGlobalWebAppScreen() {
        binding.btnRecreateShortcut.visibility = View.GONE
        binding.labelWebAppName.visibility = View.GONE
        binding.txtWebAppName.visibility = View.GONE
        binding.switchOverrideGlobal.visibility = View.GONE
        binding.sectionSSL.visibility = View.GONE
        binding.sectionSandbox.visibility = View.GONE
        binding.labelTitle.visibility = View.GONE
        binding.labelEditableBaseUrl.visibility = View.GONE
        binding.textBaseUrl.visibility = View.GONE

        binding.globalSettingsInfoText.visibility = View.VISIBLE

        setToolbarTitle(getString(R.string.global_web_app_settings))
    }
}


