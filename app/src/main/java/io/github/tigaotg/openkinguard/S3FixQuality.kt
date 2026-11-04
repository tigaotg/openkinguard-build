package io.github.tigaotg.openkinguard

/** Quality targets, not promises of satellite positioning or a person's location. */
internal object S3FixQuality {
    const val FAST_FIX_METERS = 35f
    const val CACHE_MAX_AGE_MILLIS = 15_000L

    fun canUseCacheImmediately(ageMillis: Long, accuracyMeters: Float): Boolean =
        ageMillis in 0..CACHE_MAX_AGE_MILLIS &&
            accuracyMeters.isFinite() && accuracyMeters in 0f..FAST_FIX_METERS

    /** A rough network fix must not end the attempt before GPS can provide a better fix. */
    fun goodEnoughToFinish(accuracyMeters: Float): Boolean =
        accuracyMeters.isFinite() && accuracyMeters > 0f &&
            accuracyMeters <= FAST_FIX_METERS

    fun description(provider: String, accuracyMeters: Float): String = when {
        accuracyMeters >= 300f -> "精度较低，误差可能超过数百米；请在室外开启精确定位重试"
        accuracyMeters >= 100f -> "位置仅供参考；可能来自网络定位，建议等待卫星定位"
        provider != "G" -> "来自网络定位，不等于 GPS 卫星定位"
        else -> "来自 GPS 卫星定位；实际误差仍受环境影响"
    }
}
