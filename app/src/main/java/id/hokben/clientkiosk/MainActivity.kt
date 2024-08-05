package id.hokben.clientkiosk

import android.Manifest
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.databinding.DataBindingUtil
import dagger.hilt.android.AndroidEntryPoint
import id.hokben.clientkiosk.databinding.ActivityMainBinding
import id.hokben.clientkiosk.service.WebrtcServiceRepository
import pub.devrel.easypermissions.AfterPermissionGranted
import pub.devrel.easypermissions.EasyPermissions
import javax.inject.Inject


@AndroidEntryPoint
class MainActivity : AppCompatActivity() {


    @Inject
    lateinit var webrtcServiceRepository: WebrtcServiceRepository

    private lateinit var binding: ActivityMainBinding;
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = DataBindingUtil.setContentView(this, R.layout.activity_main)


        binding.askHelpBtn.setOnClickListener {

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

            startActivityForResult(
                mediaProjectionManager.createScreenCaptureIntent(), capturePermissionRequestCode
            )
        } else {
            EasyPermissions.requestPermissions(this, "Need some permissions", RC_CALL, *perms)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        Log.e("NotError", "MainActivity@onActivityResult")
        if (requestCode != capturePermissionRequestCode) {
            return
        }

        ShareScreenAndCameraService.screenPermissionIntent = data
        val intent = Intent(this, ShareScreenAndCameraService::class.java)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    companion object {
        private const val RC_CALL = 111
        private const val capturePermissionRequestCode = 1
    }


}
