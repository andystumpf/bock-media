import Foundation

/// Deferred tab prefetch — run from Home after first paint, not on cold boot.
enum TabWarmCoordinator {
    static func warmLibrary(repository: BockMediaRepository) {
        if LibrarySessionCache.getIfFresh() != nil { return }
        Task {
            let memberKey = ActiveProfileStore.activeMemberId() ?? ""
            if let cached = SessionDataStore.peekPlaylists(memberKey: memberKey), !cached.items.isEmpty {
                let playlistItems = cached.items.map { pl in
                    LibraryItem(
                        id: "pl-\(pl.id)",
                        title: pl.name,
                        subtitle: "\(pl.tracks) songs",
                        kind: .playlist,
                        playTarget: .playlist(id: pl.id, name: pl.name),
                        artPath: pl.artPath,
                        playlistId: pl.id
                    )
                }
                LibrarySessionCache.put(LibraryData(
                    playlists: playlistItems,
                    artists: [],
                    albums: [],
                    offline: []
                ))
            }
            let data = await LibraryLoader.loadBuckets(repository: repository)
            LibrarySessionCache.put(data)
            LibraryCachePersistence.save(data)
        }
    }

    static func warmSearchBrowse(repository: BockMediaRepository) {
        if SearchBrowseSessionCache.getIfFresh() != nil { return }
        Task {
            let full = await SearchBrowseLoader.load(repository: repository)
            SearchBrowseSessionCache.put(full)
        }
    }
}
