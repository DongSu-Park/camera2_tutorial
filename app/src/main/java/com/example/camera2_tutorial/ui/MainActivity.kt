package com.example.camera2_tutorial.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Surface
import android.view.TextureView
import androidx.core.app.ActivityCompat
import com.example.camera2_tutorial.R
import kotlinx.android.synthetic.main.activity_main.*

class MainActivity : AppCompatActivity() {
    private lateinit var previewRequestBuilder: CaptureRequest.Builder
    private var cameraDevice: CameraDevice? = null
    private var previewSize : Size? = null
    private var captureSession: CameraCaptureSession? = null

    private var backgroundThread : HandlerThread? = null
    private var backgroundHandler : Handler? = null

    // TextureView (Surface) 를 표현하기 위한 리스너 등록
    private var textureListener: TextureView.SurfaceTextureListener = object : TextureView.SurfaceTextureListener{
        override fun onSurfaceTextureAvailable(surface: SurfaceTexture?, width: Int, height: Int) {
            // Texture에서 surfaceTexture가 사용이 가능한 경우 카메라를 실행하는 메서드를 호출
            openCamera(width, height)
        }

        override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture?, width: Int, height: Int) {
            configureTransform(width, height)
        }

        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture?): Boolean {
            // 지정된 Texture를 파괴할 때 호출 -> 아마도 프로그램 종료 시 메모리에 남지 않도록 제거하는 것 같음
            return true
        }

        override fun onSurfaceTextureUpdated(surface: SurfaceTexture?) {
        }
    }

    // 장치가 회전 했을 경우 카메라 위치 바로 잡기 (Camera2 API 유사)
    private fun configureTransform(width: Int, height: Int) {
        val rotation = this.windowManager.defaultDisplay.rotation
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

        texture_camera.setTransform(matrix)
    }

    // Texture에서 SurfaceTexture가 사용 가능 할 때 호출
    private fun openCamera(width: Int, height: Int) {
        Log.d("TAG", "카메라 제어 실행")

        // 카메라 제어를 위한 CameraManager 객체 생성
        val mag = getSystemService(Context.CAMERA_SERVICE) as CameraManager

        try {
            // list 0번은 전면, list 1번은 후면
            val cameraId = mag.cameraIdList[0]

            // 카메라 장치의 정보를 가져옴
            val characteristics = mag.getCameraCharacteristics(cameraId)
            val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)

            // SurfaceTexture에 사용할 Size 값을 가져옴
            previewSize = map?.getOutputSizes(SurfaceTexture::class.java)?.get(0)

            // 만약 해당 화면이 LANDSCAPE 상태이면
            if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE){
                configureTransform(width, height)
            }

            // 권한 확인 요청
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED
                && ActivityCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED){
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA, Manifest.permission.WRITE_EXTERNAL_STORAGE), 100)
            }

            // 인자로 넘겨준 cameraId 대상의 카메라 실행 (카메라 상태를 확인하기 위한 stateCallBack 상태를 관찰 함)
            mag.openCamera(cameraId, stateCallBack, null)

        } catch (e : Exception){
            e.stackTrace
        }
    }

    // 카메라 상태 확인 콜백
    private val stateCallBack = object : CameraDevice.StateCallback(){
        override fun onOpened(camera: CameraDevice) {
            cameraDevice = camera

            // 미리보기 생성
            startPreview()
        }

        override fun onDisconnected(camera: CameraDevice) {
            // 연결 해제되면 cameraDevice 를 닫아줌
            cameraDevice!!.close()
        }

        override fun onError(camera: CameraDevice, error: Int) {
            // cameraDevice 초기화
            cameraDevice!!.close()
            cameraDevice = null

            Log.e("TAG","Camera onError() : $error")
        }
    }

    // 카메라 프리뷰 (미리보기) 표시
    private fun startPreview() {
        if (cameraDevice == null) return

        try{
            val texture = texture_camera.surfaceTexture

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
                    previewRequestBuilder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)

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

    // 카메라 디바이스 초기화 (카메라 제어 닫기)
    private fun closeCamera() {
        if (cameraDevice != null){
            cameraDevice!!.close()
            cameraDevice = null
        }
    }

    // 백그라운드 스레드 (워커스레드)로 동작 설계
    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("CameraBackground")
        backgroundThread?.start()
        backgroundHandler = Handler(backgroundThread?.looper)
    }

    // 백그라운드 스레드 (워커스레드) 동작 중지
    private fun stopBackgroundThread(){
        backgroundThread?.quitSafely()
        try{
            // 백그라운드 스레드 초기화
            backgroundThread?.join()
            backgroundThread = null
            backgroundHandler = null
        } catch (e : InterruptedException){
            Log.d("TAG", e.toString())
        }
    }

    // 앱을 시작하거나 백그라운드에서 다시 활성화 할 때 발생 (onCreate 전)
    override fun onResume() {
        super.onResume()
        startBackgroundThread()

        // 권한 확인 요청
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED
            && ActivityCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED){
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA, Manifest.permission.WRITE_EXTERNAL_STORAGE), 100)
        }

        // 작업 : MVVM으로 변경 시 상태를 관찰해야하므로 옵저버 패턴 사용
        if (texture_camera.isAvailable){
            openCamera(texture_camera.width, texture_camera.height)
        } else {
            texture_camera.surfaceTextureListener = textureListener
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

    }

    // 앱이 백그라운드로 넘어갔을 때 카메라 제어를 닫기
    override fun onPause() {
        closeCamera()
        stopBackgroundThread()
        super.onPause()
    }

    // 앱을 완전히 종료시 카메라 제어를 닫기
    override fun onDestroy() {
        closeCamera()
        super.onDestroy()
    }
}