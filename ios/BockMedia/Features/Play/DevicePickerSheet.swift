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

    @State private var options: [DeviceOption] = []
    @State private var loading = true
    @State private var shuffle: Bool
    @State private var selectedValue: String?
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

    private var orderedOptions: [DeviceOption] {
        let pinnedSet = Set(pinned)
        let pinnedOpts = pinned.compactMap { value in options.first { $0.value == value } }
        let rest = options.filter { !pinnedSet.contains($0.value) }
        return pinnedOpts + rest
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
                    if loading {
                        Section {
                            ProgressView()
                        }
                    } else if options.isEmpty {
                        Section {
                            Text("No Alexa devices found")
                                .foregroundStyle(BockColors.muted)
                        }
                    } else {
                        Section("Speakers & groups") {
                            ForEach(orderedOptions) { option in
                                optionRow(option)
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
                        .disabled(playing || selectedValue == nil)
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

    @ViewBuilder
    private func optionRow(_ option: DeviceOption) -> some View {
        let isGroup = option.value.hasPrefix("group:")
        Button {
            selectedValue = option.value
        } label: {
            HStack {
                BockIcon(icon: isGroup ? .speakerGroup : .speaker, size: 22)
                Text(option.label)
                Spacer()
                Button {
                    PinnedDevicesStore.toggle(option.value)
                    pinned = PinnedDevicesStore.pinned()
                } label: {
                    BockIcon(icon: .pushPin, size: 18)
                        .foregroundStyle(PinnedDevicesStore.isPinned(option.value) ? BockColors.green : BockColors.muted)
                }
                .buttonStyle(.plain)
                if selectedValue == option.value {
                    BockIcon(icon: .check, size: 18).foregroundStyle(BockColors.green)
                }
            }
        }
        .foregroundStyle(BockColors.onSurface)
    }

    private func loadDevices() async {
        guard remoteOk else { return }
        if let snapshot = DeviceCatalog.peek(), DeviceCatalog.isFresh() {
            applyOptions(snapshot.options)
            loading = false
        } else {
            loading = true
        }
        let snapshot = await DeviceCatalog.refresh(repository: repository, probe: false)
        applyOptions(snapshot.options)
        loading = false
    }

    private func applyOptions(_ deviceOptions: [DeviceOption]) {
        options = deviceOptions
        if selectedValue == nil {
            let online = { (value: String) in
                deviceOptions.contains { $0.value == value && !$0.label.localizedCaseInsensitiveContains("offline") }
            }
            if let last = repository.preferences.lastDevice, online(last) {
                selectedValue = last
            } else if let pinnedFirst = pinned.first(where: online) {
                selectedValue = pinnedFirst
            } else {
                selectedValue = deviceOptions.first(where: { !$0.label.localizedCaseInsensitiveContains("offline") })?.value
                    ?? deviceOptions.first?.value
            }
        }
    }

    private func playSelected() async {
        guard let value = selectedValue,
              let option = options.first(where: { $0.value == value }) else { return }
        playing = true
        defer { playing = false }
        await onPlay(value, shuffle, option.label)
        repository.preferences.lastDevice = value
        onDismiss()
    }

    private func playOnPhone() async {
        playing = true
        defer { playing = false }
        await onPlayOnPhone(shuffle)
        onDismiss()
    }
}
