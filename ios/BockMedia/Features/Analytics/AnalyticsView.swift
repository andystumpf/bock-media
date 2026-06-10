import SwiftUI

private enum DatePreset: String, CaseIterable, Identifiable {
    case last7 = "Last 7 days"
    case last30 = "Last 30 days"
    case allTime = "All time"
    case custom = "Custom"
    var id: String { rawValue }
}

struct AnalyticsView: View {
    @ObservedObject var appState: AppState
    @Environment(\.dismiss) private var dismiss
    @State private var data: AnalyticsResponse?
    @State private var ignored: [IgnoredTrack] = []
    @State private var preset: DatePreset = .allTime
    @State private var customFrom = Date()
    @State private var customTo = Date()
    @State private var showFromPicker = false
    @State private var showToPicker = false
    @State private var loading = true
    @State private var exporting = false
    @State private var exportURL: URL?

    var body: some View {
        List {
            Section {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 8) {
                        ForEach(DatePreset.allCases.filter { $0 != .custom }) { p in
                            Button(p.rawValue) { preset = p }
                                .buttonStyle(.bordered)
                                .tint(preset == p ? BockColors.green : BockColors.muted)
                        }
                    }
                }
                HStack {
                    Button("From") { showFromPicker = true }
                    Button("To") { showToPicker = true }
                    Button("Clear") {
                        preset = .allTime
                    }
                    Spacer()
                    Button(exporting ? "Exporting…" : "Export") {
                        Task { await exportCSV() }
                    }
                    .disabled(exporting)
                }
                .font(.caption)
            }

            if let data {
                section("Top artists", data.topArtists)
                section("Top albums", data.topAlbums)
                section("Top tracks", data.topTracks)
                section("Top devices", data.topDevices)
                section("Genres", data.topGenres)
                section("By date", data.byDate)
                section("By hour", data.byHour)
                section("By day of week", data.byDayOfWeek)
                section("Decades", data.decades)
            } else if !loading {
                Text("No analytics data").foregroundStyle(BockColors.muted)
            }

            Section("Never play again") {
                if ignored.isEmpty {
                    Text("No ignored tracks").foregroundStyle(BockColors.muted)
                }
                ForEach(ignored) { track in
                    VStack(alignment: .leading, spacing: 2) {
                        Text(track.title ?? track.path.split(separator: "/").last.map(String.init) ?? track.path)
                        if track.artist != nil || track.album != nil {
                            Text([track.artist, track.album].compactMap { $0 }.joined(separator: " · "))
                                .font(.caption)
                                .foregroundStyle(BockColors.muted)
                        }
                    }
                    .swipeActions {
                        Button(role: .destructive) {
                            Task {
                                try? await appState.repository.removeIgnored(path: track.path)
                                await load()
                            }
                        } label: {
                            Text("Remove")
                        }
                    }
                }
            }
        }
        .listStyle(.plain)
        .scrollContentBackground(.hidden)
        .navigationTitle("Analytics")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .cancellationAction) { Button("Done") { dismiss() } }
        }
        .task(id: preset) { await load() }
        .refreshable { await load() }
        .sheet(isPresented: $showFromPicker) {
            DatePickerSheet(title: "From", date: $customFrom) {
                preset = .custom
                showFromPicker = false
                Task { await load() }
            }
        }
        .sheet(isPresented: $showToPicker) {
            DatePickerSheet(title: "To", date: $customTo) {
                preset = .custom
                showToPicker = false
                Task { await load() }
            }
        }
        .sheet(item: $exportURL) { url in
            ShareSheet(items: [url])
        }
    }

    @ViewBuilder
    private func section(_ title: String, _ rows: [CountRow]) -> some View {
        if !rows.isEmpty {
            Section(title) {
                ForEach(rows.prefix(12)) { row in
                    HStack {
                        Text(row.label ?? row.name ?? "—")
                        Spacer()
                        Text("\(row.count)")
                            .foregroundStyle(BockColors.muted)
                    }
                }
            }
        }
    }

    private func dateRange() -> (String?, String?) {
        let cal = Calendar.current
        let today = Date()
        switch preset {
        case .last7:
            let from = cal.date(byAdding: .day, value: -6, to: today) ?? today
            return (isoDate(from), isoDate(today))
        case .last30:
            let from = cal.date(byAdding: .day, value: -29, to: today) ?? today
            return (isoDate(from), isoDate(today))
        case .allTime:
            return (nil, nil)
        case .custom:
            return (isoDate(customFrom), isoDate(customTo))
        }
    }

    private func isoDate(_ date: Date) -> String {
        let f = DateFormatter()
        f.dateFormat = "yyyy-MM-dd"
        f.timeZone = .current
        return f.string(from: date)
    }

    private func load() async {
        loading = true
        defer { loading = false }
        let (from, to) = dateRange()
        data = try? await appState.repository.analytics(from: from, to: to)
        ignored = (try? await appState.repository.ignored()) ?? []
    }

    private func exportCSV() async {
        exporting = true
        defer { exporting = false }
        let (from, to) = dateRange()
        if let url = try? await appState.repository.exportAnalyticsCSV(from: from, to: to) {
            exportURL = url
        }
    }
}

private struct DatePickerSheet: View {
    let title: String
    @Binding var date: Date
    let onDone: () -> Void
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            DatePicker(title, selection: $date, displayedComponents: .date)
                .datePickerStyle(.graphical)
                .padding()
                .navigationTitle(title)
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                    ToolbarItem(placement: .confirmationAction) {
                        Button("Done") {
                            onDone()
                            dismiss()
                        }
                    }
                }
        }
        .presentationDetents([.medium])
    }
}

struct ShareSheet: UIViewControllerRepresentable {
    let items: [Any]

    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: items, applicationActivities: nil)
    }

    func updateUIViewController(_ uiViewController: UIActivityViewController, context: Context) {}
}

extension URL: @retroactive Identifiable {
    public var id: String { absoluteString }
}
