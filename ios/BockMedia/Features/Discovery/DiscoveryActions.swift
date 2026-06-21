import SwiftUI

enum DiscoverySeedKind: String {
    case song, album, playlist
}

struct DiscoverySeed {
    let kind: DiscoverySeedKind
    let title: String
    var path: String?
    var album: String?
    var artist: String?
    var playlistId: String?
}

struct DiscoveryContextMenuItems: View {
    @ObservedObject var appState: AppState
    let seed: DiscoverySeed
    @Binding var showMixMuse: Bool
    @Binding var mixMuseSeed: DiscoverySeed?

    var body: some View {
        Button {
            mixMuseSeed = seed
            showMixMuse = true
        } label: {
            Label("Mix Muse playlist…", systemImage: "sparkles")
        }
        Button {
            Task { await runResonanceRadio() }
        } label: {
            Label("Resonance radio", systemImage: "waveform")
        }
        Button {
            Task { await runResonanceMix() }
        } label: {
            Label("Resonance mix (save)", systemImage: "music.note.list")
        }
    }

    private func runResonanceRadio() async {
        do {
            let resp = try await appState.repository.resonanceRadio(
                seedKind: seed.kind.rawValue,
                path: seed.path,
                album: seed.album,
                artist: seed.artist,
                playlistId: seed.playlistId
            )
            await appState.repository.playDiscoveryTracksLocally(resp.tracks, title: resp.name ?? seed.title, shuffle: true)
            appState.toast = "Playing \(resp.name ?? "Resonance radio")"
            appState.showNowPlayingSheet = true
        } catch {
            appState.toast = error.localizedDescription
        }
    }

    private func runResonanceMix() async {
        do {
            let resp = try await appState.repository.resonanceMix(
                seedKind: seed.kind.rawValue,
                path: seed.path,
                album: seed.album,
                artist: seed.artist,
                playlistId: seed.playlistId,
                save: true
            )
            let pid = resp.playlistId ?? resp.id ?? ""
            let name = resp.name ?? "Resonance mix"
            if !pid.isEmpty {
                appState.pendingPlayTarget = .playlist(id: pid, name: name)
            } else {
                appState.toast = "Saved \(name)"
            }
        } catch {
            appState.toast = error.localizedDescription
        }
    }
}

struct MixMusePromptSheet: View {
    @ObservedObject var appState: AppState
    let seed: DiscoverySeed?
    @Environment(\.dismiss) private var dismiss
    @State private var prompt = ""
    @State private var saving = false

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("Describe the vibe…", text: $prompt, axis: .vertical)
                        .lineLimit(3...6)
                } footer: {
                    Text("Mix Muse picks tracks from your library using Claude or OpenAI.")
                }
            }
            .navigationTitle("Mix Muse")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Create") { Task { await create() } }
                        .disabled(saving || prompt.trimmingCharacters(in: .whitespaces).isEmpty)
                }
            }
            .onAppear {
                if prompt.isEmpty, let seed {
                    switch seed.kind {
                    case .song:
                        prompt = "Songs that sound like \"\(seed.title)\""
                    case .album:
                        prompt = "Songs that sound like the album \"\(seed.title)\""
                    case .playlist:
                        prompt = "Songs in the spirit of playlist \"\(seed.title)\""
                    }
                }
            }
        }
    }

    private func create() async {
        saving = true
        defer { saving = false }
        do {
            let resp: AiPlaylistResponse
            if let seed {
                resp = try await appState.repository.mixMuseSimilar(
                    seedKind: seed.kind.rawValue,
                    path: seed.path,
                    album: seed.album,
                    artist: seed.artist,
                    playlistId: seed.playlistId,
                    prompt: prompt,
                    save: true
                )
            } else {
                resp = try await appState.repository.aiPlaylist(
                    prompt: prompt,
                    name: "Mix Muse",
                    maxTracks: 25,
                    save: true
                )
            }
            let pid = resp.playlistId ?? resp.id ?? ""
            let name = resp.name ?? "Mix Muse"
            dismiss()
            if !pid.isEmpty {
                appState.pendingPlayTarget = .playlist(id: pid, name: name)
            } else {
                appState.toast = "Created \(name)"
            }
        } catch {
            appState.toast = error.localizedDescription
        }
    }
}
