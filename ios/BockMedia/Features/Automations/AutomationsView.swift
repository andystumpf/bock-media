import SwiftUI

struct AutomationsView: View {
    @ObservedObject var appState: AppState
    @State private var items: [AutomationItem] = []
    @State private var loading = false
    @State private var remoteOk = false
    @State private var showCreate = false
    @State private var editItem: AutomationItem?

    var body: some View {
        ZStack(alignment: .bottomTrailing) {
            VStack(alignment: .leading, spacing: 0) {
                TabScreenHeader(title: "Automations")
                if !remoteOk {
                    Text("Alexa remote required. Sign in via Settings → Re-login to Alexa.")
                        .font(.subheadline)
                        .foregroundStyle(BockColors.muted)
                        .padding(.horizontal, 16)
                        .padding(.bottom, 8)
                }
                if loading {
                    LoadingBox(logoSize: 48)
                        .padding()
                } else if items.isEmpty {
                    ContentUnavailableView {
                        Label("No automations", icon: .schedule, size: 40)
                    }
                } else {
                    List(items) { item in
                        Button {
                            editItem = item
                        } label: {
                            VStack(alignment: .leading, spacing: 4) {
                                Text(item.name.isEmpty ? item.label : item.name)
                                    .font(.headline)
                                    .foregroundStyle(BockColors.onSurface)
                                Text("\(item.time ?? "—") · \(formatDays(item.days)) · \(item.playlistName ?? item.playlist ?? "")")
                                    .font(.caption)
                                    .foregroundStyle(BockColors.muted)
                            }
                        }
                        .swipeActions {
                            Button("Run") {
                                Task { try? await appState.repository.runAutomation(item.id) }
                            }
                            .tint(BockColors.green)
                            Button(role: .destructive) {
                                Task {
                                    try? await appState.repository.deleteAutomation(item.id)
                                    await load()
                                }
                            } label: {
                                Text("Delete")
                            }
                        }
                        .listRowBackground(BockColors.surfaceVariant.opacity(0.4))
                    }
                    .listStyle(.plain)
                    .scrollContentBackground(.hidden)
                }
            }
            .accessibilityIdentifier(BockTestTags.automationsContent)

            if remoteOk {
                Button {
                    showCreate = true
                } label: {
                    BockIcon(icon: .add, size: 22)
                        .font(.title2.weight(.semibold))
                        .foregroundStyle(BockColors.black)
                        .frame(width: 56, height: 56)
                        .background(BockColors.green)
                        .clipShape(Circle())
                        .shadow(radius: 4)
                }
                .padding(20)
            }
        }
        .task { await load() }
        .refreshable { await load() }
        .sheet(isPresented: $showCreate) {
            AutomationFormSheet(appState: appState, onDismiss: { showCreate = false }, onSaved: {
                showCreate = false
                Task { await load() }
            })
        }
        .sheet(item: $editItem) { item in
            AutomationFormSheet(
                appState: appState,
                editItem: item,
                onDismiss: { editItem = nil },
                onSaved: {
                    editItem = nil
                    Task { await load() }
                }
            )
        }
    }

    private func load() async {
        loading = true
        defer { loading = false }
        items = (try? await appState.repository.automations())?.items ?? []
        remoteOk = appState.remoteOk
    }

    private func formatDays(_ days: [Int]) -> String {
        if days.count == 7 { return "Daily" }
        if Set(days) == Set([0, 1, 2, 3, 4]) { return "Mon–Fri" }
        if Set(days) == Set([5, 6]) { return "Weekends" }
        let labels = ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"]
        return days.sorted().compactMap { labels[safe: $0] }.joined(separator: ", ")
    }
}

private extension Array {
    subscript(safe index: Int) -> Element? {
        indices.contains(index) ? self[index] : nil
    }
}
