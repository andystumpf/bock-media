package com.bockmedia.console.data.api.dto

import kotlinx.serialization.Serializable

@Serializable
data class HouseholdMember(
    val id: String = "",
    val name: String = "",
    val role: String = "kid",
    val color: String? = null,
    val avatar: String? = null,
    val hasPin: Boolean = false,
    val createdAt: Double? = null,
) {
    val isParent: Boolean get() = role == "parent"
}

@Serializable
data class DeviceOwner(
    val deviceId: String = "",
    val deviceName: String? = null,
    val memberId: String? = null,
    val memberName: String? = null,
)

@Serializable
data class ClientBinding(
    val clientDeviceId: String = "",
    val deviceName: String? = null,
    val platform: String? = null,
    val memberId: String? = null,
    val memberName: String? = null,
)

@Serializable
data class HouseholdResponse(
    val members: List<HouseholdMember> = emptyList(),
    val deviceOwners: List<DeviceOwner> = emptyList(),
    val clientBindings: List<ClientBinding> = emptyList(),
)

@Serializable
data class QuietWindow(
    val days: List<Int>? = null,
    val from: String? = null,
    val to: String? = null,
)

@Serializable
data class RoomPolicy(
    val deviceId: String? = null,
    val safe: Boolean = false,
    val allowPlaylistIds: List<String> = emptyList(),
    val allowExplicit: Boolean = true,
    val maxVolume: Int? = null,
    val quietHours: List<QuietWindow> = emptyList(),
    val requireApproval: Boolean = false,
)

@Serializable
data class MemberPlayCount(
    val memberId: String? = null,
    val name: String = "",
    val plays: Int = 0,
)

@Serializable
data class RoomPlayCount(
    val room: String = "",
    val plays: Int = 0,
)

@Serializable
data class PlatformPlayCount(
    val platform: String = "",
    val plays: Int = 0,
)

@Serializable
data class HouseholdAnalytics(
    val totalPlays: Int = 0,
    val members: List<HouseholdMember> = emptyList(),
    val byMember: List<MemberPlayCount> = emptyList(),
    val byRoom: List<RoomPlayCount> = emptyList(),
    val byPlatform: List<PlatformPlayCount> = emptyList(),
    val leaderboard: List<MemberPlayCount> = emptyList(),
)

@Serializable
data class MessageAttach(
    val type: String? = null,
    val id: String? = null,
    val title: String? = null,
)

@Serializable
data class FamilyMessage(
    val id: String = "",
    val fromMemberId: String? = null,
    val fromName: String? = null,
    val toMemberId: String? = null,
    val toName: String? = null,
    val scope: String? = null,
    val text: String? = null,
    val attach: MessageAttach? = null,
    val ts: Double? = null,
    val readBy: List<String> = emptyList(),
)

@Serializable
data class MessagesResponse(
    val items: List<FamilyMessage> = emptyList(),
    val unread: Int = 0,
)
