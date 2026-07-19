import SwiftUI

struct HomeSectionPinSheet: View {
    let playlistId: String
    let playlistName: String
    var suggestedSectionId: String?
    var onDismiss: () -> Void
    var onPinned: (String) -> Void

    @State private var pinnedSections: Set<String> = []

    private let targets = HomePinTargets.pinEligible()

    var body: some View {
        NavigationStack {
            List {
                Section {
                    Text(playlistName)
                        .font(.subheadline)
                        .foregroundStyle(BockColors.muted)
                } header: {
                    Text("Add to Home")
                } footer: {
                    Text("Pin to the beginning of a home row")
                }

                ForEach(targets, id: \.sectionId) { target in
                    let isPinned = pinnedSections.contains(target.sectionId)
                    let isSuggested = target.sectionId == suggestedSectionId
                    Button {
                        HomeSectionPinsStore.pin(
                            sectionId: target.sectionId,
                            playlistId: playlistId,
                            playlistName: playlistName
                        )
                        pinnedSections.insert(target.sectionId)
                        onPinned(target.title)
                        onDismiss()
                    } label: {
                        HStack {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(target.title)
                                    .foregroundStyle(BockColors.onSurface)
                                if isPinned {
                                    Text("Pinned · tap to update")
                                        .font(.caption)
                                        .foregroundStyle(BockColors.green)
                                } else if isSuggested {
                                    Text("Suggested")
                                        .font(.caption)
                                        .foregroundStyle(BockColors.muted)
                                }
                            }
                            Spacer()
                            if isPinned {
                                Text("✓")
                                    .foregroundStyle(BockColors.green)
                            }
                        }
                    }
                }
            }
            .navigationTitle("Add to Home")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Not now", action: onDismiss)
                }
            }
            .onAppear {
                pinnedSections = Set(HomeSectionPinsStore.pinnedSections(playlistId: playlistId))
            }
        }
        .presentationBackground(BockColors.sheetBg)
    }
}
