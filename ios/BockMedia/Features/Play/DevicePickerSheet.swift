import SwiftUI

struct DevicePickerSheet: View {
    let repository: BockMediaRepository
    let playLabel: String
    let playTarget: PlayTarget
    let remoteOk: Bool
    let shuffleDefault: Bool
    let onDismiss: () -> Void
    let onPlay: (String, Bool, String?) async -> Void
    let onPlayOnPhone: (Bool) async -> Void
    let onPlayError: (Error) -> Void

    @State private var devices: [AlexaDevice] = []
    @State private var loading = true
    @State private var shuffle: Bool
    @State private var selectedSerial: String?
    @State private var playing = false
    @State private var pinned: [String] = []

    init(
        repository: BockMediaRepository,
        playLabel: String,
        playTarget: PlayTarget,
        remoteOk: Bool,
        shuffleDefault: Bool,
        onDismiss: @escaping () -> Void,
        onPlay: @escaping (String, Bool, String?) async -> Void,
        onPlayOnPhone: @escaping (Bool) async -> Void,
        onPlayError: @escaping (Error) -> Void
    ) {
        self.repository = repository
        self.playLabel = playLabel
        self.playTarget = playTarget
        self.remoteOk = remoteOk
        self.shuffleDefault = shuffleDefault
        self.onDismiss = onDismiss
        self.onPlay = onPlay
        self.onPlayOnPhone = onPlayOnPhone
        self.onPlayError = onPlayError
        _shuffle = State(initialValue: shuffleDefault)
    }

    var body: some View {
        NavigationStack {
            List {
                Section {
                    Button {
                        Task { await playOnPhone() }
                    } label: {
                        Label("Play on this iPhone", icon: .phoneAndroid)
                    }
                    .disabled(playing)
                }

                if remoteOk {
                    Section {
                        Toggle(isOn: $shuffle) {
                            Label("Shuffle", icon: .shuffle)
                        }
                    }
                    if !pinnedDevices.isEmpty {
                        Section("Pinned") {
                            ForEach(pinnedDevices) { device in
                                speakerRow(device)
                            }
                        }
                    }
                    Section("Speakers") {
                        if loading {
                            ProgressView()
                        } else if devices.isEmpty {
                            Text("No Alexa devices found")
                                .foregroundStyle(BockColors.muted)
                        } else {
                            ForEach(otherDevices) { device in
                                speakerRow(device)
                            }
                        }
                    }
                } else {
                    Section {
                        Text("Alexa remote is not available on this network. Use “Play on this iPhone” or sign in to Alexa in Settings.")
                            .font(.subheadline)
                            .foregroundStyle(BockColors.muted)
                    }
                }
            }
            .scrollContentBackground(.hidden)
            .background(BockColors.sheetBg)
            .navigationTitle("Play \"\(playLabel)\"")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel", action: onDismiss)
                }
                if remoteOk {
                    ToolbarItem(placement: .confirmationAction) {
                        Button(playing ? "…" : "Play") {
                            Task { await playSelected() }
                        }
                        .disabled(playing || selectedSerial == nil)
                    }
                }
            }
            .task {
                pinned = PinnedDevicesStore.pinned()
                await loadDevices()
            }
        }
        .presentationDetents([.medium, .large])
    }

    private var pinnedDevices: [AlexaDevice] {
        pinned.compactMap { serial in devices.first { $0.serial == serial } }
    }

    private var otherDevices: [AlexaDevice] {
        devices.filter { dev in
            guard let serial = dev.serial else { return true }
            return !pinned.contains(serial)
        }
    }

    @ViewBuilder
    private func speakerRow(_ device: AlexaDevice) -> some View {
        Button {
            selectedSerial = device.serial
        } label: {
            HStack {
                BockIcon(icon: .speaker, size: 22)
                VStack(alignment: .leading) {
                    Text(device.name ?? "Speaker")
                    if !device.online {
                        Text("Offline").font(.caption).foregroundStyle(BockColors.muted)
                    }
                }
                Spacer()
                if let serial = device.serial {
                    Button {
                        PinnedDevicesStore.toggle(serial)
                        pinned = PinnedDevicesStore.pinned()
                    } label: {
                        BockIcon(icon: .pushPin, size: 18)
                            .foregroundStyle(PinnedDevicesStore.isPinned(serial) ? BockColors.green : BockColors.muted)
                    }
                    .buttonStyle(.plain)
                }
                if selectedSerial == device.serial {
                    BockIcon(icon: .check, size: 18).foregroundStyle(BockColors.green)
                }
            }
        }
        .foregroundStyle(BockColors.onSurface)
    }

    private func loadDevices() async {
        loading = true
        defer { loading = false }
        do {
            devices = try await repository.alexaRemoteDevices()
            if selectedSerial == nil {
                if let last = repository.preferences.lastDevice,
                   devices.contains(where: { $0.serial == last }) {
                    selectedSerial = last
                } else {
                    selectedSerial = devices.first(where: \.online)?.serial ?? devices.first?.serial
                }
            }
        } catch {
            onPlayError(error)
        }
    }

    private func playSelected() async {
        guard let serial = selectedSerial,
              let device = devices.first(where: { $0.serial == serial }),
              let name = device.name else { return }
        playing = true
        defer { playing = false }
        await onPlay(serial, shuffle, name)
        repository.preferences.lastDevice = serial
        onDismiss()
    }

    private func playOnPhone() async {
        playing = true
        defer { playing = false }
        await onPlayOnPhone(shuffle)
        onDismiss()
    }
}
