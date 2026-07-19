import AVFoundation
import Speech
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
    @Binding var showAcquire: Bool
    @Binding var acquireSeed: DiscoverySeed?

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
        Button {
            acquireSeed = seed
            showAcquire = true
        } label: {
            Label("Music to seek out…", systemImage: "binoculars")
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

struct AcquireIdeasSheet: View {
    @ObservedObject var appState: AppState
    let seed: DiscoverySeed?
    @Environment(\.dismiss) private var dismiss
    @State private var loading = true
    @State private var response: AcquireSuggestResponse?
    @State private var errorText: String?

    var body: some View {
        NavigationStack {
            Group {
                if loading {
                    ProgressView("Looking up MusicBrainz…")
                } else if let errorText {
                    ContentUnavailableView("Could not load", systemImage: "exclamationmark.triangle", description: Text(errorText))
                } else if (response?.suggestions ?? []).isEmpty {
                    ContentUnavailableView(
                        "Nothing new found",
                        systemImage: "binoculars",
                        description: Text(response?.note ?? "Your library may already cover this niche.")
                    )
                } else {
                    List(response?.suggestions ?? []) { item in
                        VStack(alignment: .leading, spacing: 4) {
                            HStack {
                                Text(item.name).font(.headline)
                                Spacer()
                                if let urlStr = item.musicbrainzUrl, let url = URL(string: urlStr) {
                                    Link("MusicBrainz", destination: url)
                                        .font(.caption)
                                }
                            }
                            if !item.reasons.isEmpty {
                                Text(item.reasons.prefix(2).joined(separator: " · "))
                                    .font(.caption)
                                    .foregroundStyle(BockColors.muted)
                            }
                            if !item.tags.isEmpty {
                                Text(item.tags.prefix(4).joined(separator: " · "))
                                    .font(.caption2)
                                    .foregroundStyle(BockColors.muted)
                            }
                        }
                    }
                }
            }
            .navigationTitle("Music to seek out")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Close") { dismiss() }
                }
            }
            .task { await load() }
        }
    }

    private func load() async {
        loading = true
        defer { loading = false }
        do {
            if let seed {
                response = try await appState.repository.acquireSuggest(
                    seedKind: seed.kind.rawValue,
                    path: seed.path,
                    album: seed.album,
                    artist: seed.artist,
                    playlistId: seed.playlistId
                )
            } else {
                response = try await appState.repository.acquireExplore()
            }
        } catch {
            errorText = error.localizedDescription
        }
    }
}

struct MixMusePromptSheet: View {
    @ObservedObject var appState: AppState
    let seed: DiscoverySeed?
    var defaultName: String = "Mix Muse"
    var onPlaylistCreated: ((String) -> Void)?
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
            .navigationTitle(defaultName == "Sonic Sage" ? "Sonic Sage" : "Mix Muse")
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
                    name: defaultName,
                    maxTracks: 25,
                    save: true
                )
            }
            let pid = resp.playlistId ?? resp.id ?? ""
            let name = resp.name ?? defaultName
            dismiss()
            if !pid.isEmpty {
                appState.suggestHomePinPlaylistId = pid
                if let onPlaylistCreated {
                    onPlaylistCreated(pid)
                } else {
                    appState.pendingPlayTarget = .playlist(id: pid, name: name)
                }
            } else {
                appState.toast = "Created \(name)"
            }
        } catch {
            appState.toast = error.localizedDescription
        }
    }
}

@MainActor
final class SpeechInputModel: ObservableObject {
    @Published var transcript = ""
    @Published var isListening = false
    @Published var error: String?

    private let recognizer = SFSpeechRecognizer(locale: Locale(identifier: "en-US"))
    private var request: SFSpeechAudioBufferRecognitionRequest?
    private var task: SFSpeechRecognitionTask?
    private let engine = AVAudioEngine()

    func requestPermissions() async -> Bool {
        let speech = await withCheckedContinuation { cont in
            SFSpeechRecognizer.requestAuthorization { status in
                cont.resume(returning: status == .authorized)
            }
        }
        guard speech else {
            error = "Speech recognition permission denied"
            return false
        }
        let mic = await AVAudioApplication.requestRecordPermission()
        if !mic {
            error = "Microphone permission denied"
        }
        return mic
    }

    func startListening() async {
        error = nil
        guard await requestPermissions() else { return }
        guard let recognizer, recognizer.isAvailable else {
            error = "Speech recognition unavailable"
            return
        }
        stopListening()
        transcript = ""
        isListening = true
        request = SFSpeechAudioBufferRecognitionRequest()
        request?.shouldReportPartialResults = true
        let inputNode = engine.inputNode
        let format = inputNode.outputFormat(forBus: 0)
        inputNode.installTap(onBus: 0, bufferSize: 1024, format: format) { [weak self] buffer, _ in
            self?.request?.append(buffer)
        }
        engine.prepare()
        do {
            try engine.start()
        } catch {
            isListening = false
            self.error = error.localizedDescription
            return
        }
        task = recognizer.recognitionTask(with: request!) { [weak self] result, err in
            guard let self else { return }
            if let result {
                self.transcript = result.bestTranscription.formattedString
            }
            if err != nil || result?.isFinal == true {
                self.stopListening()
            }
        }
    }

    func stopListening() {
        if engine.isRunning {
            engine.stop()
            engine.inputNode.removeTap(onBus: 0)
        }
        request?.endAudio()
        task?.cancel()
        request = nil
        task = nil
        isListening = false
    }
}

struct ListenAgentView: View {
    @ObservedObject var appState: AppState
    @Environment(\.dismiss) private var dismiss
    @StateObject private var speech = SpeechInputModel()
    @State private var prompt = ""
    @State private var working = false
    @State private var errorText: String?
    @State private var modeLabel: String?
    @State private var didAutoStart = false
    @State private var autoPlayPending = false
    var autoStartListening: Bool = true
    var initialPrompt: String? = nil
    var autoSubmitPrompt: Bool = false

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 20) {
                    Text("What would you like to listen to?")
                        .font(.title2.bold())
                        .multilineTextAlignment(.center)
                        .padding(.top, 24)
                    if let modeLabel {
                        Text(modeLabel)
                            .font(.caption.weight(.semibold))
                            .foregroundStyle(BockColors.green)
                    }
                    Text("Try \"play top songs from Steely Dan\" or \"Play the Album Siamese Dream\"")
                        .font(.subheadline)
                        .foregroundStyle(BockColors.muted)
                        .multilineTextAlignment(.center)
                    TextField("Your request", text: $prompt, axis: .vertical)
                        .lineLimit(3...6)
                        .textFieldStyle(.roundedBorder)
                        .disabled(working)
                    Button {
                        Task { await speech.startListening() }
                    } label: {
                        ZStack {
                            Circle()
                                .fill(BockColors.surfaceVariant)
                                .frame(width: 72, height: 72)
                            if speech.isListening {
                                ProgressView()
                            } else {
                                BockIcon(icon: .mic, size: 32)
                            }
                        }
                    }
                    .buttonStyle(.plain)
                    .disabled(working)
                    Text(
                        working ? "Finding music…" : (speech.isListening ? "Listening…" : "Tap the mic to try again")
                    )
                        .font(.caption)
                        .foregroundStyle(BockColors.muted)
                    if let errorText {
                        Text(errorText)
                            .font(.caption)
                            .foregroundStyle(.red)
                            .multilineTextAlignment(.center)
                    }
                    Button {
                        Task { await playRequest() }
                    } label: {
                        Group {
                            if working {
                                ProgressView()
                            } else {
                                Text("Play")
                                    .frame(maxWidth: .infinity)
                            }
                        }
                    }
                    .buttonStyle(.borderedProminent)
                    .tint(BockColors.green)
                    .disabled(working || prompt.trimmingCharacters(in: .whitespaces).isEmpty)
                }
                .padding(24)
            }
            .navigationTitle("Listen")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Close") { dismiss() }
                }
            }
            .onChange(of: speech.transcript) { _, value in
                if !value.isEmpty { prompt = value }
            }
            .onChange(of: speech.isListening) { wasListening, isListening in
                if wasListening && !isListening && !prompt.trimmingCharacters(in: .whitespaces).isEmpty {
                    autoPlayPending = true
                }
            }
            .onChange(of: autoPlayPending) { _, pending in
                if pending { Task { await playRequest() } }
            }
            .onDisappear { speech.stopListening() }
            .task {
                if let st = try? await appState.repository.listenAgentStatus() {
                    let mode = st.mode ?? st.provider ?? "local"
                    modeLabel = switch mode.lowercased() {
                    case "claude": "Powered by Claude"
                    case "openai": "Powered by OpenAI"
                    default: "Local library matching"
                    }
                }
                if let initial = initialPrompt?.trimmingCharacters(in: .whitespacesAndNewlines), !initial.isEmpty, prompt.isEmpty {
                    prompt = initial
                }
                if autoSubmitPrompt, !prompt.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                    await playRequest()
                } else if autoStartListening && !didAutoStart {
                    didAutoStart = true
                    await speech.startListening()
                }
            }
        }
    }

    private func playRequest() async {
        working = true
        errorText = nil
        autoPlayPending = false
        defer { working = false }
        let text = prompt.trimmingCharacters(in: .whitespaces)
        guard !text.isEmpty else { return }
        do {
            let resp = try await appState.repository.listenAgentPlay(prompt: text)
            guard !resp.tracks.isEmpty else {
                errorText = "No tracks found"
                return
            }
            await appState.repository.playDiscoveryTracksLocally(
                resp.tracks,
                title: resp.name ?? "Listen",
                shuffle: resp.shuffle ?? true
            )
            dismiss()
        } catch {
            errorText = error.localizedDescription
        }
    }
}
