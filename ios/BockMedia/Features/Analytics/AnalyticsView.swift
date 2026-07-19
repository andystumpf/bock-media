import Charts
import SwiftUI

private enum DatePreset: String, CaseIterable, Identifiable {
    case last7 = "7 days"
    case last30 = "30 days"
    case allTime = "All time"
    case custom = "Custom"
    var id: String { rawValue }
}

private enum ActivityPeriod: String, CaseIterable, Identifiable {
    case day = "Day"
    case week = "Week"
    case month = "Month"
    case year = "Year"
    var id: String { rawValue }
}

private enum AnalyticsDeviceFilter: Equatable {
    case allDevices
    case thisPhone
    case specific(deviceId: String, label: String)

    func apiDeviceId(thisPhoneId: String) -> String? {
        switch self {
        case .allDevices: return nil
        case .thisPhone: return thisPhoneId.isEmpty ? nil : thisPhoneId
        case .specific(let id, _): return id
        }
    }
}

private struct IndexedValue: Identifiable {
    let id: Int
    let label: String
    let value: Int
}

struct AnalyticsView: View {
    @ObservedObject var appState: AppState
    @Environment(\.dismiss) private var dismiss
    @State private var data: AnalyticsResponse?
    @State private var ignored: [IgnoredTrack] = []
    @State private var preset: DatePreset = .last30
    @State private var activityPeriod: ActivityPeriod = .day
    @State private var deviceFilter: AnalyticsDeviceFilter = .allDevices
    @State private var knownDevices: [DeviceItem] = []
    @State private var customFrom = Date()
    @State private var customTo = Date()
    @State private var showFromPicker = false
    @State private var showToPicker = false
    @State private var loading = true
    @State private var exporting = false
    @State private var exportURL: URL?

    private var thisPhoneId: String { appState.repository.clientDeviceId() }
    private var deviceScoped: Bool { deviceFilter != .allDevices }

    private var queryKey: String {
        let range: String
        switch preset {
        case .last7: range = "last7"
        case .last30: range = "last30"
        case .allTime: range = "all"
        case .custom: range = "custom:\(isoDate(customFrom)):\(isoDate(customTo))"
        }
        return "\(range)|\(deviceFilter.apiDeviceId(thisPhoneId: thisPhoneId) ?? "all-devices")|\(appState.activeMemberId ?? "none")|\(appState.profileChangeRevision)"
    }

    var body: some View {
        List {
            toolbarSection

            if loading && data == nil {
                Section {
                    LoadingBox()
                        .frame(minHeight: 120)
                        .listRowBackground(Color.clear)
                }
            } else if let data {
                summarySection(data)
                activitySection(data)
                hourDowSection(data)
                if let heatmap = data.heatmap, !heatmap.isEmpty {
                    heatmapSection(heatmap)
                }
                rankingSection("Top artists", data.topArtists, BockColors.green)
                rankingSection("Top albums", data.topAlbums, BockColors.gold)
                rankingSection("Top tracks", data.topTracks, Color(red: 0x50 / 255, green: 0x9B / 255, blue: 0xF5 / 255))
                if !deviceScoped {
                    rankingSection("Top devices", data.topDevices, BockColors.navy)
                }
                rankingSection("Top genres", data.topGenres, Color(red: 0x8D / 255, green: 0x67 / 255, blue: 0xAB / 255))
                decadeSection(data.topDecades)
                if !deviceScoped, data.deviceBreakdown.contains(where: { $0.plays + $0.downloads + $0.connects > 0 }) {
                    deviceBreakdownSection(data.deviceBreakdown)
                }
            } else if !loading {
                Text("No analytics data").foregroundStyle(BockColors.muted)
            }

            ignoredSection
        }
        .listStyle(.plain)
        .scrollContentBackground(.hidden)
        .accessibilityIdentifier(BockTestTags.analyticsBody)
        .navigationTitle("Analytics")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .cancellationAction) { Button("Done") { dismiss() } }
        }
        .task { knownDevices = (try? await appState.repository.devices()) ?? [] }
        .task(id: queryKey) { await load() }
        .refreshable { await load() }
        .sheet(isPresented: $showFromPicker) {
            DatePickerSheet(title: "From", date: $customFrom) {
                preset = .custom
                showFromPicker = false
            }
        }
        .sheet(isPresented: $showToPicker) {
            DatePickerSheet(title: "To", date: $customTo) {
                preset = .custom
                showToPicker = false
            }
        }
        .sheet(item: $exportURL) { url in
            ShareSheet(items: [url])
        }
    }

    // MARK: - Toolbar

    private var toolbarSection: some View {
        Section {
            HStack {
                Menu {
                    Button { deviceFilter = .allDevices } label: {
                        Label("All devices", systemImage: deviceFilter == .allDevices ? "checkmark" : "")
                    }
                    if !thisPhoneId.isEmpty {
                        Button { deviceFilter = .thisPhone } label: {
                            Label(thisPhoneLabel, systemImage: deviceFilter == .thisPhone ? "checkmark" : "iphone")
                        }
                    }
                    ForEach(otherDevices) { device in
                        let label = deviceLabel(device)
                        Button { deviceFilter = .specific(deviceId: device.deviceId, label: label) } label: {
                            if case .specific(let id, _) = deviceFilter, id == device.deviceId {
                                Label(label, systemImage: "checkmark")
                            } else {
                                Text(label)
                            }
                        }
                    }
                } label: {
                    HStack {
                        Image(systemName: "rectangle.on.rectangle")
                        Text(deviceFilterLabel).lineLimit(1)
                        Image(systemName: "chevron.down").font(.caption2)
                    }
                    .foregroundStyle(BockColors.onSurface)
                }
                Spacer()
                Button(exporting ? "Exporting…" : "Export", systemImage: "square.and.arrow.up") {
                    Task { await exportCSV() }
                }
                .disabled(exporting)
                .tint(BockColors.green)
            }

            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    ForEach([DatePreset.last7, .last30, .allTime]) { p in
                        Button(p.rawValue) { preset = p }
                            .buttonStyle(.bordered)
                            .tint(preset == p ? BockColors.green : BockColors.muted)
                    }
                }
            }

            HStack {
                Button(preset == .custom ? isoDate(customFrom) : "From") { showFromPicker = true }
                Button(preset == .custom ? isoDate(customTo) : "To") { showToPicker = true }
                if preset == .custom {
                    Button("Clear") { preset = .allTime }
                }
            }
            .font(.caption)
        }
    }

    private var thisPhoneLabel: String {
        if let name = knownDevices.first(where: { $0.deviceId == thisPhoneId })?.name, !name.isEmpty {
            return "This phone (\(name))"
        }
        return "This phone"
    }

    private var deviceFilterLabel: String {
        switch deviceFilter {
        case .allDevices: return "All devices"
        case .thisPhone: return thisPhoneLabel
        case .specific(_, let label): return label
        }
    }

    private var otherDevices: [DeviceItem] {
        knownDevices
            .filter { !$0.deviceId.isEmpty && $0.deviceId != thisPhoneId }
            .sorted { ($0.name?.lowercased() ?? $0.deviceId) < ($1.name?.lowercased() ?? $1.deviceId) }
    }

    private func deviceLabel(_ device: DeviceItem) -> String {
        if let name = device.name, !name.isEmpty { return name }
        return String(device.deviceId.suffix(8))
    }

    // MARK: - Summary

    private func summarySection(_ data: AnalyticsResponse) -> some View {
        let streak = data.listeningStreak ?? ListeningStreak(current: data.currentStreak, longest: data.longestStreak)
        let cov = data.catalogCoverage
        let rr = data.repeatRate
        var tiles: [StatTile] = [
            StatTile(icon: "headphones", tint: Color(red: 0x50 / 255, green: 0x9B / 255, blue: 0xF5 / 255),
                     value: intFmt(data.totalPlays), label: "Total plays", subtitle: "\(intFmt(data.uniqueArtists)) artists"),
            StatTile(icon: "flame.fill", tint: BockColors.gold,
                     value: "\(streak.current)", label: "Day streak", subtitle: "Best: \(streak.longest)"),
            StatTile(icon: "square.stack.fill", tint: Color(red: 0x8D / 255, green: 0x67 / 255, blue: 0xAB / 255),
                     value: catalogPct(cov), label: "Catalog heard",
                     subtitle: cov.map { "\(intFmt($0.heard)) / \(intFmt($0.total))" }),
            StatTile(icon: "repeat", tint: BockColors.green,
                     value: "\(formatPct(rr?.pct ?? 0))%", label: "Repeat rate",
                     subtitle: rr.map { "\(intFmt($0.repeated)) replays" }),
        ]
        if let mad = data.mostActiveDay, mad.count > 0 {
            tiles.append(StatTile(icon: "calendar", tint: Color(red: 0xE9 / 255, green: 0x14 / 255, blue: 0x29 / 255),
                                  value: intFmt(mad.count), label: "Best day", subtitle: mad.date))
        }
        return Section {
            LazyVGrid(columns: [GridItem(.flexible(), spacing: 10), GridItem(.flexible(), spacing: 10)], spacing: 10) {
                ForEach(Array(tiles.enumerated()), id: \.offset) { index, tile in
                    StatCard(tile: tile)
                        .accessibilityIdentifier(index == 0 ? BockTestTags.analyticsTotalPlays : "bock_analytics_stat_\(index)")
                }
            }
            .listRowInsets(EdgeInsets(top: 4, leading: 0, bottom: 4, trailing: 0))
            .listRowBackground(Color.clear)
        }
    }

    private func catalogPct(_ cov: CatalogCoverage?) -> String {
        guard let cov else { return "0%" }
        if cov.pct < 0.1, cov.heard > 0 { return "<0.1%" }
        return "\(formatPct(cov.pct))%"
    }

    // MARK: - Device breakdown

    private func deviceBreakdownSection(_ rows: [DeviceBreakdownRow]) -> some View {
        let active = rows
            .filter { $0.plays + $0.downloads + $0.connects > 0 }
            .sorted { ($0.plays + $0.downloads) > ($1.plays + $1.downloads) }
            .prefix(6)
        return CardSection(title: "Device Activity") {
            ForEach(Array(active), id: \.deviceId) { d in
                HStack {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(d.name.isEmpty ? String(d.deviceId.suffix(8)) : d.name)
                            .fontWeight(.medium).lineLimit(1)
                        Text(d.platform).font(.caption2).foregroundStyle(BockColors.muted)
                    }
                    Spacer()
                    HStack(spacing: 12) {
                        if d.plays > 0 { miniStat("\(d.plays)", "plays") }
                        if d.downloads > 0 { miniStat("\(d.downloads)", "dl") }
                        if d.connects > 0 { miniStat("\(d.connects)", "conn") }
                    }
                }
                .padding(.vertical, 4)
            }
        }
    }

    private func miniStat(_ value: String, _ label: String) -> some View {
        VStack(alignment: .trailing, spacing: 0) {
            Text(value).fontWeight(.semibold).font(.subheadline)
            Text(label).font(.caption2).foregroundStyle(BockColors.muted)
        }
    }

    // MARK: - Activity

    private func activitySection(_ data: AnalyticsResponse) -> some View {
        let points: [ActivityPoint]
        switch activityPeriod {
        case .day: points = data.activity?.day ?? []
        case .week: points = data.activity?.week ?? []
        case .month: points = data.activity?.month ?? []
        case .year: points = data.activity?.year ?? []
        }
        return CardSection(title: "Activity over time") {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 6) {
                    ForEach(ActivityPeriod.allCases) { p in
                        Button(p.rawValue) { activityPeriod = p }
                            .buttonStyle(.bordered)
                            .controlSize(.small)
                            .tint(activityPeriod == p ? BockColors.green : BockColors.muted)
                    }
                }
            }
            if points.isEmpty || points.allSatisfy({ $0.count == 0 }) {
                Text("No plays in this range").foregroundStyle(BockColors.muted).font(.subheadline)
            } else {
                lineChart(points.suffix(48).map(\.count), height: 200)
            }
        }
    }

    private func hourDowSection(_ data: AnalyticsResponse) -> some View {
        let hours = (0..<24).map { h in data.hourOfDay.first(where: { $0.hour == h })?.count ?? 0 }
        let order = ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"]
        let days = order.map { d in data.dayOfWeek.first(where: { $0.day.caseInsensitiveCompare(d) == .orderedSame })?.count ?? 0 }
        return Section {
            HStack(alignment: .top, spacing: 12) {
                CardBody(title: "Hour of day") { columnChart(hours, height: 140, color: BockColors.green) }
                CardBody(title: "Day of week") { columnChart(days, height: 140, color: BockColors.gold) }
            }
            .listRowInsets(EdgeInsets(top: 4, leading: 0, bottom: 4, trailing: 0))
            .listRowBackground(Color.clear)
        }
    }

    private func decadeSection(_ decades: [DecadeRow]) -> some View {
        Group {
            if !decades.isEmpty {
                CardSection(title: "By decade") {
                    columnChart(decades.map(\.count), height: 160, color: Color(red: 0x8D / 255, green: 0x67 / 255, blue: 0xAB / 255))
                    HStack {
                        let labels = Array(decades.prefix(8).enumerated())
                        ForEach(labels, id: \.offset) { idx, d in
                            Text(d.decade ?? "—").font(.caption2).foregroundStyle(BockColors.muted)
                            if idx < labels.count - 1 { Spacer() }
                        }
                    }
                }
            }
        }
    }

    // MARK: - Heatmap

    private func heatmapSection(_ matrix: [[Int]]) -> some View {
        let maxVal = max(matrix.flatMap { $0 }.max() ?? 1, 1)
        return CardSection(title: "Listening heatmap") {
            HStack(spacing: 0) {
                Spacer().frame(width: 28)
                ForEach(["M", "T", "W", "T", "F", "S", "S"], id: \.self) { d in
                    Text(d).font(.caption2).foregroundStyle(BockColors.muted).frame(maxWidth: .infinity)
                }
            }
            ForEach(Array(matrix.enumerated()), id: \.offset) { hour, row in
                HStack(spacing: 0) {
                    Text(hourLabel(hour)).font(.caption2).foregroundStyle(BockColors.muted).frame(width: 28, alignment: .leading)
                    ForEach(Array(row.enumerated()), id: \.offset) { _, v in
                        let alpha = v > 0 ? 0.15 + (Double(v) / Double(maxVal)) * 0.85 : 0
                        RoundedRectangle(cornerRadius: 3)
                            .fill(v > 0 ? BockColors.green.opacity(alpha) : BockColors.surfaceVariant)
                            .aspectRatio(1.4, contentMode: .fit)
                            .padding(1)
                            .frame(maxWidth: .infinity)
                    }
                }
            }
        }
    }

    private func hourLabel(_ h: Int) -> String {
        switch h {
        case 0: return "12a"
        case 1..<12: return "\(h)a"
        case 12: return "12p"
        default: return "\(h - 12)p"
        }
    }

    // MARK: - Ranking

    @ViewBuilder
    private func rankingSection(_ title: String, _ rows: [CountRow], _ accent: Color) -> some View {
        if !rows.isEmpty {
            let items = Array(rows.prefix(8))
            let maxCount = max(items.map(\.count).max() ?? 1, 1)
            CardSection(title: title) {
                ForEach(Array(items.enumerated()), id: \.offset) { index, row in
                    VStack(alignment: .leading, spacing: 4) {
                        HStack {
                            Text("\(index + 1)").frame(width: 20, alignment: .leading)
                                .font(.caption).fontWeight(.bold).foregroundStyle(accent)
                            VStack(alignment: .leading, spacing: 0) {
                                Text(row.displayName).lineLimit(1).fontWeight(.medium)
                                if let artist = row.artist, !artist.isEmpty {
                                    Text(artist).font(.caption2).foregroundStyle(BockColors.muted).lineLimit(1)
                                }
                            }
                            Spacer()
                            Text(intFmt(row.count)).font(.subheadline).foregroundStyle(BockColors.muted)
                        }
                        GeometryReader { geo in
                            ZStack(alignment: .leading) {
                                RoundedRectangle(cornerRadius: 3).fill(BockColors.surfaceVariant)
                                RoundedRectangle(cornerRadius: 3).fill(accent)
                                    .frame(width: geo.size.width * CGFloat(row.count) / CGFloat(maxCount))
                            }
                        }
                        .frame(height: 6)
                    }
                    .padding(.vertical, 2)
                }
            }
        }
    }

    // MARK: - Ignored

    private var ignoredSection: some View {
        Section("Never play again") {
            if ignored.isEmpty {
                Text("No ignored tracks. Block a song from Now Playing to add it here.")
                    .foregroundStyle(BockColors.muted)
            }
            ForEach(ignored) { track in
                VStack(alignment: .leading, spacing: 2) {
                    Text(track.title ?? track.path.split(separator: "/").last.map(String.init) ?? track.path)
                    if track.artist != nil || track.album != nil {
                        Text([track.artist, track.album].compactMap { $0 }.joined(separator: " · "))
                            .font(.caption).foregroundStyle(BockColors.muted)
                    }
                }
                .swipeActions {
                    Button {
                        Task {
                            try? await appState.repository.removeIgnored(path: track.path)
                            await load()
                        }
                    } label: {
                        Text("Allow again")
                    }
                    .tint(BockColors.green)
                }
            }
        }
    }

    // MARK: - Charts

    private func lineChart(_ values: [Int], height: CGFloat) -> some View {
        let points = values.enumerated().map { IndexedValue(id: $0.offset, label: "\($0.offset)", value: $0.element) }
        return Chart(points) { p in
            LineMark(x: .value("t", p.id), y: .value("Plays", p.value))
                .foregroundStyle(BockColors.green)
                .interpolationMethod(.catmullRom)
            AreaMark(x: .value("t", p.id), y: .value("Plays", p.value))
                .foregroundStyle(BockColors.green.opacity(0.15))
                .interpolationMethod(.catmullRom)
        }
        .chartXAxis(.hidden)
        .frame(height: height)
    }

    private func columnChart(_ values: [Int], height: CGFloat, color: Color) -> some View {
        let points = values.enumerated().map { IndexedValue(id: $0.offset, label: "\($0.offset)", value: $0.element) }
        return Chart(points) { p in
            BarMark(x: .value("Bucket", p.id), y: .value("Plays", p.value))
                .foregroundStyle(color)
        }
        .chartXAxis(.hidden)
        .frame(height: height)
    }

    // MARK: - Data

    private func dateRange() -> (String?, String?) {
        let cal = Calendar.current
        let today = Date()
        switch preset {
        case .last7:
            return (isoDate(cal.date(byAdding: .day, value: -6, to: today) ?? today), isoDate(today))
        case .last30:
            return (isoDate(cal.date(byAdding: .day, value: -29, to: today) ?? today), isoDate(today))
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

    private func intFmt(_ value: Int) -> String {
        NumberFormatter.localizedString(from: NSNumber(value: value), number: .decimal)
    }

    private func formatPct(_ value: Double) -> String {
        value == value.rounded() ? String(Int(value)) : String(format: "%.1f", value)
    }

    private func load() async {
        loading = true
        defer { loading = false }
        let (from, to) = dateRange()
        let deviceId = deviceFilter.apiDeviceId(thisPhoneId: thisPhoneId)
        if let fresh = try? await appState.repository.analytics(from: from, to: to, deviceId: deviceId) {
            data = fresh
        }
        ignored = (try? await appState.repository.ignored()) ?? []
    }

    private func exportCSV() async {
        exporting = true
        defer { exporting = false }
        let (from, to) = dateRange()
        let deviceId = deviceFilter.apiDeviceId(thisPhoneId: thisPhoneId)
        if let url = try? await appState.repository.exportAnalyticsCSV(from: from, to: to, deviceId: deviceId) {
            exportURL = url
        }
    }
}

// MARK: - Card helpers

private struct StatTile {
    let icon: String
    let tint: Color
    let value: String
    let label: String
    let subtitle: String?
}

private struct StatCard: View {
    let tile: StatTile
    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Image(systemName: tile.icon).foregroundStyle(tile.tint).font(.title3)
            Text(tile.value).font(.title3).fontWeight(.bold)
            Text(tile.label).font(.caption).foregroundStyle(BockColors.muted)
            if let subtitle = tile.subtitle {
                Text(subtitle).font(.caption2).foregroundStyle(BockColors.muted).lineLimit(1)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(14)
        .background(BockColors.elevatedSurface, in: RoundedRectangle(cornerRadius: 14))
    }
}

private struct CardSection<Content: View>: View {
    let title: String
    @ViewBuilder let content: Content
    var body: some View {
        Section {
            CardBody(title: title) { content }
                .listRowInsets(EdgeInsets(top: 4, leading: 0, bottom: 4, trailing: 0))
                .listRowBackground(Color.clear)
        }
    }
}

private struct CardBody<Content: View>: View {
    let title: String
    @ViewBuilder let content: Content
    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(title).font(.subheadline).fontWeight(.semibold)
            content
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(14)
        .background(BockColors.elevatedSurface, in: RoundedRectangle(cornerRadius: 14))
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
