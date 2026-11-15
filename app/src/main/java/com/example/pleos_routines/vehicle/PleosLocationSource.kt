package com.example.pleos_routines.vehicle

import ai.pleos.playground.location.fused.FusedLocation
import ai.pleos.playground.location.fused.listener.FusedLocationListener
import ai.pleos.playground.location.fused.model.LocationData
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.example.pleos_routines.model.GeoPoint

private const val MAX_LOCATION_AGE_MS = 30_000L

internal fun checkedPleosLocation(location: LocationData?, nowMs: Long): GeoPoint? {
    location ?: return null
    if (!location.latitude.isFinite() || location.latitude !in -90.0..90.0 ||
        !location.longitude.isFinite() || location.longitude !in -180.0..180.0) return null
    // Keep the measurement timestamp, including for getLastKnownLocation. An
    // unknown clock domain, zero timestamp, old or future fix is never made fresh.
    if (location.timestampMillis <= 0 || location.timestampMillis > nowMs ||
        nowMs - location.timestampMillis > MAX_LOCATION_AGE_MS) return null
    // FusedLocation 1.1.3 exposes confidence, without a documented meter unit.
    // It does not expose Android Location.accuracy; NaN explicitly means unknown.
    return GeoPoint(location.latitude, location.longitude, Float.NaN, location.timestampMillis)
}

/** Receives real vehicle positions from the official Pleos FusedLocation SDK. */
class PleosLocationSource(context: Context) {
    private val context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var sdk: FusedLocation? = null
    private var listener: FusedLocationListener? = null
    private var active = false
    private var session = 0
    private var latest: GeoPoint? = null
    private var expiresElapsedMs = 0L
    private var onLocation: (GeoPoint?) -> Unit = {}
    private var onStatus: (String) -> Unit = {}
    private var readInFlight = false
    private var readId = 0

    private fun dispatch(token: Int, block: () -> Unit) {
        main.post { if (active && session == token) block() }
    }

    fun start(onLocation: (GeoPoint?) -> Unit, onStatus: (String) -> Unit) {
        stop()
        active = true
        this.onLocation = onLocation
        this.onStatus = onStatus
        onLocation(null)
        onStatus("Pleos 위치 서비스 확인 중")
        val token = session
        try {
            val service = FusedLocation(context)
            if (!service.isSupported()) {
                onStatus("Pleos 차량 위치 서비스 미지원")
                return
            }
            if (context.checkSelfPermission(PERMISSION) != PackageManager.PERMISSION_GRANTED) {
                onStatus("차량 위치 권한이 필요합니다")
                return
            }
            sdk = service
            service.initialize()
            val updates = object : FusedLocationListener {
                override fun onChanged(location: LocationData?) {
                    dispatch(token) { accept(location) }
                }

                override fun onError(exception: Exception) {
                    dispatch(token) { fail(exception) }
                }
            }
            listener = updates
            service.registerFusedLocationCallback(updates)
            onStatus("Pleos 위치 연결 · 실제 좌표 대기 중")
            poll(token)
            main.postDelayed(object : Runnable {
                override fun run() {
                    if (!active || session != token) return
                    if (latest != null && (SystemClock.elapsedRealtime() >= expiresElapsedMs ||
                            checkedTimestampExpired(latest!!.timestampMs))) {
                        invalidate("차량 위치가 30초 이상 갱신되지 않았습니다")
                    }
                    main.postDelayed(this, 1000)
                }
            }, 1000)
            main.postDelayed(object : Runnable {
                override fun run() {
                    if (!active || session != token) return
                    poll(token)
                    main.postDelayed(this, 5000)
                }
            }, 5000)
        } catch (exception: Exception) {
            releaseSdk()
            fail(exception)
        }
    }

    private fun checkedTimestampExpired(timestampMs: Long): Boolean {
        val now = System.currentTimeMillis()
        return timestampMs > now || now - timestampMs > MAX_LOCATION_AGE_MS
    }

    private fun accept(location: LocationData?) {
        val now = System.currentTimeMillis()
        val point = checkedPleosLocation(location, now)
        if (point == null) {
            // A delayed cached reply must not discard a newer, still valid fix.
            if (latest != null && location != null && location.timestampMillis < latest!!.timestampMs) return
            invalidate(if (location == null) "Pleos 차량 위치를 사용할 수 없습니다" else
                "차량 위치가 오래되었거나 유효하지 않습니다")
            return
        }
        if (latest?.let { point.timestampMs <= it.timestampMs } == true) return
        latest = point
        expiresElapsedMs = SystemClock.elapsedRealtime() +
            (MAX_LOCATION_AGE_MS - (now - point.timestampMs))
        onLocation(point)
        val confidence = location?.confidence?.takeIf { it.isFinite() }?.toString() ?: "정보 없음"
        onStatus("Pleos 차량 위치 연결 · 신뢰도 $confidence · 정확도 정보 없음")
    }

    private fun poll(token: Int) {
        val service = sdk ?: return
        if (readInFlight) return
        readInFlight = true
        val request = ++readId
        main.postDelayed({
            if (active && session == token && readId == request && readInFlight) {
                readInFlight = false
                if (latest == null) onStatus("Pleos 차량 위치 응답 대기 · 위치 서비스와 GPS를 확인하세요")
            }
        }, 5000)
        try {
            service.getLastKnownLocation(object : FusedLocationListener {
                override fun onChanged(location: LocationData?) {
                    dispatch(token) {
                        if (request != readId) return@dispatch
                        readInFlight = false
                        accept(location)
                    }
                }

                override fun onError(exception: Exception) {
                    dispatch(token) {
                        if (request != readId) return@dispatch
                        readInFlight = false
                        fail(exception)
                    }
                }
            })
        } catch (exception: Exception) {
            readInFlight = false
            fail(exception)
        }
    }

    private fun invalidate(status: String) {
        latest = null
        expiresElapsedMs = 0
        onLocation(null)
        onStatus(status)
    }

    private fun fail(exception: Exception) {
        Log.w("PleosRoutines", "FusedLocation SDK request failed", exception)
        invalidate(if (exception is SecurityException || exception.message.orEmpty().contains("permission", true))
            "차량 위치 권한이 거부되었습니다" else "Pleos 차량 위치 오류 · 연결과 위치 접근 설정을 확인하세요")
    }

    private fun releaseSdk() {
        val service = sdk
        sdk = null
        listener?.let { callback -> runCatching { service?.unregisterFusedLocationCallback(callback) } }
        listener = null
        runCatching { service?.release() }
    }

    fun stop() {
        active = false
        session++
        main.removeCallbacksAndMessages(null)
        releaseSdk()
        readInFlight = false
        readId++
        latest = null
        expiresElapsedMs = 0
        onLocation(null)
        onLocation = {}
        onStatus = {}
    }

    companion object {
        const val PERMISSION = "pleos.car.permission.FUSED_LOCATION"
    }
}
