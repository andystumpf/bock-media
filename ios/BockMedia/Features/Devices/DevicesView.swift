import SwiftUI

struct DevicesView: View {
    @ObservedObject var appState: AppState
    @Environment(\.dismiss) private var dismiss

    @State private var savedDevices: [DeviceItem] = []
    @State private var alexaDevices: [AlexaDevice] = []
    @State private var candidates: [MergeCandidate] = []
    @State private var groups: [DeviceGroup] = []
    @State private var identify: IdentifyStatusResponse?
    @State private var loading = true
    @State private var renameDevice: DeviceItem?
    @State private var editGroup: DeviceGroup?
    @State private var showNewGroup = false

    var body: some View {
        List {
            Section {
                HStack {
                    VStack(alignment: .leading) {
                        Text("\(alexaDevices.filter(\.online).count) online")
                            .font(.headline)
                            .foregroundStyle(BockColors.onSurface)
                        Text("\(alexaDevices.count) speakers · \(groups.count) groups")
                            .font(.caption)
                            .foregroundStyle(BockColors.muted)
                    }
                    Spacer()
                    if identify?.running == true {
                        ProgressView()
                            .tint(BockColors.green)
                    }
                }
            }

            Section("Actions") {
                Button("Identify speakers") {
                    Task {
                        try? await appState.repository.identifyDevices()
                        identify = try? await appState.repository.identifyStatus()
                    }
                }
                .disabled(identify?.running == true)
                Button("New speaker group") { showNewGroup = true }
            }

            if !candidates.isEmpty {
                Section("Likely duplicates") {
                    ForEach(candidates) { c in
                        VStack(alignment: .leading, spacing: 6) {
                            Text("\(c.sourceName ?? c.sourceId) → \(c.targetName ?? c.targetId)")
                                .font(.subheadline)
                            HStack {
                                Button("Merge") {
                                    Task {
                                        try? await appState.repository.mergeDevices(sourceId: c.sourceId, targetId: c.targetId)
                                        await load()
                                    }
                                }
                                .font(.caption)
                                Button("Dismiss") {
                                    Task {
                                        try? await appState.repository.dismissMergeCandidate(sourceId: c.sourceId)
                                        await load()
                                    }
                                }
                                .font(.caption)
                                .foregroundStyle(BockColors.muted)
                            }
                        }
                    }
                }
            }

            if !groups.isEmpty {
                Section("Speaker groups") {
                    ForEach(groups) { group in
                        HStack {
                            VStack(alignment: .leading) {
                                Text(group.name).foregroundStyle(BockColors.onSurface)
                                Text("\(group.members.count) members").font(.caption).foregroundStyle(BockColors.muted)
                            }
                            Spacer()
                            Button("Edit") { editGroup = group }.font(.caption)
                        }
                        .swipeActions {
                            Button(role: .destructive) {
                                Task {
                                    try? await appState.repository.deleteDeviceGroup(id: group.id)
                                    await load()
                                }
                            } label: { Label("Delete", icon: .delete) }
                        }
                    }
                }
            }

            Section("Speakers") {
                if alexaDevices.isEmpty {
                    Text("No Alexa speakers found. Check Alexa remote in Settings.")
                        .foregroundStyle(BockColors.muted)
                } else {
                    ForEach(alexaDevices) { device in
                        HStack {
                            BockIcon(icon: .speaker, size: 24)
                                .foregroundStyle(device.online ? BockColors.green : BockColors.muted)
                            VStack(alignment: .leading) {
                                Text(device.name ?? "Speaker")
                                if let serial = device.serial {
                                    Text(serial).font(.caption2).foregroundStyle(BockColors.muted).lineLimit(1)
                                }
                            }
                            Spacer()
                            if device.online, let serial = device.serial {
                                Button("Test") {
                                    Task {
                                        try? await appState.repository.testDevice(serial: serial)
                                        appState.toast = "Playing test clip…"
                                    }
                                }
                                .font(.caption)
                            }
                        }
                    }
                }
            }

            if !savedDevices.isEmpty {
                Section("Saved device names") {
                    ForEach(savedDevices) { device in
                        Button {
                            renameDevice = device
                        } label: {
                            VStack(alignment: .leading) {
                                Text(device.name ?? device.deviceId).foregroundStyle(BockColors.onSurface)
                                Text(device.deviceId).font(.caption2).foregroundStyle(BockColors.muted)
                            }
                        }
                        .swipeActions {
                            Button(role: .destructive) {
                                Task {
                                    try? await appState.repository.deleteDevice(deviceId: device.deviceId)
                                    await load()
                                }
                            } label: { Label("Delete", icon: .delete) }
                        }
                    }
                }
            }
        }
        .listStyle(.plain)
        .scrollContentBackground(.hidden)
        .bockBackground()
        .navigationTitle("Devices")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .cancellationAction) { Button("Done") { dismiss() } }
        }
        .refreshable { await load() }
        .task {
            await load()
            await pollIdentifyIfNeeded()
        }
        .sheet(item: $renameDevice) { dev in
            DeviceRenameSheet(appState: appState, device: dev) {
                renameDevice = nil
                Task { await load() }
            }
        }
        .sheet(isPresented: $showNewGroup) {
            DeviceGroupEditorSheet(appState: appState, group: nil, alexaDevices: alexaDevices) {
                showNewGroup = false
                Task { await load() }
            }
        }
        .sheet(item: $editGroup) { group in
            DeviceGroupEditorSheet(appState: appState, group: group, alexaDevices: alexaDevices) {
                editGroup = nil
                Task { await load() }
            }
        }
    }

    private func load() async {
        loading = true
        defer { loading = false }
        savedDevices = (try? await appState.repository.devices()) ?? []
        alexaDevices = (try? await appState.repository.alexaRemoteDevices()) ?? []
        candidates = (try? await appState.repository.mergeCandidates()) ?? []
        groups = (try? await appState.repository.deviceGroups())?.items ?? []
        identify = try? await appState.repository.identifyStatus()
    }

    private func pollIdentifyIfNeeded() async {
        while identify?.running == true {
            try? await Task.sleep(nanoseconds: 1_500_000_000)
            identify = try? await appState.repository.identifyStatus()
        }
        if identify != nil { await load() }
    }
}

private struct DeviceRenameSheet: View {
    @ObservedObject var appState: AppState
    let device: DeviceItem
    let onDone: () -> Void

    @State private var name = ""
    @State private var saving = false
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            Form {
                TextField("Display name", text: $name)
            }
            .navigationTitle("Rename device")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") { Task { await save() } }.disabled(name.isEmpty || saving)
                }
            }
            .onAppear { name = device.name ?? "" }
        }
    }

    private func save() async {
        saving = true
        defer { saving = false }
        do {
            try await appState.repository.renameDevice(deviceId: device.deviceId, name: name)
            onDone()
            dismiss()
        } catch {
            appState.toast = error.localizedDescription
        }
    }
}

private struct DeviceGroupEditorSheet: View {
    @ObservedObject var appState: AppState
    let group: DeviceGroup?
    let alexaDevices: [AlexaDevice]
    let onDone: () -> Void

    @State private var name = ""
    @State private var selectedSerials: Set<String> = []
    @State private var saving = false
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            Form {
                TextField("Group name", text: $name)
                Section("Members") {
                    ForEach(alexaDevices.filter { $0.serial != nil }) { device in
                        if let serial = device.serial {
                            Toggle(device.name ?? serial, isOn: Binding(
                                get: { selectedSerials.contains(serial) },
                                set: { on in
                                    if on { selectedSerials.insert(serial) }
                                    else { selectedSerials.remove(serial) }
                                }
                            ))
                        }
                    }
                }
            }
            .navigationTitle(group == nil ? "New group" : "Edit group")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") { Task { await save() } }
                        .disabled(name.isEmpty || selectedSerials.isEmpty || saving)
                }
            }
            .onAppear {
                if let group {
                    name = group.name
                    selectedSerials = Set(group.members.map(\.serial))
                }
            }
        }
    }

    private func save() async {
        saving = true
        defer { saving = false }
        let serials = Array(selectedSerials)
        do {
            if let group {
                try await appState.repository.updateDeviceGroup(id: group.id, name: name, serials: serials)
            } else {
                try await appState.repository.createDeviceGroup(name: name, serials: serials)
            }
            onDone()
            dismiss()
        } catch {
            appState.toast = error.localizedDescription
        }
    }
}
