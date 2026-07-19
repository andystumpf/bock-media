import Foundation

/// Loads disk snapshots into in-memory session caches before the first frame paints.
enum SessionDiskHydrator {
    private static var hydrated = false

    static func hydrate() {
        guard !hydrated else { return }
        hydrateHome()
        hydrateLibrary()
        hydrated = true
    }

    private static func hydrateHome() {
        guard HomeFeedCache.peek() == nil else { return }
        let linked = ActiveProfileStore.activeMemberId() != nil
        guard let snap = HomeCachePersistence.load() else { return }
        guard snap.feed.isUsableHomeCache(
            activeProfileLinked: linked,
            hasRatedSongs: snap.hasRatedSongs
        ) else { return }
        HomeArtworkCache.restore(playlistPaths: snap.playlistPaths)
        HomeFeedCache.put(snap.feed, hasRatedSongs: snap.hasRatedSongs)
        HomeLoadCoordinator.markLoaded()
    }

    private static func hydrateLibrary() {
        guard LibrarySessionCache.peek() == nil else { return }
        guard let disk = LibraryCachePersistence.load() else { return }
        LibrarySessionCache.put(disk)
    }
}
