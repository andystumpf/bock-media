import SwiftUI

/// Blocks until the user picks a household profile or explicitly continues unattributed.
struct ProfilePickerGate<Content: View>: View {
    @ObservedObject var appState: AppState
    @ViewBuilder var content: () -> Content

    @State private var members: [HouseholdMember]?
    @State private var loadFailed = false
    @State private var profileSyncing = false
    @State private var storeRevision = HouseholdStore.revision

    private var choiceMade: Bool { ActiveProfileStore.hasProfileChoice() }

    var body: some View {
        Group {
            if UITestSupport.isEnabled {
                content()
            } else if choiceMade && !profileSyncing && appState.shellReady {
                content()
            } else if !appState.shellReady || profileSyncing || (members == nil && !loadFailed) {
                LoadingBox()
            } else if loadFailed {
                pickerError
            } else if members?.isEmpty == true {
                content()
            } else {
                ZStack {
                    Color.black.opacity(0.55).ignoresSafeArea()
                    profilePickerDialog
                }
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .task(id: "\(choiceMade)-\(storeRevision)-\(appState.shellReady)") {
            guard appState.shellReady else { return }
            guard !choiceMade else { return }
            await loadMembers()
        }
        .onReceive(NotificationCenter.default.publisher(for: HouseholdStore.changedNotification)) { _ in
            storeRevision = HouseholdStore.revision
            guard !choiceMade else { return }
            let fresh = HouseholdStore.members()
            if !fresh.isEmpty {
                members = fresh
                loadFailed = false
            }
        }
    }

    private var pickerError: some View {
        VStack(spacing: 12) {
            Text("Couldn't load family profiles")
                .font(.headline)
            Text(HouseholdStore.lastLoadError ?? "Check your connection and try again.")
                .font(.subheadline)
                .foregroundStyle(BockColors.muted)
                .multilineTextAlignment(.center)
            Button("Retry") {
                Task { await loadMembers() }
            }
            .foregroundStyle(BockColors.green)
            Button("Continue unattributed") {
                Task { await pickUnattributed() }
            }
            .font(.subheadline)
            .foregroundStyle(BockColors.muted)
        }
        .padding(24)
    }

    private func loadMembers() async {
        loadFailed = false
        if let cached = HouseholdStore.cached, !cached.members.isEmpty {
            members = cached.members
            return
        }
        if UITestSupport.isEnabled {
            members = []
            if !ActiveProfileStore.hasProfileChoice() {
                await pickUnattributed()
            }
            return
        }
        let fetched = await withAsyncTimeout(seconds: 12) {
            await HouseholdStore.refresh(repository: appState.repository, force: true)
        }
        switch fetched {
        case .success(let response):
            members = response.members
            loadFailed = response.members.isEmpty && HouseholdStore.lastLoadError != nil
        case .timedOut:
            let cached = HouseholdStore.members()
            if cached.isEmpty {
                members = nil
                loadFailed = true
            } else {
                members = cached
            }
        }
    }

    @ViewBuilder
    private var profilePickerDialog: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text("Who's listening?")
                .font(.headline)
            Text(
                "Pick a profile to restore your ratings and settings, " +
                "or continue unattributed until you choose later in Family."
            )
            .font(.subheadline)
            .foregroundStyle(BockColors.muted)
            ForEach(members ?? []) { member in
                Button {
                    Task { await pick(member) }
                } label: {
                    HStack {
                        VStack(alignment: .leading) {
                            Text(member.name)
                            Text(member.isParent ? "Parent" : "Kid")
                                .font(.caption)
                                .foregroundStyle(BockColors.muted)
                        }
                        Spacer()
                    }
                    .padding(.vertical, 8)
                }
                .buttonStyle(.plain)
                .disabled(profileSyncing)
                .accessibilityIdentifier(BockTestTags.profileMemberPick(member.id))
                Divider()
            }
            Button("Continue unattributed") {
                Task { await pickUnattributed() }
            }
            .frame(maxWidth: .infinity, alignment: .trailing)
            .foregroundStyle(BockColors.green)
            .disabled(profileSyncing)
            .accessibilityIdentifier(BockTestTags.profileContinueUnattributed)
        }
        .padding(20)
        .background(BockColors.sheetBg)
        .clipShape(RoundedRectangle(cornerRadius: 16))
        .padding(24)
        .accessibilityIdentifier(BockTestTags.profilePicker)
    }

    private func pick(_ member: HouseholdMember) async {
        profileSyncing = true
        defer { profileSyncing = false }
        let previous = ActiveProfileStore.activeMemberId()
        await ClientPrefsSync.onActiveMemberChanged(
            repository: appState.repository,
            memberId: member.id,
            previousMemberId: previous
        )
    }

    private func pickUnattributed() async {
        profileSyncing = true
        defer { profileSyncing = false }
        let previous = ActiveProfileStore.activeMemberId()
        await ClientPrefsSync.onActiveMemberChanged(
            repository: appState.repository,
            memberId: nil,
            previousMemberId: previous
        )
    }
}
