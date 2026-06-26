import CommonCrypto
import Foundation
import UIKit

/// Stable hardware id for this phone — survives app reinstall (not factory reset).
enum InstallIdentity {
    static func phoneId() -> String {
        guard let raw = UIDevice.current.identifierForVendor?.uuidString.trimmingCharacters(in: .whitespacesAndNewlines),
              !raw.isEmpty else { return "" }
        return sha256(raw)
    }

    private static func sha256(_ text: String) -> String {
        guard let data = text.data(using: .utf8) else { return "" }
        var hash = [UInt8](repeating: 0, count: Int(CC_SHA256_DIGEST_LENGTH))
        data.withUnsafeBytes { buffer in
            _ = CC_SHA256(buffer.baseAddress, CC_LONG(buffer.count), &hash)
        }
        return hash.map { String(format: "%02x", $0) }.joined()
    }
}
