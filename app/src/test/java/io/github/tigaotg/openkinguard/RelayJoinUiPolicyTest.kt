package io.github.tigaotg.openkinguard

import org.junit.Assert.*
import org.junit.Test

class RelayJoinUiPolicyTest {
    @Test fun oldAttemptCannotOverrideFreshApproval() {
        assertFalse(RelayJoinUiPolicy.shouldApply(4, 3))
        assertTrue(RelayJoinUiPolicy.shouldApply(4, 4))
    }

    @Test fun aTransientDisconnectDoesNotPrematurelyCloseJoin() {
        assertFalse(RelayJoinUiPolicy.terminal("扫码连接暂时中断，正在核验授权"))
        assertFalse(RelayJoinUiPolicy.terminal("加入申请已发送，等待路由器主人批准"))
        assertTrue(RelayJoinUiPolicy.terminal("路由器主人已批准，家庭中继地址已保存"))
        assertTrue(RelayJoinUiPolicy.terminal("路由器已批准，但手机未能保存配置"))
        assertTrue(RelayJoinUiPolicy.terminal("加入未完成：路由器主人拒绝"))
        assertTrue(RelayJoinUiPolicy.terminal("申请等待超时：仍未得到认证"))
    }
}
