import SwiftUI

private let dayLabels = ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"]

private enum DayPreset: String, CaseIterable, Identifiable {
    case daily = "Daily"
    case weekdays = "Weekdays"
    case weekends = "Weekends"
    case custom = "Custom"
    var id: String { rawValue }
}

struct AutomationFormSheet: View {
    @ObservedObject var appState: AppState
    var editItem: AutomationItem?
    let onDismiss: () -> Void
    let onSaved: () -> Void

    @State private var label = ""
    @State private var playlistSearch = ""
    @State private var playlistId: String?
    @State private var playlistName: String?
    @State private var playlistHits: [PlaylistSummary] = []
    @State private var deviceValue = ""
    @State private var deviceName = ""
    @State private var deviceOptions: [DeviceOption] = []
    @State private var time = "08:00"
    @State private var volume = ""
    @State private var shuffle = false
    @State private var enabled = true
    @State private var dayPreset: DayPreset = .daily
    @State private var customDays: Set<Int> = Set(0...6)
    @State private var saving = false
    @State private var error: String?

    var body: some View {
        NavigationStack {
            Form {
                Section("Details") {
                    TextField("Label", text: $label)
                    TextField("Playlist search", text: $playlistSearch)
                        .onChange(of: playlistSearch) { _, q in
                            Task { await searchPlaylists(q) }
                        }
                    if let name = playlistName {
                        Text("Selected: \(name)").font(.caption).foregroundStyle(BockColors.green)
                    }
                    ForEach(playlistHits.prefix(6)) { pl in
                        Button(pl.name) {
                            playlistId = pl.id
                            playlistName = pl.name
                            playlistSearch = pl.name
                            playlistHits = []
                        }
                    }
                    Picker("Device", selection: $deviceValue) {
                        Text("Select…").tag("")
                        ForEach(deviceOptions) { opt in
                            Text(opt.label).tag(opt.value)
                        }
                    }
                    .onChange(of: deviceValue) { _, val in
                        deviceName = deviceOptions.first(where: { $0.value == val }).map {
                            $0.label.replacingOccurrences(of: " (offline)", with: "")
                        } ?? ""
                    }
                    TextField("Time (HH:MM)", text: $time)
                    TextField("Volume (optional)", text: $volume)
                        .keyboardType(.numberPad)
                }
                Section("Days") {
                    Picker("Preset", selection: $dayPreset) {
                        ForEach(DayPreset.allCases) { p in
                            Text(p.rawValue).tag(p)
                        }
                    }
                    .pickerStyle(.segmented)
                    if dayPreset == .custom {
                        LazyVGrid(columns: Array(repeating: GridItem(.flexible()), count: 4)) {
                            ForEach(0..<7, id: \.self) { idx in
                                Button(dayLabels[idx]) {
                                    if customDays.contains(idx) { customDays.remove(idx) }
                                    else { customDays.insert(idx) }
                                }
                                .buttonStyle(.bordered)
                                .tint(customDays.contains(idx) ? BockColors.green : BockColors.muted)
                            }
                        }
                    }
                }
                Section {
                    Toggle("Shuffle", isOn: $shuffle)
                    Toggle("Enabled", isOn: $enabled)
                }
                if let error {
                    Text(error).foregroundStyle(.red).font(.caption)
                }
            }
            .navigationTitle(editItem == nil ? "New automation" : "Edit automation")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel", action: onDismiss) }
                ToolbarItem(placement: .confirmationAction) {
                    Button(saving ? "…" : "Save") { Task { await save() } }
                        .disabled(saving || label.isEmpty || deviceValue.isEmpty || selectedDays.isEmpty)
                }
            }
            .task {
                await loadDevices()
                if let item = editItem {
                    label = item.name.isEmpty ? item.label : item.name
                    playlistSearch = item.playlistName ?? item.playlist ?? ""
                    playlistId = item.playlistId
                    playlistName = item.playlistName ?? item.playlist
                    deviceValue = item.device ?? ""
                    deviceName = item.deviceName ?? ""
                    time = item.time ?? "08:00"
                    volume = item.volume.map(String.init) ?? ""
                    shuffle = item.shuffle
                    enabled = item.enabled
                    customDays = Set(item.days)
                    dayPreset = presetFor(days: item.days)
                }
            }
        }
    }

    private var selectedDays: Set<Int> {
        switch dayPreset {
        case .daily: return Set(0...6)
        case .weekdays: return Set(0...4)
        case .weekends: return Set([5, 6])
        case .custom: return customDays
        }
    }

    private func presetFor(days: [Int]) -> DayPreset {
        let set = Set(days)
        if set == Set(0...6) { return .daily }
        if set == Set(0...4) { return .weekdays }
        if set == Set([5, 6]) { return .weekends }
        return .custom
    }

    private func loadDevices() async {
        let groups = (try? await appState.repository.deviceGroups())?.items ?? []
        let devices = (try? await appState.repository.alexaRemoteDevices()) ?? []
        deviceOptions = DeviceOptionBuilder.build(groups: groups, devices: devices)
        if deviceValue.isEmpty, let first = deviceOptions.first {
            deviceValue = first.value
            deviceName = first.label
        }
    }

    private func searchPlaylists(_ q: String) async {
        guard playlistId == nil, q.count >= 1 else {
            playlistHits = []
            return
        }
        try? await Task.sleep(nanoseconds: 250_000_000)
        playlistHits = (try? await appState.repository.playlists(search: q, limit: 25))?.items ?? []
    }

    private func save() async {
        saving = true
        error = nil
        defer { saving = false }
        var body: [String: Any] = [
            "name": label,
            "device": deviceValue,
            "deviceName": deviceName,
            "time": time,
            "days": selectedDays.sorted(),
            "shuffle": shuffle,
            "enabled": enabled,
        ]
        if let playlistId { body["playlistId"] = playlistId }
        if let playlistName { body["playlistName"] = playlistName }
        if let vol = Int(volume) { body["volume"] = vol }
        do {
            if let editItem {
                try await appState.repository.updateAutomation(editItem.id, body: body)
            } else {
                try await appState.repository.createAutomation(body)
            }
            onSaved()
        } catch {
            self.error = error.localizedDescription
        }
    }
}

struct DeviceOption: Identifiable {
    let value: String
    let label: String
    var id: String { value }
}

enum DeviceOptionBuilder {
    static func build(groups: [DeviceGroup], devices: [AlexaDevice]) -> [DeviceOption] {
        var out: [DeviceOption] = []
        for g in groups {
            out.append(DeviceOption(value: "group:\(g.id)", label: "\(g.name) (\(g.members.count))"))
        }
        for d in devices {
            guard let serial = d.serial else { continue }
            let name = d.name ?? serial
            let suffix = d.online ? "" : " (offline)"
            out.append(DeviceOption(value: serial, label: name + suffix))
        }
        return out
    }
}
