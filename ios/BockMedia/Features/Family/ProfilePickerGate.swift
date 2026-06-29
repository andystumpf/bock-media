import SwiftUI

/// Blocks the app until a household profile is chosen (required after reinstall).
struct ProfilePickerGate<Content: View>: View {
    @ObservedObject var appState: AppState
    @ViewBuilder var content: () -> Content

    @State private var members: [HouseholdMember]?
    @State private var loading = false
    @State private var profileReady = ActiveProfileStore.activeMemberId() != nil

    var body: some View {
        Group {
            if profileReady || members?.isEmpty == true {
                content()
            } else if members == nil {
                ProgressView()
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else if members?.count == 1, let only = members?.first?.id {
                content()
                    .task(id: only) {
                        await pickMemberId(only)
                    }
            } else {
                content()
                    .overlay {
                        profilePickerDialog
                    }
            }
        }
        .task {
            guard !profileReady else { return }
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
        .accessibilityIdentifier(BockTestTags.profilePicker)
    }

    private func pickMemberId(_ memberId: String) async {
        guard !profileReady else { return }
        profileReady = true
        await ClientPrefsSync.onActiveMemberChanged(repository: appState.repository, memberId: memberId)
    }

    private func pick(_ member: HouseholdMember) async {
        loading = true
        profileReady = true
        defer { loading = false }
        await ClientPrefsSync.onActiveMemberChanged(repository: appState.repository, memberId: member.id)
    }
}
