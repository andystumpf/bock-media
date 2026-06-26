import SwiftUI
import AuthenticationServices

struct SettingsView: View {
    @ObservedObject var appState: AppState
    @Environment(\.dismiss) private var dismiss

    @State private var health: HealthResponse?
    @State private var summary: SummaryResponse?
    @State private var alexaStatus: AlexaRemoteStatus?
    @State private var loading = true
    @State private var loginMessage: String?
    @State private var watchFolders: [WatchFolder] = []
    @State private var clearingCache = false
    @State private var plex: PlexSyncStatusResponse?
    @State private var configExpanded = false
    @State private var configConfirmed = false
    @State private var configLoading = false
    @State private var defaultPlaylist = ""
    @State private var publicUrl = ""
    @State private var flacSupport = ""
    @State private var transcodeBitrate = ""

    var body: some View {
        List {
            if let health {
                Section("Server health") {
                    healthRow("Backend", health.backend == true)
                    healthRow("Alexa auth", health.alexaAuth == true)
                    if let uptime = health.uptimeSeconds {
                        LabeledContent("Uptime", value: formatUptime(uptime))
                    }
                }
            }
            if let summary {
                Section("Your library") {
                    LabeledContent("Songs", value: "\(summary.songs)")
                    LabeledContent("Playlists", value: "\(summary.playlists)")
                    LabeledContent("Artists", value: "\(summary.artists)")
                }
            }
            Section("Downloads") {
                Toggle("Wi‑Fi only", isOn: Binding(
                    get: { appState.preferences.downloadWifiOnly },
                    set: {
                        appState.preferences.downloadWifiOnly = $0
                        ClientPrefsSync.schedulePush()
                    }
                ))
            }
            Section("This Phone playback") {
                let seconds = appState.preferences.crossfadeSeconds
                VStack(alignment: .leading, spacing: 8) {
                    LabeledContent("Crossfade", value: seconds == 0 ? "Off" : "\(seconds) s")
                    Text(seconds == 0 ? "Hard cut between songs" : "Overlap before each track ends")
                        .font(.caption)
                        .foregroundStyle(BockColors.muted)
                    Slider(
                        value: Binding(
                            get: { Double(appState.preferences.crossfadeSeconds) },
                            set: {
                                appState.preferences.crossfadeSeconds = Int($0.rounded())
                                ClientPrefsSync.schedulePush()
                            }
                        ),
                        in: 0...20,
                        step: 1
                    )
                }
                Picker("When queue ends", selection: Binding(
                    get: { appState.preferences.continueAfterQueue },
                    set: {
                        appState.preferences.continueAfterQueue = $0
                        ClientPrefsSync.schedulePush()
                    }
                )) {
                    Text("Stop").tag("off")
                    Text("Similar songs").tag("similar")
                    Text("Artist radio").tag("artist_radio")
                }
            }
            Section("Watch folders") {
                if watchFolders.isEmpty {
                    Text("No watch folders configured.")
                        .foregroundStyle(BockColors.muted)
                } else {
                    ForEach(watchFolders) { folder in
                        VStack(alignment: .leading, spacing: 4) {
                            Text(folder.label?.nilIfBlank ?? folder.path ?? "Folder")
                            if let path = folder.path, folder.label?.nilIfBlank != nil {
                                Text(path).font(.caption).foregroundStyle(BockColors.muted).lineLimit(2)
                            }
                            HStack(spacing: 12) {
                                if folder.count > 0 { Text("\(folder.count) files").font(.caption2) }
                                if let status = folder.status?.nilIfBlank {
                                    Text(status).font(.caption2).foregroundStyle(BockColors.green)
                                }
                            }
                        }
                    }
                }
            }
            if let plex {
                Section("Plex sync") {
                    LabeledContent("Running", value: plex.running ? "Yes" : "No")
                    if let last = plex.lastSuccess?.nilIfBlank {
                        LabeledContent("Last success", value: last)
                    }
                    if let count = plex.playlistCount {
                        LabeledContent("Playlists", value: "\(count)")
                    }
                    if !plex.logTail.isEmpty {
                        Text(plex.logTail.suffix(3).joined(separator: "\n"))
                            .font(.caption2)
                            .foregroundStyle(BockColors.muted)
                    }
                }
            }
            Section("Server") {
                Button(clearingCache ? "Clearing cache…" : "Clear server cache") {
                    Task { await clearCache() }
                }
                .disabled(clearingCache)
                Toggle("Server config", isOn: $configExpanded)
                if configExpanded {
                    if !configConfirmed {
                        Text("Changes affect the Bock Media server.")
                            .font(.caption)
                            .foregroundStyle(BockColors.muted)
                        Button("Load server settings") { configConfirmed = true }
                    } else if configLoading {
                        BockLoadingLogo(size: 32)
                    } else {
                        TextField("Default playlist name", text: $defaultPlaylist)
                        TextField("Public URL", text: $publicUrl)
                        Text("FLAC: \(flacSupport.isEmpty ? "—" : flacSupport) · Transcode: \(transcodeBitrate.isEmpty ? "—" : transcodeBitrate)")
                            .font(.caption)
                            .foregroundStyle(BockColors.muted)
                        Button("Save server settings") { Task { await saveServerConfig() } }
                    }
                }
            }
            Section("Alexa remote") {
                if let st = alexaStatus {
                    LabeledContent("Configured", value: st.configured ? "Yes" : "No")
                    LabeledContent("Authenticated", value: (st.authenticated ?? false) ? "Yes" : "No")
                }
                Button("Re-login to Alexa") {
                    Task { await startAlexaLogin() }
                }
                .disabled(alexaStatus?.configured != true)
                if let loginMessage {
                    Text(loginMessage).font(.caption).foregroundStyle(BockColors.muted)
                }
            }
            Section {
                LabeledContent("Client ID", value: String(ClientIdStore.clientId().prefix(8)) + "…")
            }
        }
        .scrollContentBackground(.hidden)
        .bockBackground()
        .navigationTitle("Settings")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .cancellationAction) {
                Button("Done") { dismiss() }
            }
        }
        .task { await load() }
        .refreshable { await load() }
        .onChange(of: configExpanded) { _, expanded in
            if !expanded { configConfirmed = false }
        }
        .onChange(of: configConfirmed) { _, confirmed in
            if configExpanded && confirmed { Task { await loadServerConfig() } }
        }
    }

    private func healthRow(_ label: String, _ ok: Bool) -> some View {
        HStack {
            Text(label)
            Spacer()
            BockIcon(icon: ok ? .check : .clear, size: 20)
                .foregroundStyle(ok ? BockColors.green : .red)
        }
    }

    private func load() async {
        loading = true
        defer { loading = false }
        health = try? await appState.repository.health()
        summary = try? await appState.repository.summary()
        alexaStatus = try? await appState.repository.alexaRemoteStatus()
        watchFolders = (try? await appState.repository.watchFolders()) ?? []
        plex = try? await appState.repository.plexSyncStatus()
    }

    private func loadServerConfig() async {
        configLoading = true
        defer { configLoading = false }
        do {
            let settings = try await appState.repository.loadSettingsJSON()
            let config = try await appState.repository.loadConfigJSON()
            defaultPlaylist = jsonString(settings, "defaultPlaylist")
            publicUrl = jsonString(config, "publicUrl")
            flacSupport = jsonString(settings, "flacSupport")
            transcodeBitrate = jsonString(settings, "transcodeBitrate")
        } catch {
            appState.toast = error.localizedDescription
        }
    }

    private func saveServerConfig() async {
        do {
            try await appState.repository.saveSettingsJSON(["defaultPlaylist": defaultPlaylist])
            try await appState.repository.saveConfigJSON(["publicUrl": publicUrl])
            appState.toast = "Server settings saved"
        } catch {
            appState.toast = error.localizedDescription
        }
    }

    private func jsonString(_ dict: [String: Any], _ key: String) -> String {
        if let s = dict[key] as? String { return s }
        if let n = dict[key] as? NSNumber { return n.stringValue }
        return ""
    }

    private func clearCache() async {
        clearingCache = true
        defer { clearingCache = false }
        do {
            try await appState.repository.clearServerCache()
            appState.toast = "Server cache cleared"
            await load()
        } catch {
            appState.toast = error.localizedDescription
        }
    }

    private func startAlexaLogin() async {
        do {
            let status = try await appState.repository.startAlexaLogin()
            guard let url = status.effectiveLoginURL else {
                loginMessage = status.loginError ?? status.error ?? "No login URL returned"
                return
            }
            await openLoginURL(url)
            loginMessage = "Complete login in the browser, then return to the app."
            await appState.refreshRemoteStatus()
            alexaStatus = try? await appState.repository.alexaRemoteStatus()
        } catch {
            loginMessage = error.localizedDescription
        }
    }

    @MainActor
    private func openLoginURL(_ url: URL) async {
        await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
            let session = ASWebAuthenticationSession(url: url, callbackURLScheme: "bockmedia") { _, _ in
                continuation.resume()
            }
            session.prefersEphemeralWebBrowserSession = false
            session.start()
        }
    }

}

private extension String {
    var nilIfBlank: String? {
        let t = trimmingCharacters(in: .whitespacesAndNewlines)
        return t.isEmpty ? nil : t
    }
}

extension SettingsView {
    private func formatUptime(_ seconds: Int) -> String {
        let h = seconds / 3600
        let m = (seconds % 3600) / 60
        return "\(h)h \(m)m"
    }
}
