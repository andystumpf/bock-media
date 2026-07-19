import SwiftUI
import UIKit

/// Material Icons (baseline/filled) — same set as Android `Icons.Default.*` in Routes.kt and components.
enum BockIcons: String, CaseIterable {
    case home = "ic_home"
    case playArrow = "ic_play_arrow"
    case libraryMusic = "ic_library_music"
    case search = "ic_search"
    case list = "ic_list"
    case schedule = "ic_schedule"
    case album = "ic_album"
    case mic = "ic_mic"
    case musicNote = "ic_music_note"
    case star = "ic_star"
    case download = "ic_download"
    case downloadDone = "ic_download_done"
    case bolt = "ic_bolt"
    case recordVoiceOver = "ic_record_voice_over"
    case speaker = "ic_speaker"
    case speakerGroup = "ic_speaker_group"
    case analytics = "ic_analytics"
    case settings = "ic_settings"
    case person = "ic_person"
    case pause = "ic_pause"
    case skipNext = "ic_skip_next"
    case skipPrevious = "ic_skip_previous"
    case shuffle = "ic_shuffle"
    case phoneAndroid = "ic_phone_android"
    case pushPin = "ic_push_pin"
    case check = "ic_check"
    case clear = "ic_clear"
    case add = "ic_add"
    case delete = "ic_delete"
    case favorite = "ic_favorite"
    case favoriteBorder = "ic_favorite_border"
    case bedtime = "ic_bedtime"
    case history = "ic_history"
    case moreHoriz = "ic_more_horiz"
    case moreVert = "ic_more_vert"
    case block = "ic_block"
    case volumeUp = "ic_volume_up"
    case volumeDown = "ic_volume_down"
    case build = "ic_build"
    case merge = "ic_merge"
    case autoAwesome = "ic_auto_awesome"
    case psychology = "ic_psychology"
    case refresh = "ic_refresh"
    case playlistAdd = "ic_playlist_add"
    case gridView = "ic_grid_view"
    case edit = "ic_edit"
    case remove = "ic_remove"
    case stop = "ic_stop"
    case folder = "ic_folder"
    case newReleases = "ic_new_releases"
    case close = "ic_close"
    case arrowBack = "ic_arrow_back"

    var assetName: String { rawValue }
}

struct BockIcon: View {
    let icon: BockIcons
    var size: CGFloat = 24

    var body: some View {
        Image(icon.assetName)
            .renderingMode(.template)
            .resizable()
            .scaledToFit()
            .frame(width: size, height: size)
    }
}

extension Label where Title == Text, Icon == BockIcon {
    init(_ title: String, icon: BockIcons, size: CGFloat = 24) {
        self.init {
            Text(title)
        } icon: {
            BockIcon(icon: icon, size: size)
        }
    }
}
