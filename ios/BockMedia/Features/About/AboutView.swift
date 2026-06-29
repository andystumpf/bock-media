import SwiftUI

enum AboutLinks {
    static let githubPublic = "https://github.com/andystumpf/ourMedia"
    static let githubPrivate = "https://github.com/andystumpf/ourMedia"
}

struct AboutView: View {
    @ObservedObject var appState: AppState
    @Environment(\.openURL) private var openURL
    @Environment(\.dismiss) private var dismiss

    @State private var downloadURL: URL?
    @State private var githubPublic = AboutLinks.githubPublic
    @State private var githubPrivate = AboutLinks.githubPrivate

    private var appVersion: String {
        Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "—"
    }

    var body: some View {
        List {
            Section {
                VStack(alignment: .leading, spacing: 4) {
                    Text("Bock Media Console")
                        .font(.title3.bold())
                    Text("iPhone app")
                        .font(.subheadline)
                        .foregroundStyle(BockColors.muted)
                }
                .listRowBackground(Color.clear)
            }

            Section("Version") {
                Text(appVersion)
                    .font(.title3.weight(.semibold))
            }

            Section("Download apps") {
                Text("Install the Android or iPhone app from your server.")
                    .font(.subheadline)
                    .foregroundStyle(BockColors.muted)
                if let downloadURL {
                    Button {
                        openURL(downloadURL)
                    } label: {
                        Label("Mobile app downloads", icon: .download, size: 20)
                    }
                } else {
                    Text("Server public URL not configured.")
                        .font(.caption)
                        .foregroundStyle(BockColors.muted)
                }
            }

            Section("Source code") {
                linkRow("Public repository", url: githubPublic)
                linkRow("Private repository", url: githubPrivate)
            }
        }
        .scrollContentBackground(.hidden)
        .bockBackground()
        .navigationTitle("About")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .cancellationAction) {
                Button("Done") { dismiss() }
            }
        }
        .task { await load() }
    }

    @ViewBuilder
    private func linkRow(_ title: String, url: String) -> some View {
        if let link = URL(string: url) {
            Button {
                openURL(link)
            } label: {
                HStack {
                    Text(title)
                    Spacer()
                    Image(systemName: "arrow.up.right")
                        .font(.caption)
                        .foregroundStyle(BockColors.muted)
                }
            }
        }
    }

    private func load() async {
        guard let config = try? await appState.repository.loadConfigJSON() else { return }
        if let publicUrl = (config["publicUrl"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines),
           !publicUrl.isEmpty {
            let base = publicUrl.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
            downloadURL = URL(string: "\(base)/app")
        }
        if let about = config["appAbout"] as? [String: Any] {
            if let pub = about["githubPublic"] as? String, !pub.isEmpty {
                githubPublic = pub
            }
            if let priv = about["githubPrivate"] as? String, !priv.isEmpty {
                githubPrivate = priv
            }
        }
    }
}
