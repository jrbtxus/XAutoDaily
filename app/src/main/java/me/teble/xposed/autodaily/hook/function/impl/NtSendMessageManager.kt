package me.teble.xposed.autodaily.hook.function.impl

import com.tencent.mobileqq.qroute.QRoute
import com.tencent.qqnt.kernel.nativeinterface.IOperateCallback
import com.tencent.qqnt.kernel.nativeinterface.MsgAttributeInfo
import com.tencent.qqnt.kernel.nativeinterface.TextElement
import me.teble.xposed.autodaily.hook.base.load
import me.teble.xposed.autodaily.hook.base.loadAs
import me.teble.xposed.autodaily.hook.function.BaseSendMessage
import me.teble.xposed.autodaily.hook.utils.NtUidUtil
import me.teble.xposed.autodaily.hook.utils.QApplicationUtil.appRuntime
import me.teble.xposed.autodaily.utils.LogUtil
import me.teble.xposed.autodaily.utils.invoke
import me.teble.xposed.autodaily.utils.new
import java.lang.reflect.Method
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit


open class NtSendMessageManager : BaseSendMessage(
    TAG = "NtSendMessageManager"
) {
    private lateinit var msgService: Any
    private lateinit var msgUtilApi: Any
    private lateinit var contactClass: Class<*>

    // NtQQ 8.9.30+
    private var generateMsgUniqueIdMethod: Method? = null
    // lower nt qq
    private var getMsgUniqueIdMethod: Method? = null
    // IKernelMsgService.sendMsg（同样声明在接口上，需用原生 Class.getMethods() 才能找到）
    private var sendMsgMethod: Method? = null

    override fun init() {
        val kernelService = appRuntime.getRuntimeService(loadAs("com.tencent.qqnt.kernel.api.IKernelService"), "all")
        msgService = kernelService.invoke("getMsgService")!!
        // 注意：msgService 是 IKernelMsgService 接口的 JNI 实现，方法声明在接口上。
        // hutool 的 ReflectUtil.getMethodsDirectly 第三参 withSuperInterface 被封装写死为 false，
        // 永不遍历接口，必须用原生 Class.getMethods()（返回公共方法，含父类与接口继承）。
        val msgServiceMethods = msgService.javaClass.methods
        generateMsgUniqueIdMethod = msgServiceMethods.firstOrNull {
            (it.returnType == Long::class.javaObjectType || it.returnType == Long::class.javaPrimitiveType)
                    && it.parameterTypes.size == 1
                    && (it.parameterTypes[0] == Int::class.java || it.parameterTypes[0] == Int::class.javaPrimitiveType)
        }
        generateMsgUniqueIdMethod ?: run {
            getMsgUniqueIdMethod = msgServiceMethods.firstOrNull {
                (it.returnType == Long::class.javaObjectType || it.returnType == Long::class.javaPrimitiveType)
                        && it.parameterTypes.isEmpty()
            }
        }
        sendMsgMethod = msgServiceMethods.firstOrNull {
            it.name == "sendMsg" && it.parameterTypes.size == 5
        }
        if (generateMsgUniqueIdMethod == null && getMsgUniqueIdMethod == null) {
            // 找不到方法时显式失败（而非等到 sendTextMessage 里 NPE），
            // 并把 msgService 的方法列表打出来，便于定位真实签名
            LogUtil.i("NtSendMessageManager init 失败：未找到 generateMsgUniqueId/getMsgUniqueId，msgService 方法列表：")
            msgServiceMethods.forEach { LogUtil.i("    -> $it") }
            throw RuntimeException("没有找到发送消息生成 msgUniqueId 的方法")
        }
        if (sendMsgMethod == null) {
            LogUtil.i("NtSendMessageManager init 失败：未找到 sendMsg，msgService 方法列表：")
            msgServiceMethods.forEach { LogUtil.i("    -> $it") }
            throw RuntimeException("没有找到 sendMsg 方法")
        }

        msgUtilApi = QRoute.api(loadAs("com.tencent.qqnt.msg.api.IMsgUtilApi"))
        contactClass = load("com.tencent.qqnt.kernelpublic.nativeinterface.Contact")
            ?: load("com.tencent.qqnt.kernel.nativeinterface.Contact")!!
    }

    override fun sendTextMessage(uin: String, msg: String, isGroup: Boolean) {
        val chatType = if (isGroup) 2 else 1
        val peerUid = if (isGroup) {
            uin
        } else {
            NtUidUtil.getUidFromUin(uin)
        }
        val guildId = ""
        val contact = contactClass.new(chatType, peerUid, guildId)

        val textElement = TextElement()
        textElement.content = msg
        val msgElement = msgUtilApi.invoke("createTextElement", textElement)!!
        val msgElements = arrayListOf(msgElement)

        val msgUniqueId = getMsgUniqueId(chatType)

        LogUtil.d("msgId: $msgUniqueId, contact: $contact, msgElements: $msgElements")

        val countDownLatch = CountDownLatch(1)

        // 直接用反射调用接口方法（hutool 的 invoke 不遍历接口，会找不到 sendMsg）
        sendMsgMethod!!.invoke(
            msgService,
            msgUniqueId,
            contact,
            msgElements,
            HashMap<Int, MsgAttributeInfo>(),
            object : IOperateCallback {
                override fun onResult(result: Int, msg: String) {
                    countDownLatch.countDown()
                }
            }
        )

        countDownLatch.await(10, TimeUnit.SECONDS)
    }

    private fun getMsgUniqueId(chatType: Int): Long {
        val ret = generateMsgUniqueIdMethod?.invoke(msgService, chatType)
            ?: getMsgUniqueIdMethod?.invoke(msgService)
            ?: throw RuntimeException("生成 msgUniqueId 的方法未初始化")
        return ret as Long
    }
}