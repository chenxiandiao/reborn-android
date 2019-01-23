package one.mixin.android.worker

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters
import one.mixin.android.api.ClientErrorException
import one.mixin.android.api.LocalJobException
import one.mixin.android.api.NetworkException
import one.mixin.android.api.ServerErrorException
import one.mixin.android.api.WebSocketException
import java.net.SocketTimeoutException

abstract class BaseWork(
    context: Context,
    parameters: WorkerParameters
) : Worker(context, parameters) {

    override fun doWork(): Result {
        return try {
            onRun()
        } catch (e: Exception) {
            if (shouldRetry(e)) {
                Result.retry()
            } else {
                Result.failure()
            }
        }
    }

    abstract fun onRun(): Result

    private fun shouldRetry(throwable: Throwable): Boolean {
        if (throwable is SocketTimeoutException) {
            return true
        }
        return (throwable as? ServerErrorException)?.shouldRetry()
            ?: ((throwable as? ClientErrorException)?.shouldRetry()
                ?: ((throwable as? NetworkException)?.shouldRetry()
                    ?: ((throwable as? WebSocketException)?.shouldRetry()
                        ?: ((throwable as? LocalJobException)?.shouldRetry() ?: false))))
    }
}
