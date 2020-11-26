package com.example.camera2_tutorial.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.view.TextureView
import androidx.core.app.ActivityCompat
import com.example.camera2_tutorial.R
import com.example.camera2_tutorial.util.AutoFitTextureView
import com.example.camera2_tutorial.util.Camera2Util
import kotlinx.android.synthetic.main.activity_main.*

class MainActivity : AppCompatActivity() {
    private val camera2Util = Camera2Util(this)
    private var mag : CameraManager? = null
    private var orientation : Int? = null
    private var rotation : Int? = null
    // 이걸 변경해줘야 함.
    private var textureView : AutoFitTextureView? = null
    var isRecording : Boolean = false

    // TextureView (Surface) 를 표현하기 위한 리스너 등록
    private var textureListener: TextureView.SurfaceTextureListener = object : TextureView.SurfaceTextureListener{
        override fun onSurfaceTextureAvailable(surface: SurfaceTexture?, width: Int, height: Int) {
            // Texture에서 surfaceTexture가 사용이 가능한 경우 카메라를 실행하는 메서드를 호출
            camera2Util.openCamera(textureView!!, mag!!, orientation!!, rotation!!, width, height)
        }

        override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture?, width: Int, height: Int) {
            camera2Util.configureTransform(textureView!!, rotation!!, width, height)
        }

        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture?): Boolean {
            // 지정된 Texture를 파괴할 때 호출 -> 아마도 프로그램 종료 시 메모리에 남지 않도록 제거하는 것 같음
            return true
        }

        override fun onSurfaceTextureUpdated(surface: SurfaceTexture?) {
        }
    }

    // 앱을 시작하거나 백그라운드에서 다시 활성화 할 때 발생 (onCreate 전)
    override fun onResume() {
        super.onResume()
        camera2Util.startBackgroundThread()

        // 권한 확인 요청
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED
            && ActivityCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
            && ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED){
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA, Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.RECORD_AUDIO), 100)
        }

        mag = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        orientation = resources.configuration.orientation
        rotation = this.windowManager.defaultDisplay.rotation


        if (texture_camera.isAvailable){
            camera2Util.openCamera(textureView!!, mag!!, orientation!!, rotation!!, textureView!!.width, textureView!!.height)
        } else {
            texture_camera.surfaceTextureListener = textureListener
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        textureView = texture_camera

        // 비디오 녹화 버튼 클릭
        btn_record.setOnClickListener {
            if (!isRecording) {
                isRecording = true
                camera2Util.startRecordingVideo()
            } else {
                isRecording = false
                camera2Util.stopRecordingVideo()
            }
        }
    }

    // 앱이 백그라운드로 넘어갔을 때 카메라 제어를 닫기
    override fun onPause() {
        camera2Util.closeCamera()
        camera2Util.stopBackgroundThread()
        super.onPause()
    }

    // 앱을 완전히 종료시 카메라 제어를 닫기
    override fun onDestroy() {
        camera2Util.closeCamera()
        super.onDestroy()
    }
}