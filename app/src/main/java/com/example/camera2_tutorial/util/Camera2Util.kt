package com.example.camera2_tutorial.util

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.*
import android.hardware.camera2.CameraDevice.TEMPLATE_RECORD
import android.media.MediaRecorder
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Surface
import android.widget.Toast
import java.util.*
import kotlin.collections.ArrayList

class Camera2Util(context : Context){
    private val mainContext = context
    private lateinit var previewRequestBuilder: CaptureRequest.Builder
    private var previewSize : Size? = null
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var backgroundThread : HandlerThread? = null
    private var backgroundHandler : Handler? = null
    private var textureView : AutoFitTextureView? = null
    private var videoSize : Size? = null
    private var mediaRecorder = MediaRecorder()
    private var videoPath: String? = null
    private var rotationView : Int? = null

    // 1. Texture에서 SurfaceTexture가 사용 가능 할 때 호출
    @SuppressLint("MissingPermission")
    fun openCamera(view: AutoFitTextureView, mag : CameraManager, orientation : Int, rotation : Int, width: Int, height: Int) {
        Log.d("TAG", "카메라 제어 실행")

        // 카메라 제어를 위한 CameraManager 객체 생성
        try {
            textureView = view
            rotationView = rotation

            // list 0번은 전면, list 1번은 후면
            val cameraId = mag.cameraIdList[0]

            // 카메라 장치의 정보를 가져옴
            val characteristics = mag.getCameraCharacteristics(cameraId)
            val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)

            // 영상 녹화에 사용할 Size 값을 가져옴
            videoSize = chooseVideoSize(map!!.getOutputSizes(MediaRecorder::class.java))

            // SurfaceTexture에 사용할 Size 값을 가져옴
            previewSize = chooseOptimalSize(map.getOutputSizes(SurfaceTexture::class.java), width, height, videoSize!!)

            // 만약 해당 화면이 LANDSCAPE 상태이면
            if (orientation == Configuration.ORIENTATION_LANDSCAPE){
                configureTransform(textureView!!, rotationView!!, width, height)
            }

            // 인자로 넘겨준 cameraId 대상의 카메라 실행 (카메라 상태를 확인하기 위한 stateCallBack 상태를 관찰 함)
            mag.openCamera(cameraId, stateCallBack, null)

        } catch (e : Exception){
            e.stackTrace
        }
    }

    // 1-1. 카메라 프리뷰 사이즈를 설정
    private fun chooseOptimalSize(choices: Array<Size>, width: Int, height: Int, aspectRatio: Size): Size {

        // Collect the supported resolutions that are at least as big as the preview Surface
        val w = aspectRatio.width
        val h = aspectRatio.height
        val bigEnough = choices.filter {
            it.height == it.width * h / w && it.width >= width && it.height >= height }

        // Pick the smallest of those, assuming we found any
        return if (bigEnough.isNotEmpty()) {
            Collections.min(bigEnough, CompareSizesByArea())
        } else {
            choices[0]
        }
    }

    // 1-2. 비디오 사이즈를 설정
    private fun chooseVideoSize(choices: Array<Size>) = choices.firstOrNull {
        it.width == it.height * 16 / 9 && it.width <= 1920 } ?: choices[choices.size - 1]

    // 1-3. 회전 상태 일 때 TextureView 위치 값 다시 세팅
    fun configureTransform(view: AutoFitTextureView, rotation: Int, width: Int, height: Int) {
        val matrix = Matrix()
        val viewRect = RectF(0f, 0f, width.toFloat(), height.toFloat())
        val bufferRect = RectF(0f, 0f, previewSize!!.height.toFloat(), previewSize!!.width.toFloat())
        val centerX = viewRect.centerX()
        val centerY = viewRect.centerY()

        // 기기를 회전 했을 경우
        if (Surface.ROTATION_90 == rotation || Surface.ROTATION_270 == rotation){
            bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY())
            matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL)
            val scale = Math.max(
                height.toFloat() / previewSize!!.height,
                width.toFloat() / previewSize!!.width
            )
            with(matrix){
                postScale(scale, scale, centerX, centerY)
                postRotate((90 * (rotation - 2)).toFloat(), centerX, centerY)
            }
        }

        view.setTransform(matrix)
    }

    // 2. 카메라 상태 확인 콜백
    private val stateCallBack = object : CameraDevice.StateCallback(){
        override fun onOpened(camera: CameraDevice) {
            cameraDevice = camera

            // 미리보기 생성
            startPreview()

            // 뷰 재 설정
            configureTransform(textureView!!, rotationView!!,  textureView!!.width, textureView!!.height)
        }

        override fun onDisconnected(camera: CameraDevice) {
            // 연결 해제되면 cameraDevice 를 닫아줌
            cameraDevice!!.close()
            cameraDevice = null
        }

        override fun onError(camera: CameraDevice, error: Int) {
            // cameraDevice 초기화
            cameraDevice!!.close()
            cameraDevice = null

            Log.e("TAG","Camera onError() : $error")
        }
    }

    // 3, 5-2 카메라 프리뷰 (미리보기) 표시
    private fun startPreview() {
        if (cameraDevice == null) return

        try {
            val texture = textureView!!.surfaceTexture

            // 미리보기를 위한 surface 기본 버퍼의 크기는 카메라 미리보기 크기로 설정
            texture.setDefaultBufferSize(previewSize!!.width, previewSize!!.height)

            // 미리보기를 시작하기 위해 필요한 출력표면인 surface
            val previewSurface = Surface(texture)

            // 미리보기 화면 요청 RequestBuilder
            // 요청의 타겟은 surface
            previewRequestBuilder = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            previewRequestBuilder.addTarget(previewSurface)

            // 미리보기를 보여주기 위해 Session 메서드를 시작
            cameraDevice?.createCaptureSession(listOf(previewSurface), object : CameraCaptureSession.StateCallback(){
                override fun onConfigured(session: CameraCaptureSession) {
                    if (cameraDevice == null) return

                    // 세션 준비완료되면 미리보기를 화면에 뿌려준다.
                    captureSession = session
                    updatePreview()

                    try{
                        captureSession!!.setRepeatingRequest(previewRequestBuilder.build(), null, null)
                    } catch (e : Exception){
                        e.stackTrace
                    }
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                }

            }, backgroundHandler)
        } catch (e : Exception){
            e.stackTrace
        }
    }

    // 3-1. 카메라 프리뷰 재생성
    private fun updatePreview() {
        if (cameraDevice == null) return

        try {
            setUpCaptureRequestBuilder(previewRequestBuilder)
            HandlerThread("CameraPreview").start()
            captureSession?.setRepeatingRequest(previewRequestBuilder.build(),
                null, backgroundHandler)
        } catch (e: CameraAccessException) {
            Log.e("TAG", e.toString())
        }
    }

    // 3-2. 캡쳐 모드 설정 (카메라를 제어하고 메타데이터를 오토로 설정)
    private fun setUpCaptureRequestBuilder(builder: CaptureRequest.Builder?) {
        builder?.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
    }

    // 4. (버튼 클릭 후) 비디오 레코딩 시작
    fun startRecordingVideo(){
        if (cameraDevice == null || !textureView!!.isAvailable) return

        try {
            // 녹화 준비를 위해 프리뷰 세션을 잠시 멈춤
            closeSession()
            // 레코더 설정을 세팅
            setUpMediaRecorder()

            val texture = textureView!!.surfaceTexture.apply {
                setDefaultBufferSize(previewSize!!.width, previewSize!!.height)
            }

            // Surface를 다시 설정하여 mediaRecoder.surface에 추가 (영상 녹화 대상체, 프리뷰 세션 -> 동영상 촬영 세션)
            val previewSurface = Surface(texture)
            val recorderSurface = mediaRecorder.surface

            val surfaces = ArrayList<Surface>().apply {
                add(previewSurface)
                add(recorderSurface)
            }

            // 프리뷰 세션을 새로 지정
            previewRequestBuilder = cameraDevice!!.createCaptureRequest(TEMPLATE_RECORD).apply {
                addTarget(previewSurface)
                addTarget(recorderSurface)
            }

            cameraDevice?.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    captureSession = session
                    updatePreview()
                    // 레코딩 시작
                    mediaRecorder.start()
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    Toast.makeText(mainContext, "Failed", Toast.LENGTH_LONG).show()
                }
            }, backgroundHandler)

        } catch (e : Exception){
            e.stackTrace
        }
    }

    // 4-1, 5-1 현재 세션을 초기화 (프리뷰 세션 -> 녹화 세션), (녹화 세션 -> 프리뷰 세션)
    private fun closeSession() {
        captureSession?.close()
        captureSession = null
    }

    // 4-2. 동영상 입출력 설정을 위한 MediaRecorder 사용
    private fun setUpMediaRecorder() {
        if (videoPath == null){
            videoPath = setVideoPath()
        }

        try{
            mediaRecorder.apply {
                // 설정 순서가 중요
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setVideoSource(MediaRecorder.VideoSource.SURFACE)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setOutputFile(videoPath)
                setVideoEncodingBitRate(10000000)
                setVideoFrameRate(30)
                setVideoSize(videoSize!!.width, videoSize!!.height)
                setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                prepare()
            }
        } catch (e : Exception){
            Log.e("Record Tag", "Exception : $e")
        }

    }

    // 5. (버튼 클릭 후) 비디오 레코딩 종료
    fun stopRecordingVideo(){
        mediaRecorder.stop()
        mediaRecorder.reset()

        // 저장 확인 토스트 메세지
        Toast.makeText(mainContext, "Video saved : $videoPath", Toast.LENGTH_LONG).show()
        videoPath = null

        // 테스트를 위해 추가
        closeSession()

        // 녹화 종료 후 녹화 세션 -> 프리뷰 세션으로 변경
        startPreview()
    }

    // Return. 파일 저장 위치 생성
    private fun setVideoPath(): String? {
        val filename = "${System.currentTimeMillis()}.mp4"
        val dir = mainContext.getExternalFilesDir(null)

        if (dir == null){
            return filename
        } else {
            return "${dir.absolutePath}/$filename"
        }
    }

    // Close. 카메라 디바이스 초기화 (onPause, onDestroy 경우)
    fun closeCamera() {
        if (cameraDevice != null){
            cameraDevice!!.close()
            cameraDevice = null
        }
    }

    // Thread start. 백그라운드 스레드 (워커스레드)로 동작 설계
    fun startBackgroundThread() {
        backgroundThread = HandlerThread("CameraBackground")
        backgroundThread?.start()
        backgroundHandler = Handler(backgroundThread!!.looper)
    }

    // Thread stop. 백그라운드 스레드 (워커스레드) 동작 중지
    fun stopBackgroundThread(){
        // 워커 스레드를 안전하게 종료
        backgroundThread?.quitSafely()
        try{
            // 백그라운드 스레드가 종료될 때까지 기다림 (종료됬는지 확인 용도)
            backgroundThread?.join()
            // 백그라운드 스레드가 종료되면 핸들러 초기화 (백그라운드 스레드 종료되기 전에 null 발생하면 exception 발생)
            backgroundThread = null
            backgroundHandler = null
        } catch (e : InterruptedException){
            Log.d("TAG", e.toString())
        }
    }
}