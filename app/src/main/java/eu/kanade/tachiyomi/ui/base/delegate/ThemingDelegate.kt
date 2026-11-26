package eu.kanade.tachiyomi.ui.base.delegate

import android.app.Activity
import eu.kanade.domain.ui.model.AppTheme
import eu.kanade.tachiyomi.R
import mihon.app.di.appGraph

interface ThemingDelegate {
    fun applyAppTheme(activity: Activity)

    companion object {
        fun getThemeResIds(appTheme: AppTheme, isAmoled: Boolean): List<Int> {
            return buildList(2) {
                add(themeResources.getOrDefault(appTheme, R.style.Theme_Yomu))
                if (isAmoled) add(R.style.ThemeOverlay_Yomu_Amoled)
            }
        }
    }
}

class ThemingDelegateImpl : ThemingDelegate {
    override fun applyAppTheme(activity: Activity) {
        val uiPreferences = activity.appGraph.uiPreferences
        ThemingDelegate.getThemeResIds(uiPreferences.appTheme.get(), uiPreferences.themeDarkAmoled.get())
            .forEach(activity::setTheme)
    }
}

private val themeResources: Map<AppTheme, Int> = mapOf(
    AppTheme.MONET to R.style.Theme_Yomu_Monet,
    AppTheme.CATPPUCCIN to R.style.Theme_Yomu_Catppuccin,
    AppTheme.TOKYONIGHT to R.style.Theme_Yomu_TokyoNight,
    AppTheme.GREEN_APPLE to R.style.Theme_Yomu_GreenApple,
    AppTheme.LAVENDER to R.style.Theme_Yomu_Lavender,
    AppTheme.MIDNIGHT_DUSK to R.style.Theme_Yomu_MidnightDusk,
    AppTheme.MONOCHROME to R.style.Theme_Yomu_Monochrome,
    AppTheme.NORD to R.style.Theme_Yomu_Nord,
    AppTheme.STRAWBERRY_DAIQUIRI to R.style.Theme_Yomu_StrawberryDaiquiri,
    AppTheme.TAKO to R.style.Theme_Yomu_Tako,
    AppTheme.TEALTURQUOISE to R.style.Theme_Yomu_TealTurquoise,
    AppTheme.YINYANG to R.style.Theme_Yomu_YinYang,
    AppTheme.YOTSUBA to R.style.Theme_Yomu_Yotsuba,
    AppTheme.TIDAL_WAVE to R.style.Theme_Yomu_TidalWave,
)
