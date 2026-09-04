import Foundation

@MainActor
enum DeviceSettingsConsent: Equatable {
    case cookieSync
    case cookieDomains([String])
    case cookieProfile(String)
    case computerControl
    case peekabooBridge
    case camera

    static func required(
        for key: DeviceSettingKey,
        value: DeviceSettingValue,
        cookieSyncEnabled: Bool,
        cookieDomains: [String],
        cookieProfile: String) -> Self?
    {
        switch (key, value) {
        case (.cookieSyncEnabled, .boolean(true)): .cookieSync
        case (.computerControlEnabled, .boolean(true)): .computerControl
        case (.peekabooBridgeEnabled, .boolean(true)): .peekabooBridge
        case (.cameraEnabled, .boolean(true)): .camera
        case let (.cookieSyncDomains, .strings(domains)):
            Self.addedCookieDomains(domains, current: cookieDomains)
        case let (.cookieSyncTargetProfile, .string(profile)):
            Self.changedCookieProfile(profile, current: cookieProfile, enabled: cookieSyncEnabled)
        default: nil
        }
    }

    private static func addedCookieDomains(_ domains: [String], current: [String]) -> Self? {
        let existing = Set(CookieSyncManager.normalizedDomains(current).map { $0.lowercased() })
        let added = CookieSyncManager.normalizedDomains(domains).filter { !existing.contains($0.lowercased()) }
        return added.isEmpty ? nil : .cookieDomains(added)
    }

    private static func changedCookieProfile(_ profile: String, current: String, enabled: Bool) -> Self? {
        let profile = profile.trimmingCharacters(in: .whitespacesAndNewlines).nonEmpty ?? "imported"
        return enabled && profile != current ? .cookieProfile(profile) : nil
    }

    var message: String {
        switch self {
        case .cookieSync:
            String(localized: "Allow browser cookie sync from this Mac?")
        case .cookieDomains:
            String(localized: "Allow cookie sync for additional domains?")
        case .cookieProfile:
            String(localized: "Change the browser cookie sync destination?")
        case .computerControl:
            String(localized: "Allow the Gateway to control this Mac?")
        case .peekabooBridge:
            String(localized: "Enable the Peekaboo bridge on this Mac?")
        case .camera:
            String(localized: "Allow the Gateway to use this Mac's camera?")
        }
    }

    var detail: String {
        switch self {
        case .cookieSync:
            String(
                localized: """
                Browser cookies for the configured domains will be sent to your remote Gateway. \
                These cookies can grant access to your signed-in accounts.
                """)
        case let .cookieDomains(domains):
            String(
                format: String(
                    localized: """
                    Cookies for these additional domains can be sent to your remote Gateway when sync is enabled: %@. \
                    These cookies can grant access to your signed-in accounts.
                    """),
                domains.joined(separator: ", "))
        case let .cookieProfile(profile):
            String(
                format: String(
                    localized: """
                    Cookie sync is enabled. Browser cookies will be sent to the remote browser profile “%@”. \
                    These cookies can grant access to your signed-in accounts.
                    """),
                profile)
        case .computerControl:
            String(
                localized: """
                The Gateway can capture your screen and interact with apps on this Mac, \
                including clicking and typing, subject to macOS permissions.
                """)
        case .peekabooBridge:
            String(
                localized: """
                Local automation clients can use the Peekaboo bridge to capture your screen and control apps \
                on this Mac using OpenClaw's macOS permissions.
                """)
        case .camera:
            String(
                localized: """
                The Gateway can request photos and video from this Mac's camera, subject to macOS permission.
                """)
        }
    }
}
