package eu.kanade.tachiyomi.network

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore

/**
 * [Preference] untuk tes yang butuh aliran perubahan yang nyata. [changes()] memancarkan nilai
 * terakhir satu kali saat seorang kolektor mulai (meniru emisi "ignition" milik AndroidPreference)
 * dan setiap nilai hasil [set()] sesudahnya, termasuk saat nilainya sama.
 *
 * InMemoryPreferenceStore tidak bisa dipakai untuk ini: [changes()]-nya dingin dan hanya memancarkan
 * sekali, jadi ia tidak pernah memberi tahu kolektor tentang sebuah perubahan.
 */
class FlowPreference<T>(
    private val key: String,
    private val initial: T,
) : Preference<T> {

    private val state = MutableStateFlow(initial)

    private val emissions = MutableSharedFlow<T>(replay = 1).apply { tryEmit(initial) }

    override fun key(): String = key

    override fun get(): T = state.value

    override fun set(value: T) {
        state.value = value
        emissions.tryEmit(value)
    }

    override fun isSet(): Boolean = state.value != initial

    override fun delete() {
        set(initial)
    }

    override fun defaultValue(): T = initial

    override fun changes(): Flow<T> = emissions

    override fun stateIn(scope: CoroutineScope): StateFlow<T> = changes().stateIn(scope, SharingStarted.Eagerly, get())
}

/**
 * Store yang dipakai NetworkPreferences di tes: memberi [FlowPreference] yang sama untuk key yang
 * sama, supaya nilai yang diubah tes adalah nilai yang sedang dipantau host.
 */
class FlowPreferenceStore : PreferenceStore {

    private val preferences = mutableMapOf<String, FlowPreference<*>>()

    @Suppress("UNCHECKED_CAST")
    private fun <T> getOrCreate(key: String, initial: T): FlowPreference<T> =
        preferences.getOrPut(key) { FlowPreference(key, initial) } as FlowPreference<T>

    override fun getString(key: String, defaultValue: String): Preference<String> = getOrCreate(key, defaultValue)

    override fun getLong(key: String, defaultValue: Long): Preference<Long> = getOrCreate(key, defaultValue)

    override fun getInt(key: String, defaultValue: Int): Preference<Int> = getOrCreate(key, defaultValue)

    override fun getFloat(key: String, defaultValue: Float): Preference<Float> = getOrCreate(key, defaultValue)

    override fun getBoolean(key: String, defaultValue: Boolean): Preference<Boolean> = getOrCreate(key, defaultValue)

    override fun getStringSet(key: String, defaultValue: Set<String>): Preference<Set<String>> =
        getOrCreate(key, defaultValue)

    override fun <T> getObjectFromString(
        key: String,
        defaultValue: T,
        serializer: (T) -> String,
        deserializer: (String) -> T,
    ): Preference<T> = TODO("tidak dipakai NetworkPreferences")

    override fun <T> getObjectFromInt(
        key: String,
        defaultValue: T,
        serializer: (T) -> Int,
        deserializer: (Int) -> T,
    ): Preference<T> = TODO("tidak dipakai NetworkPreferences")

    override fun <T> getObjectSetFromStringSet(
        key: String,
        defaultValue: Set<T>,
        serializer: (T) -> String,
        deserializer: (String) -> T?,
    ): Preference<Set<T>> = TODO("tidak dipakai NetworkPreferences")

    override fun getAll(): Map<String, *> = preferences
}
