import SwiftUI

struct DeviceFixWizard: View {
    @ObservedObject var appState: AppState
    let speakers: [AlexaDevice]
    var onDismiss: () -> Void
    var onComplete: () -> Void

    @State private var index = 0
    @State private var roomName = ""
    @State private var busy = false
    @State private var error: String?

    private var speaker: AlexaDevice? { speakers[safe: index] }

    var body: some View {
        Group {
            if speaker == nil {
                Color.clear.onAppear { onComplete() }
            } else if let speaker {
                let serial = speaker.serial ?? ""
                VStack(alignment: .leading, spacing: 12) {
                    Text("Fix my devices")
                        .font(.headline)
                        .foregroundStyle(BockColors.onSurface)
                    Text("Speaker \(index + 1) of \(speakers.count). Play a clip to hear which room this is, then name it to match the Alexa app.")
                        .font(.caption)
                        .foregroundStyle(BockColors.muted)
                    HStack(spacing: 8) {
                        Image(systemName: "speaker.wave.2.fill")
                            .foregroundStyle(BockColors.green)
                        Text(speaker.name ?? (serial.isEmpty ? "Speaker" : serial))
                            .font(.subheadline.weight(.semibold))
                            .foregroundStyle(BockColors.onSurface)
                    }
                    Button("Play here") {
                        Task { await playTest(serial: serial, name: speaker.name) }
                    }
                    .disabled(busy || serial.isEmpty)
                    TextField("Room name", text: $roomName)
                        .textFieldStyle(.roundedBorder)
                    if let error {
                        Text(error)
                            .font(.caption)
                            .foregroundStyle(.red)
                    }
                    HStack {
                        Button("Skip") { advance() }
                        Spacer()
                        Button("Close", action: onDismiss)
                        Button(index + 1 >= speakers.count ? "Finish" : "Save & next") {
                            Task { await saveAndAdvance(serial: serial) }
                        }
                        .disabled(busy || roomName.trimmingCharacters(in: .whitespaces).isEmpty)
                    }
                }
                .padding(20)
                .presentationBackground(BockColors.sheetBg)
                .onAppear {
                    roomName = speaker.name ?? ""
                }
                .onChange(of: index) { _, newIndex in
                    roomName = speakers[safe: newIndex]?.name ?? ""
                    error = nil
                }
            }
        }
    }

    private func playTest(serial: String, name: String?) async {
        busy = true
        error = nil
        defer { busy = false }
        do {
            try await appState.repository.testDevice(
                serial: serial,
                name: roomName.nilIfBlank ?? name
            )
        } catch {
            self.error = error.localizedDescription
        }
    }

    private func saveAndAdvance(serial: String) async {
        busy = true
        error = nil
        defer { busy = false }
        do {
            try await appState.repository.testDevice(serial: serial, name: roomName.trimmingCharacters(in: .whitespaces))
            advance()
        } catch {
            self.error = error.localizedDescription
        }
    }

    private func advance() {
        if index + 1 >= speakers.count {
            onComplete()
        } else {
            index += 1
        }
    }
}

private extension String {
    var nilIfBlank: String? {
        let t = trimmingCharacters(in: .whitespacesAndNewlines)
        return t.isEmpty ? nil : t
    }
}

private extension Collection {
    subscript(safe index: Index) -> Element? {
        indices.contains(index) ? self[index] : nil
    }
}
