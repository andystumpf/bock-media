import SwiftUI

struct SetupView: View {
    @ObservedObject var appState: AppState

    @State private var adminUser = BuildConfig.adminUser
    @State private var adminPass = BuildConfig.adminPassword
    @State private var mobileToken = BuildConfig.mobileApiToken
    @State private var rememberMe = true
    @State private var error: String?
    @State private var loading = false

    var body: some View {
        ScrollView {
            VStack(spacing: 16) {
                BockIcon(icon: .libraryMusic, size: 56)
                    .foregroundStyle(BockColors.green)
                    .padding(.top, 8)
                Text("Bock Media")
                    .font(.largeTitle.bold())
                    .foregroundStyle(BockColors.onSurface)
                Text("Sign in to your server")
                    .foregroundStyle(BockColors.muted)

                field("Username (external only)", text: $adminUser)
                SecureField("Password (external only)", text: $adminPass)
                    .textFieldStyle(.roundedBorder)
                field("Mobile API token", text: $mobileToken)

                Toggle("Remember me", isOn: $rememberMe)
                    .foregroundStyle(BockColors.onSurface)

                if let error {
                    Text(error)
                        .foregroundStyle(.red)
                        .font(.subheadline)
                }

                Button {
                    Task { await signIn() }
                } label: {
                    Group {
                        if loading {
                            ProgressView()
                        } else {
                            Text("Sign in").frame(maxWidth: .infinity)
                        }
                    }
                }
                .buttonStyle(.borderedProminent)
                .tint(BockColors.green)
                .disabled(loading)

                Text("Uses your home Wi‑Fi server when reachable, otherwise your external address.\nPassword is only sent when away from home.")
                    .font(.caption)
                    .foregroundStyle(BockColors.muted)
                    .multilineTextAlignment(.center)
            }
            .padding(24)
            .frame(maxWidth: .infinity)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(
            LinearGradient(
                colors: [BockColors.homeGradientTop, BockColors.black],
                startPoint: .top,
                endPoint: .bottom
            )
            .ignoresSafeArea()
        )
        .onAppear {
            appState.preferences.applyBuildServerURLs()
            adminUser = appState.preferences.adminUser ?? BuildConfig.adminUser
            adminPass = appState.preferences.adminPass ?? BuildConfig.adminPassword
            mobileToken = appState.preferences.mobileToken ?? BuildConfig.mobileApiToken
            rememberMe = appState.preferences.rememberMe
        }
    }

    private func field(_ label: String, text: Binding<String>) -> some View {
        TextField(label, text: text)
            .textFieldStyle(.roundedBorder)
            .autocorrectionDisabled()
            .textInputAutocapitalization(.never)
    }

    private func signIn() async {
        guard !adminUser.trimmingCharacters(in: .whitespaces).isEmpty,
              !adminPass.isEmpty else {
            error = "Username and password required"
            return
        }
        loading = true
        error = nil
        defer { loading = false }
        do {
            try await appState.connect(
                user: adminUser.trimmingCharacters(in: .whitespaces),
                pass: adminPass,
                token: mobileToken,
                rememberMe: rememberMe
            )
        } catch {
            self.error = error.localizedDescription
        }
    }
}
