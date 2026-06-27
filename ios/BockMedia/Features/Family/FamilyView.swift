import SwiftUI

struct FamilyView: View {
    @ObservedObject var appState: AppState

    @State private var household = HouseholdResponse()
    @State private var rooms: [DeviceItem] = []
    @State private var stats: HouseholdAnalytics?
    @State private var messages: [FamilyMessage] = []
    @State private var loading = true

    @State private var activeMemberId: String = ActiveProfileStore.activeMemberId() ?? ""
    @State private var newMemberName = ""
    @State private var newMemberRole = "kid"
    @State private var messageText = ""
    @State private var messageTo: String = ""

    @State private var policyRoom: DeviceItem?
    @State private var pinMember: HouseholdMember?
    @State private var pendingRoomRequests: [(DeviceItem, RoomRequestItem)] = []

    private var ownerByDevice: [String: String] {
        Dictionary(uniqueKeysWithValues: household.deviceOwners.compactMap { o in
            o.memberId.map { (o.deviceId, $0) }
        })
    }

    private var activeMember: HouseholdMember? {
        household.members.first { $0.id == activeMemberId }
    }

    var body: some View {
        List {
            actingSection
            membersSection
            roomsSection
            roomRequestsSection
            statsSection
            messagesSection
        }
        .listStyle(.insetGrouped)
        .scrollContentBackground(.hidden)
        .bockBackground()
        .navigationTitle("Family")
        .navigationBarTitleDisplayMode(.inline)
        .refreshable { await load() }
        .task { await load() }
        .sheet(item: $policyRoom) { room in
            RoomPolicySheet(appState: appState, room: room, actingMemberId: activeMemberId) {
                policyRoom = nil
            }
        }
        .sheet(item: $pinMember) { member in
            SetPinSheet(appState: appState, member: member) {
                pinMember = nil
                Task { await load() }
            }
        }
    }

    // MARK: - Sections

    private var actingSection: some View {
        Section {
            Picker("Acting as", selection: $activeMemberId) {
                Text("Unattributed").tag("")
                ForEach(household.members) { m in
                    Text(m.name).tag(m.id)
                }
            }
            .onChange(of: activeMemberId) { _, newValue in
                ActiveProfileStore.setActiveMember(newValue.isEmpty ? nil : newValue)
                Task {
                    await ClientPrefsSync.onActiveMemberChanged(
                        repository: appState.repository,
                        memberId: newValue.isEmpty ? nil : newValue
                    )
                    await loadMessages()
                }
            }
        } footer: {
            Text("Attributes your plays and is used when sending messages, sharing playlists, and approving requests.")
        }
    }

    private var membersSection: some View {
        Section("Members") {
            if household.members.isEmpty {
                Text("No members yet. Add the people in your household.")
                    .font(.caption).foregroundStyle(BockColors.muted)
            }
            ForEach(household.members) { m in
                HStack {
                    MemberBadge(member: m)
                    Spacer()
                    if m.isParent {
                        Button(m.hasPin ? "PIN" : "Set PIN") { pinMember = m }
                            .font(.caption)
                    }
                    Menu {
                        Button("Make parent") { Task { await setRole(m, "parent") } }
                        Button("Make kid") { Task { await setRole(m, "kid") } }
                        Button("Remove", role: .destructive) { Task { await removeMember(m) } }
                    } label: {
                        Image(systemName: "ellipsis.circle")
                    }
                }
            }
            HStack {
                TextField("Name (e.g. Emma)", text: $newMemberName)
                Picker("", selection: $newMemberRole) {
                    Text("Kid").tag("kid")
                    Text("Parent").tag("parent")
                }
                .labelsHidden()
                Button("Add") { Task { await addMember() } }
                    .disabled(newMemberName.trimmingCharacters(in: .whitespaces).isEmpty)
            }
        }
    }

    private var roomsSection: some View {
        Section {
            if rooms.isEmpty {
                Text("No Echo rooms found.")
                    .font(.caption).foregroundStyle(BockColors.muted)
            }
            ForEach(rooms) { room in
                VStack(alignment: .leading, spacing: 6) {
                    Text(room.name ?? room.deviceId)
                        .foregroundStyle(BockColors.onSurface)
                    HStack {
                        Picker("Owner", selection: ownerBinding(for: room)) {
                            Text("Unattributed").tag("")
                            ForEach(household.members) { m in
                                Text(m.name).tag(m.id)
                            }
                        }
                        .pickerStyle(.menu)
                        Spacer()
                        Button {
                            policyRoom = room
                        } label: {
                            Label("Kid-safe", systemImage: "shield.lefthalf.filled")
                                .font(.caption)
                        }
                    }
                }
            }
        } header: {
            Text("Rooms — owner & kid-safe")
        } footer: {
            Text("Assign a room to a person (e.g. Office → Andy). Leave community rooms (Kitchen, Living Room, Basement, Garage) unattributed.")
        }
    }

    private var roomRequestsSection: some View {
        Section("Room requests") {
            if pendingRoomRequests.isEmpty {
                Text("No pending room requests.")
                    .font(.caption).foregroundStyle(BockColors.muted)
            }
            ForEach(pendingRoomRequests, id: \.1.id) { room, req in
                HStack {
                    VStack(alignment: .leading) {
                        Text(req.track ?? "Track")
                        Text("\(room.name ?? room.deviceId) · \(req.byMemberName ?? "Someone")")
                            .font(.caption).foregroundStyle(BockColors.muted)
                    }
                    Spacer()
                    if req.status == "queued", activeMember?.isParent == true {
                        Button("Approve") {
                            Task { await approveRoomRequest(room: room, requestId: req.id) }
                        }
                        .font(.caption)
                    }
                    Button(role: .destructive) {
                        Task { await deleteRoomRequest(room: room, requestId: req.id) }
                    } label: {
                        Image(systemName: "trash")
                    }
                }
            }
        }
    }

    @ViewBuilder
    private var statsSection: some View {
        if let s = stats, !s.byMember.isEmpty {
            Section("Family activity · \(s.totalPlays) plays") {
                ForEach(s.byMember.prefix(8).map { $0 }) { row in
                    let maxPlays = max(1, s.byMember.map(\.plays).max() ?? 1)
                    VStack(alignment: .leading, spacing: 4) {
                        HStack {
                            Text(row.name).font(.subheadline)
                            Spacer()
                            Text("\(row.plays)").font(.caption).foregroundStyle(BockColors.muted)
                        }
                        ProgressView(value: Double(row.plays), total: Double(maxPlays))
                            .tint(BockColors.green)
                    }
                }
                if !s.byPlatform.isEmpty {
                    Text(s.byPlatform.map { "\($0.platform) \($0.plays)" }.joined(separator: " · "))
                        .font(.caption).foregroundStyle(BockColors.muted)
                }
            }
        }
    }

    private var messagesSection: some View {
        Section("Music messages") {
            HStack {
                Picker("", selection: $messageTo) {
                    Text("Everyone").tag("")
                    ForEach(household.members) { m in Text(m.name).tag(m.id) }
                }
                .labelsHidden()
                TextField("Say something…", text: $messageText)
                Button("Send") { Task { await sendMessage() } }
                    .disabled(messageText.trimmingCharacters(in: .whitespaces).isEmpty)
            }
            ForEach(messages.prefix(30)) { m in
                VStack(alignment: .leading, spacing: 2) {
                    HStack {
                        Text(m.fromName ?? "Someone").font(.subheadline).bold()
                        if let to = m.toName, !to.isEmpty {
                            Text("→ \(to)").font(.caption).foregroundStyle(BockColors.muted)
                        } else if m.scope == "household" {
                            Text("→ all").font(.caption).foregroundStyle(BockColors.muted)
                        }
                        Spacer()
                    }
                    if let text = m.text, !text.isEmpty {
                        Text(text).font(.subheadline).foregroundStyle(BockColors.onSurface)
                    }
                }
            }
        }
    }

    // MARK: - Bindings / actions

    private func ownerBinding(for room: DeviceItem) -> Binding<String> {
        Binding(
            get: { ownerByDevice[room.deviceId] ?? "" },
            set: { newValue in Task { await setOwner(room, newValue) } }
        )
    }

    private func load() async {
        loading = true
        defer { loading = false }
        household = (try? await appState.repository.household()) ?? HouseholdResponse()
        let all = (try? await appState.repository.devices()) ?? []
        rooms = all.filter { !$0.deviceId.hasPrefix("client-") }
        stats = try? await appState.repository.householdAnalytics()
        await loadMessages()
        await loadRoomRequests()
    }

    private func loadRoomRequests() async {
        var pending: [(DeviceItem, RoomRequestItem)] = []
        for room in rooms {
            guard let q = try? await appState.repository.roomQueue(deviceId: room.deviceId) else { continue }
            for req in q.queue where req.status == "queued" || req.status == "approved" {
                pending.append((room, req))
            }
        }
        pendingRoomRequests = pending
    }

    private func approveRoomRequest(room: DeviceItem, requestId: String) async {
        let me = ActiveProfileStore.activeMemberId() ?? ""
        guard let pin = ParentPinCache.get(memberId: me), !pin.isEmpty else {
            appState.toast = "Set a parent PIN first"
            return
        }
        _ = try? await appState.repository.approveRoomRequest(deviceId: room.deviceId, requestId: requestId, pin: pin)
        await loadRoomRequests()
    }

    private func deleteRoomRequest(room: DeviceItem, requestId: String) async {
        try? await appState.repository.deleteRoomRequest(deviceId: room.deviceId, requestId: requestId)
        await loadRoomRequests()
    }

    private func loadMessages() async {
        let me = ActiveProfileStore.activeMemberId()
        let resp = try? await appState.repository.messages(member: me)
        messages = (resp?.items ?? []).reversed()
    }

    private func addMember() async {
        let name = newMemberName.trimmingCharacters(in: .whitespaces)
        guard !name.isEmpty else { return }
        do {
            _ = try await appState.repository.createMember(name: name, role: newMemberRole)
            newMemberName = ""
            appState.toast = "Added \(name)"
            await load()
        } catch { appState.toast = "Couldn't add member" }
    }

    private func setRole(_ m: HouseholdMember, _ role: String) async {
        do {
            _ = try await appState.repository.updateMember(id: m.id, role: role)
            await load()
        } catch { appState.toast = "Couldn't update" }
    }

    private func removeMember(_ m: HouseholdMember) async {
        do {
            try await appState.repository.deleteMember(id: m.id)
            if activeMemberId == m.id { activeMemberId = ""; ActiveProfileStore.setActiveMember(nil) }
            await load()
        } catch { appState.toast = "Couldn't remove" }
    }

    private func setOwner(_ room: DeviceItem, _ memberId: String) async {
        do {
            try await appState.repository.setDeviceOwner(deviceId: room.deviceId, memberId: memberId.isEmpty ? nil : memberId)
            household = (try? await appState.repository.household()) ?? household
        } catch { appState.toast = "Couldn't set owner" }
    }

    private func sendMessage() async {
        let text = messageText.trimmingCharacters(in: .whitespaces)
        guard !text.isEmpty else { return }
        do {
            _ = try await appState.repository.sendMessage(
                fromMemberId: ActiveProfileStore.activeMemberId(),
                toMemberId: messageTo.isEmpty ? nil : messageTo,
                text: text
            )
            messageText = ""
            await loadMessages()
        } catch { appState.toast = "Couldn't send" }
    }
}

// MARK: - Member badge

private struct MemberBadge: View {
    let member: HouseholdMember

    var body: some View {
        HStack(spacing: 8) {
            Circle()
                .fill(color)
                .frame(width: 24, height: 24)
                .overlay(Text(initial).font(.caption).bold().foregroundStyle(.white))
            Text(member.name).foregroundStyle(BockColors.onSurface)
            if member.isParent {
                Image(systemName: "person.badge.shield.checkmark")
                    .font(.caption2).foregroundStyle(BockColors.muted)
            }
        }
    }

    private var initial: String { String(member.name.prefix(1)).uppercased() }
    private var color: Color {
        if let hex = member.color, let c = Color(hex: hex) { return c }
        return BockColors.green
    }
}

// MARK: - Set parent PIN

private struct SetPinSheet: View {
    @ObservedObject var appState: AppState
    let member: HouseholdMember
    let onDone: () -> Void

    @State private var currentPin = ""
    @State private var newPin = ""
    @State private var saving = false
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            Form {
                if member.hasPin {
                    Section("Current PIN") {
                        SecureField("Current PIN", text: $currentPin)
                            .keyboardType(.numberPad)
                    }
                }
                Section("New PIN") {
                    SecureField("4+ digits", text: $newPin)
                        .keyboardType(.numberPad)
                }
            }
            .navigationTitle("Parent PIN")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") { Task { await save() } }
                        .disabled(newPin.count < 4 || saving)
                }
            }
        }
    }

    private func save() async {
        saving = true
        defer { saving = false }
        do {
            let resp = try await appState.repository.setMemberPin(
                id: member.id, pin: newPin, currentPin: member.hasPin ? currentPin : nil
            )
            if resp.ok {
                ParentPinCache.set(memberId: member.id, pin: newPin)
                appState.toast = "PIN set"
                onDone()
            } else {
                appState.toast = resp.error ?? "Couldn't set PIN"
            }
        } catch { appState.toast = "Couldn't set PIN" }
    }
}

/// In-memory parent PIN cache for the session so kid-safe edits don't re-prompt.
enum ParentPinCache {
    private static var pins: [String: String] = [:]
    static func set(memberId: String, pin: String) { pins[memberId] = pin }
    static func get(memberId: String) -> String? { pins[memberId] }
}

extension Color {
    init?(hex: String) {
        var s = hex.trimmingCharacters(in: .whitespacesAndNewlines)
        if s.hasPrefix("#") { s.removeFirst() }
        guard s.count == 6, let v = UInt32(s, radix: 16) else { return nil }
        self = Color(
            red: Double((v >> 16) & 0xFF) / 255,
            green: Double((v >> 8) & 0xFF) / 255,
            blue: Double(v & 0xFF) / 255
        )
    }
}
