package com.bockmedia.console.local

import com.bockmedia.console.data.api.dto.HouseholdResponse
import com.bockmedia.console.data.repository.BockMediaRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Server-backed household member list — source of truth is GET /api/household. */
object HouseholdStore {
    private val _revision = MutableStateFlow(0)
    val revision: StateFlow<Int> = _revision.asStateFlow()

    @Volatile
    var cached: HouseholdResponse? = null
        private set

    fun members(): List<com.bockmedia.console.data.api.dto.HouseholdMember> =
        cached?.members.orEmpty()

    fun memberExists(id: String?): Boolean {
        val mid = id?.trim().orEmpty()
        if (mid.isEmpty()) return false
        return members().any { it.id == mid }
    }

    suspend fun refresh(repository: BockMediaRepository): HouseholdResponse {
        val fresh = runCatching { repository.household() }.getOrDefault(HouseholdResponse())
        apply(fresh)
        return fresh
    }

    fun apply(response: HouseholdResponse) {
        val prev = cached
        val changed = prev == null ||
            response.members != prev.members ||
            response.deviceOwners != prev.deviceOwners ||
            response.clientBindings != prev.clientBindings
        cached = response
        if (changed) _revision.value += 1
    }
}
