package io.legado.app.web

/**
 * Web 服务端口策略（**单源**）。
 *
 * 为什么单独成件：端口合法区间原本在**两处**各写一份，且两份并不一致 ——
 * `WebService.getPort()` 判 `1024..65530`，而「其他设置」页的端口选择器上限写的是 `60000`
 * （2026-09-29 实测 `OtherConfigFragment` 的 `numberAction(max = 60000)`）⇒ 用户通过该选择器
 * **永远设不到 60001~65530**，而服务侧却接受。归一到本件后，消费侧（`WebService` / 设置页
 * 选择器与校验）共用同一区间，且该策略为纯函数 ⇒ 可直接 JVM 单测。
 */
object WebPortPolicy {

    /** 默认端口（未配置或配置非法时的回落值）。 */
    const val DEFAULT = 1122

    /** 端口合法区间（下限避开特权端口，上限避开 65531+ 的保留段）。 */
    val RANGE = 1024..65530

    /** 端口归一：落在 [RANGE] 外一律回落 [DEFAULT]。 */
    fun normalize(raw: Int): Int = if (raw in RANGE) raw else DEFAULT
}
