import SwiftUI

struct RoutinesView: View {
    @Environment(\.dismiss) private var dismiss
    @State private var playlist = "Chill"
    @State private var shuffle = false

    var body: some View {
        Form {
            Section("Playlist name") {
                TextField("Playlist", text: $playlist)
            }
            Toggle("Shuffle", isOn: $shuffle)
            Section("Alexa phrase") {
                Text(buildRoutinePhrase(playlist: playlist, shuffle: shuffle))
                    .font(.body.monospaced())
                    .foregroundStyle(BockColors.green)
            }
        }
        .scrollContentBackground(.hidden)
        .navigationTitle("Routines")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .cancellationAction) { Button("Done") { dismiss() } }
        }
    }
}
