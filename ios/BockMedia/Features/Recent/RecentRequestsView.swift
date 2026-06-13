import SwiftUI

struct RecentRequestsView: View {
    @ObservedObject var appState: AppState
    @Environment(\.dismiss) private var dismiss
    @State private var items: [RecentPlayRequestItem] = []

    var body: some View {
        List(items) { item in
            VStack(alignment: .leading, spacing: 4) {
                Text(item.heard ?? "—").foregroundStyle(BockColors.onSurface)
                Text(item.found ?? "").font(.caption).foregroundStyle(BockColors.muted)
                if let ts = item.timestamp {
                    Text(ts).font(.caption2).foregroundStyle(BockColors.muted)
                }
            }
        }
        .listStyle(.plain)
        .scrollContentBackground(.hidden)
        .navigationTitle("Voice log")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .cancellationAction) { Button("Done") { dismiss() } }
        }
        .task {
            items = (try? await appState.repository.recent())?.items ?? []
        }
    }
}
