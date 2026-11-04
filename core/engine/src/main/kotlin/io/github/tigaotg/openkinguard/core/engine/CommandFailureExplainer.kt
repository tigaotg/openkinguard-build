package io.github.tigaotg.openkinguard.core.engine

import java.io.EOFException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.GeneralSecurityException

/** Only a signed denial proves the remote party rejected a request. */
enum class RemoteLocationDenial {
    NOT_ALLOWED, NO_PERMISSION, FOREGROUND_ONLY, UNAVAILABLE
}

class RemoteLocationDenied(val denial: RemoteLocationDenial) : IllegalStateException(denial.name)

data class CommandFailureExplanation(val code: String, val message: String)

/** Bounded Chinese failure descriptions shared by manual actions and the command journal. */
object CommandFailureExplainer {
    fun explain(error: Throwable): CommandFailureExplanation {
        val causes = generateSequence(error) { it.cause }.take(8).toList()
        val denial = causes.filterIsInstance<RemoteLocationDenied>().firstOrNull()
        if (denial != null) return when (denial.denial) {
            RemoteLocationDenial.NOT_ALLOWED -> CommandFailureExplanation("REMOTE_DENIED",
                "对方已通过签名明确拒绝：尚未授权这条帮助关系查看位置")
            RemoteLocationDenial.NO_PERMISSION -> CommandFailureExplanation("REMOTE_OS_PERMISSION",
                "对方已通过签名明确告知：手机系统定位权限未开启")
            RemoteLocationDenial.FOREGROUND_ONLY -> CommandFailureExplanation("REMOTE_BACKGROUND",
                "对方已通过签名明确告知：需打开 OpenKinGuard 才能响应当前定位请求")
            RemoteLocationDenial.UNAVAILABLE -> CommandFailureExplanation("REMOTE_NO_FIX",
                "对方已通过签名明确告知：暂时未取得有效位置，可能定位开关关闭、信号差或室内无法定位")
        }
        if (causes.any { it is SocketTimeoutException })
            return CommandFailureExplanation("NETWORK_TIMEOUT",
                "通信超时：尚未收到远端回执，可能网络不流畅、对方离线或应答过慢；不能据此判断对方是否授权")
        if (causes.any { it is ConnectException })
            return CommandFailureExplanation("CONNECT_FAILED",
                "连接失败：对方设备没有接受连接；请确认同一局域网、对方在线及应用监听状态")
        if (causes.any { it is NoRouteToHostException || it is UnknownHostException })
            return CommandFailureExplanation("NETWORK_UNREACHABLE",
                "网络不可达：当前找不到对方网络地址或路由；尚不能确定对方是否授权")
        if (causes.any { it is SocketException || it is EOFException })
            return CommandFailureExplanation("CONNECTION_INTERRUPTED",
                "连接中断：对方可能退出、网络切换或连接被系统中止，尚未收到完整签名回执")
        if (causes.any { it is GeneralSecurityException })
            return CommandFailureExplanation("CRYPTO_FAILED",
                "安全验证失败：收到的加密内容或签名不可验证，已拒绝采用该次结果")
        if (causes.any { it is SecurityException })
            return CommandFailureExplanation("LOCAL_PERMISSION",
                "本机系统权限不足或操作被系统阻止，请检查应用权限设置")
        if (causes.any { it is IllegalArgumentException })
            return CommandFailureExplanation("INVALID_COMMAND",
                "参数或签名回执校验未通过：${bounded(error.message)}")
        if (causes.any { it is IllegalStateException })
            return CommandFailureExplanation("NOT_READY",
                bounded(error.message))
        return CommandFailureExplanation("UNEXPECTED", "执行异常：${bounded(error.message)}")
    }

    private fun bounded(text: String?): String = text?.replace(Regex("[\\r\\n\\t]+"), " ")
        ?.trim()?.take(160)?.ifBlank { "原因未提供" } ?: "原因未提供"
}
