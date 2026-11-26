package me.weishu.kernelsu.ui.screen.home

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Immutable
import androidx.core.content.pm.PackageInfoCompat

/** 交流群（小尘的猪窝）分享链接，QQ 未安装或 scheme 唤起失败时回退到该地址。 */
private const val QQ_GROUP_SHARE_URL =
    "https://qun.qq.com/universal-share/share?ac=1&authKey=UwVjV%2FCX9FNmQWa7uDmqz5PO6zqXC3rSB4qkvksbbd1Bosd%2BFRvpFrsSqpETa0%2Fg&busi_data=eyJncm91cENvZGUiOiIxMDQ5ODAwNjgyIiwidG9rZW4iOiJuOFU0c2tkMlhxV21qckNScGNjbVE5V1lUdmRNRW9ibWwrd0Zxc01TYStCeTR3dWRvWjFBRko0bURjTGw4SkZXIiwidWluIjoiNTU0NTkwNzc4In0%3D&data=l3QuRNaREUx1Y1_VDaL__ePmVooJvMS9k4ZeSZzpa02gUYbKR10KpkDiepL3VGDppC_OON5pN0SDGhlchwyKXA&svctype=4&tempid=h5_group_info"

/** 直接唤起 QQ 打开群资料卡的 scheme（群号 1049800682）。 */
private const val QQ_GROUP_SCHEME =
    "mqqapi://card/show_pslcard?src_type=internal&version=1&uin=1049800682&card_type=group&source=qrcode"

/**
 * 跳转 QQ 交流群：优先用 mqqapi scheme 直接唤起 QQ，失败再退回分享链接。
 */
fun openQqGroup(context: Context) {
    val direct = Intent(Intent.ACTION_VIEW, Uri.parse(QQ_GROUP_SCHEME))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    if (runCatching { context.startActivity(direct) }.isSuccess) return
    val fallback = Intent(Intent.ACTION_VIEW, Uri.parse(QQ_GROUP_SHARE_URL))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(fallback) }
}

@Immutable
data class ManagerVersion(
    val versionName: String,
    val versionCode: Long
)

@Immutable
data class SystemInfo(
    val kernelVersion: String,
    val managerVersion: String,
    val deviceModel: String,
    val fingerprint: String,
    val selinuxStatus: String,
    val seccompStatus: Int
)

fun getManagerVersion(context: Context): ManagerVersion {
    val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)!!
    val versionCode = PackageInfoCompat.getLongVersionCode(packageInfo)
    return ManagerVersion(
        versionName = packageInfo.versionName!!,
        versionCode = versionCode
    )
}
