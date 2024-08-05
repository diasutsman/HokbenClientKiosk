package id.hokben.clientkiosk

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import id.hokben.clientkiosk.databinding.ActivityMainBinding
import id.hokben.clientkiosk.service.WebrtcServiceRepository
import id.hokben.clientkiosk.webrtc.SimpleSdpObserver
import io.socket.client.IO
import io.socket.client.Socket
import io.socket.emitter.Emitter
import org.json.JSONException
import org.json.JSONObject
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.Camera1Enumerator
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraEnumerator
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.ScreenCapturerAndroid
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoCapturer
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import java.net.URISyntaxException
import javax.inject.Inject

class ShareScreenAndCameraService : Service() {
    private lateinit var notificationManager: NotificationManager
    override fun onCreate() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            notificationManager = getSystemService(
                NotificationManager::class.java
            )
        }
        super.onCreate()
    }

    private fun startServiceWithNotification(){
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O){
            val notificationChannel = NotificationChannel(
                "channel1","foreground",NotificationManager.IMPORTANCE_HIGH
            )
            notificationManager.createNotificationChannel(notificationChannel)
            val notification = NotificationCompat.Builder(this,"channel1")
                .setSmallIcon(R.mipmap.ic_launcher)

            startForeground(1,notification.build())
        }

    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {

        startServiceWithNotification()
        startVideoCapture()
        return START_STICKY
    }

    override fun onBind(p0: Intent?): IBinder? {
        return null
    }


    private var shareScreenVideoTrack: VideoTrack? = null
    private var socket: Socket? = null
    private var isInitiator = false
    private var isChannelReady = false
    private var isStarted = false

    private var screenCapturer: VideoCapturer? = null

    private val localStreamId = "ARDAMS"

    @Inject
    lateinit var webrtcServiceRepository: WebrtcServiceRepository

    var audioConstraints: MediaConstraints? = null
    var videoConstraints: MediaConstraints? = null
    var sdpConstraints: MediaConstraints? = null
    var videoSource: VideoSource? = null
    var localVideoTrack: VideoTrack? = null
    var audioSource: AudioSource? = null
    var localAudioTrack: AudioTrack? = null
    var surfaceTextureHelper: SurfaceTextureHelper? = null

    private lateinit var binding: ActivityMainBinding;
    private var peerConnection: PeerConnection? = null
    private var rootEglBase: EglBase? = null
    private val peerConnectionFactory: PeerConnectionFactory by lazy { createPeerConnectionFactory() }
    private var videoTrackFromCamera: VideoTrack? = null

    override fun onDestroy() {
        if (socket != null) {
            sendMessage("bye")
            socket!!.disconnect()
        }
        super.onDestroy()
    }

    private fun startVideoCapture() {
        connectToSignallingServer()

        initializeSurfaceViews()

        initializePeerConnectionFactory()

        createVideoTrackFromCameraAndShowIt()

        initializePeerConnections()

        startStreamingVideo()
    }

    private fun connectToSignallingServer() {
        try {
            // For me this was "http://192.168.1.220:3000";
            // $ hostname -I
            val URL =
                BuildConfig.SIGNALING_SERVER_URL // "https://calm-badlands-59575.herokuapp.com/"; //
            Log.e(TAG, "REPLACE ME: IO Socket:$URL")
            socket = IO.socket(URL)

            socket?.on(Socket.EVENT_CONNECT, Emitter.Listener { args: Array<Any?>? ->
                Log.d(TAG, "connectToSignallingServer: connect")
                socket?.emit("create or join", "cuarto")
            })?.on("ipaddr") { args: Array<Any?>? ->
                Log.d(TAG, "connectToSignallingServer: ipaddr")
            }?.on("created") { args: Array<Any?>? ->
                Log.d(TAG, "connectToSignallingServer: created")
                isInitiator = true
            }?.on("full") { args: Array<Any?>? ->
                Log.d(TAG, "connectToSignallingServer: full")
            }?.on("join") { args: Array<Any?>? ->
                Log.d(TAG, "connectToSignallingServer: join")
                Log.d(TAG, "connectToSignallingServer: Another peer made a request to join room")
                Log.d(TAG, "connectToSignallingServer: This peer is the initiator of room")
                isChannelReady = true
            }?.on("joined") { args: Array<Any?>? ->
                Log.d(TAG, "connectToSignallingServer: joined")
                isChannelReady = true
            }?.on("log") { args: Array<Any> ->
                for (arg in args) {
                    Log.d(TAG, "connectToSignallingServer: $arg")
                }
            }?.on("message") { args: Array<Any?>? ->
                Log.d(TAG, "connectToSignallingServer: got a message")
            }?.on("message") { args: Array<Any> ->
                try {
                    if (args[0] is String) {
                        val message = args[0] as String
                        if (message == "got user media") {
                            maybeStart()
                        }
                    } else {
                        val message = args[0] as JSONObject
                        Log.d(TAG, "connectToSignallingServer: got message $message")
                        if (message.getString("type") == "offer") {
                            Log.d(
                                TAG,
                                "connectToSignallingServer: received an offer $isInitiator $isStarted"
                            )
                            if (!isInitiator && !isStarted) {
                                maybeStart()
                            }
                            peerConnection!!.setRemoteDescription(
                                SimpleSdpObserver(),
                                SessionDescription(
                                    SessionDescription.Type.OFFER,
                                    message.getString("sdp")
                                )
                            )
                            doAnswer()
                        } else if (message.getString("type") == "answer" && isStarted) {
                            peerConnection!!.setRemoteDescription(
                                SimpleSdpObserver(),
                                SessionDescription(
                                    SessionDescription.Type.ANSWER,
                                    message.getString("sdp")
                                )
                            )
                        } else if (message.getString("type") == "candidate" && isStarted) {
                            Log.d(TAG, "connectToSignallingServer: receiving candidates")
                            val candidate = IceCandidate(
                                message.getString("id"),
                                message.getInt("label"),
                                message.getString("candidate")
                            )
                            peerConnection!!.addIceCandidate(candidate)
                        }
                        /*else if (message === 'bye' && isStarted) {
                        handleRemoteHangup();
                    }*/
                    }
                } catch (e: JSONException) {
                    e.printStackTrace()
                }
            }?.on(Socket.EVENT_DISCONNECT) { args: Array<Any?>? ->
                Log.d(TAG, "connectToSignallingServer: disconnect")
            }
            socket?.connect()
        } catch (e: URISyntaxException) {
            e.printStackTrace()
        }
    }

    //MirtDPM4
    private fun doAnswer() {
        peerConnection!!.createAnswer(object : SimpleSdpObserver() {
            override fun onCreateSuccess(sessionDescription: SessionDescription) {
                peerConnection!!.setLocalDescription(SimpleSdpObserver(), sessionDescription)
                val message = JSONObject()
                try {
                    message.put("type", "answer")
                    message.put("sdp", sessionDescription.description)
                    sendMessage(message)
                } catch (e: JSONException) {
                    e.printStackTrace()
                }
            }
        }, MediaConstraints())
    }

    private fun maybeStart() {
        Log.d(TAG, "maybeStart: $isStarted $isChannelReady")
        if (!isStarted && isChannelReady) {
            isStarted = true
            if (isInitiator) {
                doCall()
            }
        }
    }

    private fun doCall() {
        val sdpMediaConstraints = MediaConstraints()

        sdpMediaConstraints.mandatory.add(
            MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true")
        )
        sdpMediaConstraints.mandatory.add(
            MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true")
        )
        peerConnection!!.createOffer(object : SimpleSdpObserver() {
            override fun onCreateSuccess(sessionDescription: SessionDescription) {
                Log.d(TAG, "onCreateSuccess: ")
                peerConnection!!.setLocalDescription(SimpleSdpObserver(), sessionDescription)
                val message = JSONObject()
                try {
                    message.put("type", "offer")
                    message.put("sdp", sessionDescription.description)
                    sendMessage(message)
                } catch (e: JSONException) {
                    e.printStackTrace()
                }
            }
        }, sdpMediaConstraints)
    }

    private fun sendMessage(message: Any) {
        socket!!.emit("message", message)
    }

    private fun initializeSurfaceViews() {
        rootEglBase = EglBase.create()

        //        binding.surfaceView.init(rootEglBase.getEglBaseContext(), null);
//        binding.surfaceView.setEnableHardwareScaler(true);
//        binding.surfaceView.setMirror(true);
//        binding!!.surfaceShareCamera.init(rootEglBase?.eglBaseContext, null)
//        binding!!.surfaceShareCamera.setEnableHardwareScaler(true)
//        binding!!.surfaceShareCamera.setMirror(true)

        //add one more
    }

    private fun initializePeerConnectionFactory() {
        val options = PeerConnectionFactory.InitializationOptions.builder(application)
            .setEnableInternalTracer(true).setFieldTrials("WebRTC-H264HighProfile/Enabled/")
            .createInitializationOptions()
        PeerConnectionFactory.initialize(options)
    }

    private fun createScreenCapturer(): VideoCapturer {
        return ScreenCapturerAndroid(
            screenPermissionIntent,
            @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
            object : MediaProjection.Callback() {
                override fun onStop() {
                    super.onStop()
                    Log.d("TAG", "onStop: stopped screen casting permission")
                }
            })
    }


    private fun createVideoTrackFromCameraAndShowIt() {
        Log.i(TAG, "createVideoTrackFromCameraAndShowIt START")
        // Share Screen Capture
        val displayMetrics = DisplayMetrics()
        val windowsManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        windowsManager.defaultDisplay.getMetrics(displayMetrics)

        val screenWidthPixels = displayMetrics.widthPixels
        val screenHeightPixels = displayMetrics.heightPixels

        val shareScreenSurfaceTextureHelper = SurfaceTextureHelper.create(
            "CaptureThread", rootEglBase?.eglBaseContext
        )

        val videoSurfaceTextureHelper = SurfaceTextureHelper.create(
            Thread.currentThread().name, rootEglBase?.eglBaseContext
        )

        val shareScreenVideoSource = peerConnectionFactory.createVideoSource(screenCapturer?.isScreencast == true)
        audioConstraints = MediaConstraints()
        screenCapturer = createScreenCapturer()
        screenCapturer!!.initialize(
            shareScreenSurfaceTextureHelper,
            this,
            shareScreenVideoSource.capturerObserver
        )
        screenCapturer!!.startCapture(screenWidthPixels, screenHeightPixels, 30)

        shareScreenVideoTrack =
            peerConnectionFactory.createVideoTrack(
                VIDEO_TRACK_ID_SHARE_SCREEN,
                shareScreenVideoSource
            )
        shareScreenVideoTrack?.setEnabled(true)


        // Video Capture
        val videoCapturer = createVideoCapturer()
        Log.d(TAG, "Thread.currentThread().name: ${Thread.currentThread().name}")


        videoSource = peerConnectionFactory.createVideoSource(false)
        videoCapturer?.initialize(
            videoSurfaceTextureHelper,
            application,
            videoSource?.capturerObserver
        )
        videoCapturer!!.startCapture(VIDEO_RESOLUTION_WIDTH, VIDEO_RESOLUTION_HEIGHT, FPS)

        videoTrackFromCamera = peerConnectionFactory!!.createVideoTrack(VIDEO_TRACK_ID, videoSource)
        videoTrackFromCamera?.setEnabled(true)

        //        videoTrackFromCamera.addRenderer(new VideoRenderer(binding.surfaceView));

        //create an AudioSource instance
        audioSource = peerConnectionFactory!!.createAudioSource(audioConstraints)
        localAudioTrack = peerConnectionFactory!!.createAudioTrack("101", audioSource)

        Log.i(TAG, "createVideoTrackFromCameraAndShowIt END")
    }

    private fun initializePeerConnections() {
        peerConnection = createPeerConnection()
    }

    /**
     * The One who send the video stream
     */
    private fun startStreamingVideo() {
        val mediaStream = peerConnectionFactory.createLocalMediaStream(localStreamId)
        mediaStream.addTrack(videoTrackFromCamera)
        mediaStream.addTrack(shareScreenVideoTrack)
        mediaStream.addTrack(localAudioTrack)
        peerConnection!!.addStream(mediaStream)
        Log.e("NotError", "MainActivity@startStreamingVideo")
        sendMessage("got user media")
    }


    private fun createPeerConnectionFactory(): PeerConnectionFactory {
        return PeerConnectionFactory.builder().setVideoDecoderFactory(
            DefaultVideoDecoderFactory(rootEglBase?.eglBaseContext)
        ).setVideoEncoderFactory(
            DefaultVideoEncoderFactory(
                rootEglBase?.eglBaseContext, true, true
            )
        ).setOptions(PeerConnectionFactory.Options().apply {
            disableEncryption = false
            disableNetworkMonitor = false
        }).createPeerConnectionFactory()
    }


    private fun createPeerConnection(): PeerConnection? {
        val iceServers = ArrayList<PeerConnection.IceServer>()
        val pcObserver: PeerConnection.Observer = object : PeerConnection.Observer {
            override fun onSignalingChange(signalingState: PeerConnection.SignalingState) {
                Log.d(TAG, "onSignalingChange: ")
            }

            override fun onIceConnectionChange(iceConnectionState: PeerConnection.IceConnectionState) {
                Log.d(TAG, "onIceConnectionChange: ")
            }

            override fun onIceConnectionReceivingChange(b: Boolean) {
                Log.d(TAG, "onIceConnectionReceivingChange: ")
            }

            override fun onIceGatheringChange(iceGatheringState: PeerConnection.IceGatheringState) {
                Log.d(TAG, "onIceGatheringChange: ")
            }

            override fun onIceCandidate(iceCandidate: IceCandidate) {
                Log.d(TAG, "onIceCandidate: ")
                val message = JSONObject()

                try {
                    message.put("type", "candidate")
                    message.put("label", iceCandidate.sdpMLineIndex)
                    message.put("id", iceCandidate.sdpMid)
                    message.put("candidate", iceCandidate.sdp)

                    Log.d(TAG, "onIceCandidate: sending candidate $message")
                    sendMessage(message)
                } catch (e: JSONException) {
                    e.printStackTrace()
                }
            }

            override fun onIceCandidatesRemoved(iceCandidates: Array<IceCandidate>) {
                Log.d(TAG, "onIceCandidatesRemoved: ")
            }

            override fun onAddStream(mediaStream: MediaStream) {
                Log.d(TAG, "onAddStream: " + mediaStream.videoTracks.size)
                val remoteVideoTrack = mediaStream.videoTracks[0]
                val remoteAudioTrack = mediaStream.audioTracks[0]
                remoteAudioTrack.setEnabled(true)
                remoteVideoTrack.setEnabled(true)
//                remoteVideoTrack.addSink(binding.surfaceShareCamera)
            }

            override fun onRemoveStream(mediaStream: MediaStream) {
                Log.d(TAG, "onRemoveStream: ")
            }

            override fun onDataChannel(dataChannel: DataChannel) {
                Log.d(TAG, "onDataChannel: ")
            }

            override fun onRenegotiationNeeded() {
                Log.d(TAG, "onRenegotiationNeeded: ")
            }

            override fun onAddTrack(p0: RtpReceiver?, p1: Array<out MediaStream>?) {

            }
        }

        return peerConnectionFactory.createPeerConnection(
            iceServers, pcObserver
        )
    }

    private fun createVideoCapturer(): VideoCapturer? {
        val videoCapturer = if (useCamera2()) {
            createCameraCapturer(Camera2Enumerator(this))
        } else {
            createCameraCapturer(Camera1Enumerator(true))
        }
        return videoCapturer
    }

    private fun createCameraCapturer(enumerator: CameraEnumerator): VideoCapturer? {
        val deviceNames = enumerator.deviceNames

        for (deviceName in deviceNames) {
            if (enumerator.isFrontFacing(deviceName)) {
                val videoCapturer: VideoCapturer? = enumerator.createCapturer(deviceName, null)

                if (videoCapturer != null) {
                    return videoCapturer
                }
            }
        }

        for (deviceName in deviceNames) {
            if (!enumerator.isFrontFacing(deviceName)) {
                val videoCapturer: VideoCapturer? = enumerator.createCapturer(deviceName, null)

                if (videoCapturer != null) {
                    return videoCapturer
                }
            }
        }

        return null
    }

    private fun useCamera2(): Boolean {
        return Camera2Enumerator.isSupported(this)
    }

    companion object {

        var screenPermissionIntent: Intent? = null
        private const val TAG = "ShareScreenAndCameraSrv"
        const val VIDEO_TRACK_ID: String = "ARDAMSv0"
        const val VIDEO_TRACK_ID_SHARE_SCREEN: String = "ARDAMSv1"
        const val VIDEO_RESOLUTION_WIDTH: Int = 1280
        const val VIDEO_RESOLUTION_HEIGHT: Int = 720
        const val FPS: Int = 30
    }


}