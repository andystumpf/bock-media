import SwiftUI

/// Blocks the app until a household profile is chosen (required after reinstall).
struct ProfilePickerGate<Content: View>: View {
    @ObservedObject var appState: AppState
    @ViewBuilder var content: () -> Content

    @State private var members: [HouseholdMember]?
    @State private var loading = false

    private var activeId: String? { ActiveProfileStore.activeMemberId() }

    var body: some View {
        Group {
            if activeId != nil || members?.isEmpty != false {
                content()
            } else if members?.count == 1, let only = members?.first?.id {
                content()
                    .task(id: only) {
                        ActiveProfileStore.setActiveMember(only)
                        try? await appState.repository.bindClient(
                            clientId: ClientIdStore.clientId(),
                            memberId: only,
                            phoneId: InstallIdentity.phoneId()
                        )
                        await ClientPrefsSync.pullAndApply(repository: appState.repository)
                    }
            } else {
                content()
                    .overlay {
                        profilePickerDialog
                    }
            }
        }
        .task {
            guard activeId == nil else { return }
            members = try? await appState.repository.household().members
        }
    }

    @ViewBuilder
    private var profilePickerDialog: some View {
        ZStack {
            Color.black.opacity(0.55).ignoresSafeArea()
            VStack(alignment: .leading, spacing: 16) {
                Text("Who's listening?")
                    .font(.headline)
                Text("Choose your profile so ratings and settings restore from the server.")
                    .font(.subheadline)
                    .foregroundStyle(BockColors.muted)
                if loading {
                    ProgressView()
                        .frame(maxWidth: .infinity)
                } else {
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
                        Divider()
                    }
                }
            }
            .padding(20)
            .background(BockColors.sheetBg)
            .clipShape(RoundedRectangle(cornerRadius: 16))
            .padding(24)
        }
    }

    private func pick(_ member: HouseholdMember) async {
        loading = true
        defer { loading = false }
        ActiveProfileStore.setActiveMember(member.id)
        try? await appState.repository.bindClient(
            clientId: ClientIdStore.clientId(),
            memberId: member.id,
            phoneId: InstallIdentity.phoneId()
        )
        await ClientPrefsSync.pullAndApply(repository: appState.repository)
    }
}
