import AVFoundation
import Foundation
import Speech

enum VoiceWakeDeviceOptions {
    struct Option: Identifiable, Encodable, Equatable {
        let id: String
        let name: String
    }

    static func microphones() -> [Option] {
        let discovery = AVCaptureDevice.DiscoverySession(
            deviceTypes: [.external, .microphone],
            mediaType: .audio,
            position: .unspecified)
        let aliveUIDs = AudioInputDeviceObserver.aliveInputDeviceUIDs()
        let connectedDevices = discovery.devices.filter(\.isConnected)
        let devices = aliveUIDs.isEmpty
            ? connectedDevices
            : connectedDevices.filter { aliveUIDs.contains($0.uniqueID) }
        return devices.map { Option(id: $0.uniqueID, name: $0.localizedName) }
    }

    static func locales() -> [Option] {
        SFSpeechRecognizer.supportedLocales()
            .map { Option(id: $0.identifier, name: self.friendlyName(for: $0)) }
            .sorted { $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending }
    }

    private static func friendlyName(for locale: Locale) -> String {
        let cleanedID = normalizeLocaleIdentifier(locale.identifier)
        let cleanLocale = Locale(identifier: cleanedID)
        if let langCode = cleanLocale.language.languageCode?.identifier,
           let lang = cleanLocale.localizedString(forLanguageCode: langCode),
           let regionCode = cleanLocale.region?.identifier,
           let region = cleanLocale.localizedString(forRegionCode: regionCode)
        {
            return "\(lang) (\(region))"
        }
        if let langCode = cleanLocale.language.languageCode?.identifier,
           let lang = cleanLocale.localizedString(forLanguageCode: langCode)
        {
            return lang
        }
        return cleanLocale.localizedString(forIdentifier: cleanedID) ?? cleanedID
    }
}
