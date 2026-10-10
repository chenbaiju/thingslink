package io.thingslink.flutter

import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import java.util.concurrent.Executors

class MainActivity : FlutterActivity() {
    private val storageWorker = Executors.newSingleThreadExecutor()

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        val storage = SecureSessionStore(applicationContext)
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, "thingsx/session_storage")
            .setMethodCallHandler { call, result ->
                if (call.method != "read" && call.method != "write") {
                    result.notImplemented()
                    return@setMethodCallHandler
                }
                // 密钥操作与同步写盘不阻塞界面；失败响应不包含凭据、路径或底层异常。
                storageWorker.execute {
                    try {
                        val key = requireNotNull(call.argument<String>("key"))
                        val value = if (call.method == "read") storage.read(key) else {
                            storage.write(key, requireNotNull(call.argument<String>("value")))
                            null
                        }
                        runOnUiThread { result.success(value) }
                    } catch (_: Exception) {
                        runOnUiThread { result.error("secure_storage", "安全登录信息操作失败", null) }
                    }
                }
            }
    }

    override fun onDestroy() {
        storageWorker.shutdown()
        super.onDestroy()
    }
}
