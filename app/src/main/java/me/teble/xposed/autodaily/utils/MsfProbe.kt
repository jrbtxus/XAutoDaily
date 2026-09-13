package me.teble.xposed.autodaily.utils

import me.teble.xposed.autodaily.hook.function.proxy.FunctionPool
import me.teble.xposed.autodaily.hook.notification.XANotification
import me.teble.xposed.autodaily.hook.utils.QApplicationUtil
import me.teble.xposed.autodaily.hook.utils.ToastUtil
import me.teble.xposed.autodaily.task.cron.CronUtil
import me.teble.xposed.autodaily.task.exception.TaskTimeoutException

/**
 * MSF 长连接探活。
 *
 * ## 背景
 * QQ 的 MSF 长连接在网络切换（尤其是 VPN 建立/断开）后可能变成「黑洞」：
 * socket 仍被 QQ 视为已连接，但协议请求（OidbSvc / VisitorSvc 等）发出去
 * 收不到任何响应，只能一直等到超时。
 *
 * 此时 HTTP 类任务（QQ打卡、收集卡等，每次新建连接）完全正常，所以外部现象是
 * 「午夜协议类任务（群打卡、好友点赞）全部超时，重启 QQ 才恢复」。
 *
 * ## 本探针做什么
 * 每天 23:55:55 用一次无害的点赞请求探测 MSF 能否真的收到响应；
 * 若超时则提前提醒用户重启 QQ，避免 00:00 的签到任务整晚失败。
 *
 * ## 设计要点
 * - 独立调用 [FunctionPool.favoriteManager]，**不经过任务框架**，
 *   因此不会写 `lastExecTime` / `nextShouldExecTime`，不会把 00:00 的正常任务挤掉。
 * - 目标 uin 取当前登录账号自身，不打扰任何好友；count 固定为 1。
 * - 只判断「有没有响应」，不关心业务返回码：即使服务端返回业务错误，
 *   只要 MSF 有响应就说明长连接正常，因此「当天已点过」不会造成误报。
 * - 探针失败只做提醒，不做自动重连（重连需要调用 QQ 内部接口，超出当前范围）。
 */
object MsfProbe {

    /**
     * 每天 23:55:55 触发。
     * 调度器已开启秒匹配（[CronUtil.setMatchSecond] 为 true），
     * 6 位表达式按 `秒 分 时 日 月 周` 解析。
     */
    private const val PROBE_CRON = "55 55 23 * * *"

    /** 探针在调度器中的任务 ID */
    private const val PROBE_TASK_ID = "msf_probe"

    /**
     * 注册探针定时任务。
     * 必须在 [CronUtil.setMatchSecond] 与 [CronUtil.start] 之后调用
     * （即 [me.teble.xposed.autodaily.utils.TaskExecutor.startCorn] 内部）。
     */
    fun schedule() {
        CronUtil.schedule(PROBE_TASK_ID, PROBE_CRON) {
            probe()
        }
        LogUtil.i("MSF 探针已注册：$PROBE_CRON")
    }

    /**
     * 执行一次探活。
     *
     * @return true 表示 MSF 长连接正常（收到了响应）；false 表示无响应或异常
     */
    fun probe(): Boolean {
        val targetUin = runCatching { QApplicationUtil.currentUin }.getOrNull()
        if (targetUin == null || targetUin == 0L) {
            LogUtil.i("MSF 探活跳过：当前账号未就绪")
            return true
        }

        val favoriteManager = FunctionPool.favoriteManager
        if (!favoriteManager.isInit) {
            LogUtil.e(RuntimeException("FavoriteManager 初始化失败"), "MSF 探活无法执行")
            return false
        }

        return try {
            // 只关心是否收到响应：返回 true/false 都说明 MSF 通了
            favoriteManager.syncFavorite(targetUin, 1)
            LogUtil.i("MSF 探活正常：长连接可用")
            true
        } catch (e: TaskTimeoutException) {
            LogUtil.e(e, "MSF 探活失败：协议请求无响应，长连接疑似失效")
            onMsfDead()
            false
        } catch (e: Throwable) {
            LogUtil.e(e, "MSF 探活异常")
            false
        }
    }

    /**
     * 探活失败时的处理：提醒用户重启 QQ。
     * 自动重连需要调用 QQ 内部接口，暂不在本模块范围内。
     */
    private fun onMsfDead() {
        runCatching {
            XANotification.notify("检测到 QQ 网络长连接异常，00:00 签到可能失败，建议重启 QQ")
        }.onFailure {
            LogUtil.e(it, "MSF 探活提醒发送失败")
        }
        runCatching {
            ToastUtil.send("检测到长连接异常，建议重启 QQ 后再签到")
        }
    }
}
