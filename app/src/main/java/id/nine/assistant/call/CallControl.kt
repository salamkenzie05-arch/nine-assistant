package id.nine.assistant.call

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.telecom.TelecomManager
import android.telephony.TelephonyManager

/**
 * Menjawab atau menolak telepon yang masuk lewat TelecomManager. Butuh izin Telepon (ANSWER_PHONE_CALLS dan
 * READ_PHONE_STATE) yang diminta Nine saat pertama kali dipakai. Tidak butuh layanan aksesibilitas.
 */
object CallControl {

    enum class Result { DONE, NO_CALL, UNSUPPORTED, FAILED }

    fun requiredPermissions(): List<String> = listOf(Manifest.permission.ANSWER_PHONE_CALLS, Manifest.permission.READ_PHONE_STATE)

    /** 0 = diam, 1 = berdering, 2 = sedang bicara, -1 = tidak diketahui. */
    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission")
    private fun callState(ctx: Context): Int = try {
        (ctx.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager).callState
    } catch (e: Exception) {
        -1
    }

    /** Angkat telepon yang sedang berdering. */
    @SuppressLint("MissingPermission")
    fun accept(ctx: Context): Result {
        val state = callState(ctx)
        if (state == TelephonyManager.CALL_STATE_IDLE) return Result.NO_CALL
        return try {
            val tm = ctx.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
            tm.acceptRingingCall()
            Result.DONE
        } catch (e: SecurityException) {
            Result.FAILED
        } catch (e: Exception) {
            Result.FAILED
        }
    }

    /** Tolak telepon yang berdering, atau akhiri panggilan yang sedang berlangsung. */
    @SuppressLint("MissingPermission")
    fun decline(ctx: Context): Result {
        if (Build.VERSION.SDK_INT < 28) return Result.UNSUPPORTED
        val state = callState(ctx)
        if (state == TelephonyManager.CALL_STATE_IDLE) return Result.NO_CALL
        return try {
            val tm = ctx.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
            if (tm.endCall()) Result.DONE else Result.FAILED
        } catch (e: SecurityException) {
            Result.FAILED
        } catch (e: Exception) {
            Result.FAILED
        }
    }
}
