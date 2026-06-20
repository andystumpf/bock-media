import SwiftUI

/// Kid-safe room editor. Edits require a parent PIN (the active member must be a
/// parent). Mirrors the web Family console kid-safe modal.
struct RoomPolicySheet: View {
    @ObservedObject var appState: AppState
    let room: DeviceItem
    let actingMemberId: String
    let onDone: () -> Void

    @Environment(\.dismiss) private var dismiss

    @State private var policy = RoomPolicy()
    @State private var playlists: [PlaylistSummary] = []
    @State private var loading = true
    @State private var saving = false

    @State private var enforceVolume = false
    @State private var volume: Double = 100
    @State private var quietOn = false
    @State private var quietFrom = "20:30"
    @State private var quietTo = "07:00"
    @State private var allowSet: Set<String> = []
    @State private var pin = ""

    private var actingIsParent: Bool {
        // We only know role via household; the server still enforces, this is a hint.
        true
    }

    var body: some View {
        NavigationStack {
            Form {
                if loading {
                    ProgressView().tint(BockColors.green)
                } else {
                    Section {
                        Toggle("Enable kid-safe", isOn: $policy.safe)
                        Toggle("Allow explicit content", isOn: $policy.allowExplicit)
                        Toggle("Require approval for requests", isOn: $policy.requireApproval)
                    }

                    Section("Volume") {
                        Toggle("Enforce volume cap", isOn: $enforceVolume)
                        if enforceVolume {
                            VStack(alignment: .leading) {
                                Text("Max volume: \(Int(volume))")
                                    .font(.caption).foregroundStyle(BockColors.muted)
                                Slider(value: $volume, in: 0...100, step: 1)
                                    .tint(BockColors.green)
                            }
                        }
                    }

                    Section("Quiet hours") {
                        Toggle("Enable quiet hours", isOn: $quietOn)
                        if quietOn {
                            TextField("From (HH:MM)", text: $quietFrom)
                            TextField("To (HH:MM)", text: $quietTo)
                        }
                    }

                    Section("Allowed playlists") {
                        if playlists.isEmpty {
                            Text("No playlists").font(.caption).foregroundStyle(BockColors.muted)
                        }
                        ForEach(playlists) { p in
                            Button {
                                if allowSet.contains(p.id) { allowSet.remove(p.id) }
                                else { allowSet.insert(p.id) }
                            } label: {
                                HStack {
                                    Text(p.name).foregroundStyle(BockColors.onSurface)
                                    Spacer()
                                    if allowSet.contains(p.id) {
                                        Image(systemName: "checkmark").foregroundStyle(BockColors.green)
                                    }
                                }
                            }
                        }
                    }

                    Section("Parent PIN") {
                        SecureField("Parent PIN", text: $pin)
                            .keyboardType(.numberPad)
                    }
                }
            }
            .navigationTitle(room.name ?? "Kid-safe")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") { Task { await save() } }
                        .disabled(saving || loading)
                }
            }
            .task { await load() }
        }
    }

    private func load() async {
        loading = true
        defer { loading = false }
        if let p = try? await appState.repository.roomPolicy(deviceId: room.deviceId) {
            policy = p
            enforceVolume = p.maxVolume != nil
            volume = Double(p.maxVolume ?? 100)
            allowSet = Set(p.allowPlaylistIds)
            if let q = p.quietHours.first {
                quietOn = true
                quietFrom = q.from ?? "20:30"
                quietTo = q.to ?? "07:00"
            }
        }
        playlists = (try? await appState.repository.playlists(search: "", limit: 500))?.items ?? []
        if pin.isEmpty, let cached = ParentPinCache.get(memberId: actingMemberId) { pin = cached }
    }

    private func save() async {
        saving = true
        defer { saving = false }
        var body: [String: Any] = [
            "memberId": actingMemberId,
            "pin": pin,
            "safe": policy.safe,
            "allowExplicit": policy.allowExplicit,
            "requireApproval": policy.requireApproval,
            "allowPlaylistIds": Array(allowSet),
        ]
        body["maxVolume"] = enforceVolume ? Int(volume) : NSNull()
        if quietOn {
            body["quietHours"] = [[
                "days": [0, 1, 2, 3, 4, 5, 6],
                "from": quietFrom,
                "to": quietTo,
            ]]
        } else {
            body["quietHours"] = []
        }
        do {
            _ = try await appState.repository.setRoomPolicy(deviceId: room.deviceId, body: body)
            ParentPinCache.set(memberId: actingMemberId, pin: pin)
            appState.toast = "Kid-safe saved"
            onDone()
        } catch let BockAPIError.httpStatus(code, _) where code == 403 {
            appState.toast = "Wrong parent PIN"
        } catch {
            appState.toast = "Couldn't save"
        }
    }
}
