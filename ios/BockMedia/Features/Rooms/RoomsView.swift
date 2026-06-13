import SwiftUI

struct RoomsView: View {
    @ObservedObject var appState: AppState
    @Environment(\.dismiss) private var dismiss
    @State private var rooms: [RoomItem] = []

    var body: some View {
        List(rooms) { room in
            VStack(alignment: .leading, spacing: 4) {
                Text(room.name ?? "Room").font(.headline)
                if let np = room.nowPlaying {
                    Text("\(np.track ?? "") · \(np.artist ?? "")")
                        .font(.caption)
                        .foregroundStyle(BockColors.muted)
                }
            }
        }
        .listStyle(.plain)
        .scrollContentBackground(.hidden)
        .navigationTitle("Rooms")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .cancellationAction) { Button("Done") { dismiss() } }
        }
        .task {
            rooms = (try? await appState.repository.rooms())?.rooms ?? []
        }
    }
}
