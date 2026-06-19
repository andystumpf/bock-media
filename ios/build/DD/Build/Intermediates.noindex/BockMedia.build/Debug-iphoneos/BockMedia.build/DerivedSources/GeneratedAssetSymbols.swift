import Foundation
#if canImport(DeveloperToolsSupport)
import DeveloperToolsSupport
#endif

#if SWIFT_PACKAGE
private let resourceBundle = Foundation.Bundle.module
#else
private class ResourceBundleClass {}
private let resourceBundle = Foundation.Bundle(for: ResourceBundleClass.self)
#endif

// MARK: - Color Symbols -

@available(iOS 17.0, macOS 14.0, tvOS 17.0, watchOS 10.0, *)
extension DeveloperToolsSupport.ColorResource {

}

// MARK: - Image Symbols -

@available(iOS 17.0, macOS 14.0, tvOS 17.0, watchOS 10.0, *)
extension DeveloperToolsSupport.ImageResource {

    /// The "bock_logo" asset catalog image resource.
    static let bockLogo = DeveloperToolsSupport.ImageResource(name: "bock_logo", bundle: resourceBundle)

    /// The "bock_logo_base" asset catalog image resource.
    static let bockLogoBase = DeveloperToolsSupport.ImageResource(name: "bock_logo_base", bundle: resourceBundle)

    /// The "bock_logo_cap" asset catalog image resource.
    static let bockLogoCap = DeveloperToolsSupport.ImageResource(name: "bock_logo_cap", bundle: resourceBundle)

    /// The "ic_add" asset catalog image resource.
    static let icAdd = DeveloperToolsSupport.ImageResource(name: "ic_add", bundle: resourceBundle)

    /// The "ic_album" asset catalog image resource.
    static let icAlbum = DeveloperToolsSupport.ImageResource(name: "ic_album", bundle: resourceBundle)

    /// The "ic_analytics" asset catalog image resource.
    static let icAnalytics = DeveloperToolsSupport.ImageResource(name: "ic_analytics", bundle: resourceBundle)

    /// The "ic_arrow_back" asset catalog image resource.
    static let icArrowBack = DeveloperToolsSupport.ImageResource(name: "ic_arrow_back", bundle: resourceBundle)

    /// The "ic_auto_awesome" asset catalog image resource.
    static let icAutoAwesome = DeveloperToolsSupport.ImageResource(name: "ic_auto_awesome", bundle: resourceBundle)

    /// The "ic_bedtime" asset catalog image resource.
    static let icBedtime = DeveloperToolsSupport.ImageResource(name: "ic_bedtime", bundle: resourceBundle)

    /// The "ic_block" asset catalog image resource.
    static let icBlock = DeveloperToolsSupport.ImageResource(name: "ic_block", bundle: resourceBundle)

    /// The "ic_bolt" asset catalog image resource.
    static let icBolt = DeveloperToolsSupport.ImageResource(name: "ic_bolt", bundle: resourceBundle)

    /// The "ic_build" asset catalog image resource.
    static let icBuild = DeveloperToolsSupport.ImageResource(name: "ic_build", bundle: resourceBundle)

    /// The "ic_check" asset catalog image resource.
    static let icCheck = DeveloperToolsSupport.ImageResource(name: "ic_check", bundle: resourceBundle)

    /// The "ic_clear" asset catalog image resource.
    static let icClear = DeveloperToolsSupport.ImageResource(name: "ic_clear", bundle: resourceBundle)

    /// The "ic_close" asset catalog image resource.
    static let icClose = DeveloperToolsSupport.ImageResource(name: "ic_close", bundle: resourceBundle)

    /// The "ic_delete" asset catalog image resource.
    static let icDelete = DeveloperToolsSupport.ImageResource(name: "ic_delete", bundle: resourceBundle)

    /// The "ic_download" asset catalog image resource.
    static let icDownload = DeveloperToolsSupport.ImageResource(name: "ic_download", bundle: resourceBundle)

    /// The "ic_download_done" asset catalog image resource.
    static let icDownloadDone = DeveloperToolsSupport.ImageResource(name: "ic_download_done", bundle: resourceBundle)

    /// The "ic_edit" asset catalog image resource.
    static let icEdit = DeveloperToolsSupport.ImageResource(name: "ic_edit", bundle: resourceBundle)

    /// The "ic_favorite" asset catalog image resource.
    static let icFavorite = DeveloperToolsSupport.ImageResource(name: "ic_favorite", bundle: resourceBundle)

    /// The "ic_favorite_border" asset catalog image resource.
    static let icFavoriteBorder = DeveloperToolsSupport.ImageResource(name: "ic_favorite_border", bundle: resourceBundle)

    /// The "ic_folder" asset catalog image resource.
    static let icFolder = DeveloperToolsSupport.ImageResource(name: "ic_folder", bundle: resourceBundle)

    /// The "ic_grid_view" asset catalog image resource.
    static let icGridView = DeveloperToolsSupport.ImageResource(name: "ic_grid_view", bundle: resourceBundle)

    /// The "ic_history" asset catalog image resource.
    static let icHistory = DeveloperToolsSupport.ImageResource(name: "ic_history", bundle: resourceBundle)

    /// The "ic_home" asset catalog image resource.
    static let icHome = DeveloperToolsSupport.ImageResource(name: "ic_home", bundle: resourceBundle)

    /// The "ic_library_music" asset catalog image resource.
    static let icLibraryMusic = DeveloperToolsSupport.ImageResource(name: "ic_library_music", bundle: resourceBundle)

    /// The "ic_list" asset catalog image resource.
    static let icList = DeveloperToolsSupport.ImageResource(name: "ic_list", bundle: resourceBundle)

    /// The "ic_merge" asset catalog image resource.
    static let icMerge = DeveloperToolsSupport.ImageResource(name: "ic_merge", bundle: resourceBundle)

    /// The "ic_mic" asset catalog image resource.
    static let icMic = DeveloperToolsSupport.ImageResource(name: "ic_mic", bundle: resourceBundle)

    /// The "ic_more_horiz" asset catalog image resource.
    static let icMoreHoriz = DeveloperToolsSupport.ImageResource(name: "ic_more_horiz", bundle: resourceBundle)

    /// The "ic_more_vert" asset catalog image resource.
    static let icMoreVert = DeveloperToolsSupport.ImageResource(name: "ic_more_vert", bundle: resourceBundle)

    /// The "ic_music_note" asset catalog image resource.
    static let icMusicNote = DeveloperToolsSupport.ImageResource(name: "ic_music_note", bundle: resourceBundle)

    /// The "ic_new_releases" asset catalog image resource.
    static let icNewReleases = DeveloperToolsSupport.ImageResource(name: "ic_new_releases", bundle: resourceBundle)

    /// The "ic_pause" asset catalog image resource.
    static let icPause = DeveloperToolsSupport.ImageResource(name: "ic_pause", bundle: resourceBundle)

    /// The "ic_person" asset catalog image resource.
    static let icPerson = DeveloperToolsSupport.ImageResource(name: "ic_person", bundle: resourceBundle)

    /// The "ic_phone_android" asset catalog image resource.
    static let icPhoneAndroid = DeveloperToolsSupport.ImageResource(name: "ic_phone_android", bundle: resourceBundle)

    /// The "ic_play_arrow" asset catalog image resource.
    static let icPlayArrow = DeveloperToolsSupport.ImageResource(name: "ic_play_arrow", bundle: resourceBundle)

    /// The "ic_playlist_add" asset catalog image resource.
    static let icPlaylistAdd = DeveloperToolsSupport.ImageResource(name: "ic_playlist_add", bundle: resourceBundle)

    /// The "ic_psychology" asset catalog image resource.
    static let icPsychology = DeveloperToolsSupport.ImageResource(name: "ic_psychology", bundle: resourceBundle)

    /// The "ic_push_pin" asset catalog image resource.
    static let icPushPin = DeveloperToolsSupport.ImageResource(name: "ic_push_pin", bundle: resourceBundle)

    /// The "ic_record_voice_over" asset catalog image resource.
    static let icRecordVoiceOver = DeveloperToolsSupport.ImageResource(name: "ic_record_voice_over", bundle: resourceBundle)

    /// The "ic_refresh" asset catalog image resource.
    static let icRefresh = DeveloperToolsSupport.ImageResource(name: "ic_refresh", bundle: resourceBundle)

    /// The "ic_remove" asset catalog image resource.
    static let icRemove = DeveloperToolsSupport.ImageResource(name: "ic_remove", bundle: resourceBundle)

    /// The "ic_schedule" asset catalog image resource.
    static let icSchedule = DeveloperToolsSupport.ImageResource(name: "ic_schedule", bundle: resourceBundle)

    /// The "ic_search" asset catalog image resource.
    static let icSearch = DeveloperToolsSupport.ImageResource(name: "ic_search", bundle: resourceBundle)

    /// The "ic_settings" asset catalog image resource.
    static let icSettings = DeveloperToolsSupport.ImageResource(name: "ic_settings", bundle: resourceBundle)

    /// The "ic_shuffle" asset catalog image resource.
    static let icShuffle = DeveloperToolsSupport.ImageResource(name: "ic_shuffle", bundle: resourceBundle)

    /// The "ic_skip_next" asset catalog image resource.
    static let icSkipNext = DeveloperToolsSupport.ImageResource(name: "ic_skip_next", bundle: resourceBundle)

    /// The "ic_skip_previous" asset catalog image resource.
    static let icSkipPrevious = DeveloperToolsSupport.ImageResource(name: "ic_skip_previous", bundle: resourceBundle)

    /// The "ic_speaker" asset catalog image resource.
    static let icSpeaker = DeveloperToolsSupport.ImageResource(name: "ic_speaker", bundle: resourceBundle)

    /// The "ic_speaker_group" asset catalog image resource.
    static let icSpeakerGroup = DeveloperToolsSupport.ImageResource(name: "ic_speaker_group", bundle: resourceBundle)

    /// The "ic_star" asset catalog image resource.
    static let icStar = DeveloperToolsSupport.ImageResource(name: "ic_star", bundle: resourceBundle)

    /// The "ic_stop" asset catalog image resource.
    static let icStop = DeveloperToolsSupport.ImageResource(name: "ic_stop", bundle: resourceBundle)

    /// The "ic_volume_down" asset catalog image resource.
    static let icVolumeDown = DeveloperToolsSupport.ImageResource(name: "ic_volume_down", bundle: resourceBundle)

    /// The "ic_volume_up" asset catalog image resource.
    static let icVolumeUp = DeveloperToolsSupport.ImageResource(name: "ic_volume_up", bundle: resourceBundle)

}

