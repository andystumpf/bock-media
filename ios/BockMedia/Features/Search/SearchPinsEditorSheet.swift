import SwiftUI

private let searchPinKinds = ["artist", "album", "genre", "playlist", "radio", "mix"]

struct SearchPinsEditorSheet: View {
    @ObservedObject var appState: AppState
    @State var pins: [SearchPin]
    var onDismiss: () -> Void
    var onSaved: ([SearchPin]) -> Void

    @State private var kind = "artist"
    @State private var title = ""
    @State private var name = ""
    @State private var saving = false

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Text("Custom shortcuts appear below Aural fixations.")
                        .font(.caption)
                        .foregroundStyle(BockColors.muted)
                }
                if !pins.isEmpty {
                    Section("Shortcuts") {
                        ForEach(pins) { pin in
                            HStack {
                                VStack(alignment: .leading) {
                                    Text(pin.title ?? pin.name ?? pin.kind)
                                    Text(pin.kind)
                                        .font(.caption)
                                        .foregroundStyle(BockColors.muted)
                                }
                                Spacer()
                                Button {
                                    pins.removeAll { $0.displayId == pin.displayId }
                                } label: {
                                    Image(systemName: "trash")
                                        .foregroundStyle(.red)
                                }
                            }
                        }
                    }
                }
                Section("Add shortcut") {
                    Picker("Kind", selection: $kind) {
                        ForEach(searchPinKinds, id: \.self) { Text($0).tag($0) }
                    }
                    TextField("Title", text: $title)
                    TextField("Name / ID", text: $name)
                    Button("Add") { addPin() }
                        .disabled(title.trimmingCharacters(in: .whitespaces).isEmpty
                                  && name.trimmingCharacters(in: .whitespaces).isEmpty)
                }
            }
            .navigationTitle("Edit shortcuts")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel", action: onDismiss)
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button(saving ? "Saving…" : "Save") {
                        Task { await save() }
                    }
                    .disabled(saving)
                }
            }
        }
    }

    private func addPin() {
        let trimmedTitle = title.trimmingCharacters(in: .whitespacesAndNewlines)
        let trimmedName = name.trimmingCharacters(in: .whitespacesAndNewlines)
        let display = trimmedTitle.isEmpty ? trimmedName : trimmedTitle
        guard !display.isEmpty else { return }
        pins.append(SearchPin(
            kind: kind,
            title: display,
            name: trimmedName.isEmpty ? nil : trimmedName
        ))
        title = ""
        name = ""
    }

    private func save() async {
        saving = true
        defer { saving = false }
        do {
            try await appState.repository.saveSearchPins(pins)
            onSaved(pins)
            onDismiss()
        } catch {
            appState.toast = error.localizedDescription
        }
    }
}
