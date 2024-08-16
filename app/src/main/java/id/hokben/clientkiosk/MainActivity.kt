package id.hokben.clientkiosk

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Rect
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.databinding.DataBindingUtil
import com.google.android.material.textfield.TextInputEditText
import id.hokben.clientkiosk.databinding.ActivityMainBinding
import pub.devrel.easypermissions.AfterPermissionGranted
import pub.devrel.easypermissions.EasyPermissions


class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val resultLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val data: Intent? = result.data
                startRecordVideoAndShareScreen(data)
            }
        }

    private lateinit var sharedPref: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sharedPref = getPreferences(Context.MODE_PRIVATE)
        binding = DataBindingUtil.setContentView(this, R.layout.activity_main)

        binding.edtIp.setText(sharedPref.getString(getString(R.string.local_ip_key), ""))

        binding.askHelpBtn.setOnClickListener {
            if (binding.edtIp.text.toString().isBlank()) {
                Toast.makeText(this, "Masukkan ip local", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            val builder = AlertDialog.Builder(this)
            builder
                .setTitle("Peringatan!")
                .setMessage("Kamera dan mikrophone kiosk ini akan menyala dan layar anda akan terlihat oleh tim support kami.")
                .setPositiveButton("Ok") { dialog, _ ->
                    askNeededPermissions()
                    binding.askHelpBtn.isEnabled = false
                    dialog.dismiss()
                }
                .setNegativeButton("Batalkan") { dialog, _ ->
                    dialog.cancel()
                }
            // Create the AlertDialog object and return it.
            builder.create().show()
        }
    }

    @AfterPermissionGranted(RC_CALL)
    private fun askNeededPermissions() {
        val perms = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        if (EasyPermissions.hasPermissions(this, *perms)) {
            Log.e("NotError", "MainActivity@askNeededPermissions")
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return
            val mediaProjectionManager =
                getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

            resultLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
        } else {
            EasyPermissions.requestPermissions(this, "Need some permissions", RC_CALL, *perms)
        }
    }

    private fun startRecordVideoAndShareScreen(data: Intent?) {

        ShareScreenAndCameraService.screenPermissionIntent = data
        val intent = Intent(this, ShareScreenAndCameraService::class.java).apply {
            putExtra(
                ShareScreenAndCameraService.IP_EXTRA,
                sharedPref.getString(getString(R.string.local_ip_key), null)
            )
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN) {
            val view = currentFocus
            if (view is TextInputEditText) {
                // unfocuse the input text
                val outRect = Rect()
                view.getGlobalVisibleRect(outRect)
                if (!outRect.contains(event.rawX.toInt(), event.rawY.toInt())) {
                    view.clearFocus()
                    val imm =
                        getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                    imm.hideSoftInputFromWindow(view.getWindowToken(), 0)
                }
                Toast.makeText(this, "Local ip saved.", Toast.LENGTH_LONG)
                    .show()

                with(sharedPref.edit()) {
                    putString(getString(R.string.local_ip_key), view.text.toString())
                    apply()
                }

            }
        }
        return super.dispatchTouchEvent(event)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String?>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        EasyPermissions.onRequestPermissionsResult(requestCode, permissions, grantResults, this)
    }

    companion object {
        private const val RC_CALL = 111
    }


}
