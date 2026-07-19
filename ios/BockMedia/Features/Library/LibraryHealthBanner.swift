import SwiftUI

struct LibraryHealthBanner: View {
    let health: LibraryHealthResponse
    @ObservedObject var appState: AppState
    var onMerged: () -> Void = {}

    @State private var expanded = false
    @State private var merging = false
    @State private var message: String?

    var body: some View {
        let summary = health.summary
        if summary.needsAttention <= 0 && health.duplicateArtists.isEmpty {
            EmptyView()
        } else {
            VStack(alignment: .leading, spacing: 8) {
                HStack(spacing: 8) {
                    Image(systemName: "wrench.and.screwdriver")
                        .foregroundStyle(BockColors.green)
                    Text("Library needs attention")
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(BockColors.onSurface)
                }
                if summary.needsAttention > 0 {
                    Text("\(summary.needsAttention) tracks missing genre or album artist")
                        .font(.caption)
                        .foregroundStyle(BockColors.muted)
                }
                if !health.duplicateArtists.isEmpty {
                    Text("\(health.duplicateArtists.count) artist name duplicates (e.g. spelling variants)")
                        .font(.caption)
                        .foregroundStyle(BockColors.muted)
                }
                Button(expanded ? "Hide details" : "Show fix queues") {
                    expanded.toggle()
                }
                .font(.caption.weight(.semibold))
                .foregroundStyle(BockColors.green)

                if expanded {
                    if !health.attentionFolders.isEmpty {
                        Text("Tag these folders in Picard first:")
                            .font(.caption.weight(.medium))
                            .foregroundStyle(BockColors.onSurface)
                        ForEach(health.attentionFolders, id: \.path) { folder in
                            Text("\(folder.trackCount) tracks · \(URL(fileURLWithPath: folder.path).lastPathComponent)")
                                .font(.caption)
                                .foregroundStyle(BockColors.muted)
                        }
                    }
                    ForEach(Array(health.duplicateArtists.prefix(3)), id: \.canonical) { group in
                        Text(group.variants.joined(separator: " / "))
                            .font(.caption.weight(.medium))
                            .foregroundStyle(BockColors.onSurface)
                            .padding(.top, 4)
                        Button {
                            Task { await merge(group) }
                        } label: {
                            Text("Merge → \(group.canonical)")
                                .font(.caption.weight(.semibold))
                        }
                        .disabled(merging)
                        .foregroundStyle(BockColors.green)
                    }
                }
                if let message {
                    Text(message)
                        .font(.caption2)
                        .foregroundStyle(BockColors.green)
                }
            }
            .padding(16)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(BockColors.surfaceVariant.opacity(0.55))
            .clipShape(RoundedRectangle(cornerRadius: 12))
            .padding(.horizontal, 16)
            .padding(.vertical, 8)
        }
    }

    private func merge(_ group: DuplicateArtistGroup) async {
        merging = true
        defer { merging = false }
        let others = group.variants.filter { $0 != group.canonical }
        do {
            try await appState.repository.mergeArtists(from: others, to: group.canonical)
            message = "Merged into \"\(group.canonical)\""
            onMerged()
        } catch {
            message = error.localizedDescription
        }
    }
}
