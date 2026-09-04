import AppKit
import CoreLocation
import Foundation
import OpenClawKit
import WebKit

extension Notification.Name {
    static let openclawDeviceSettingsChanged = Notification.Name("openclaw.deviceSettings.changed")
}

extension DashboardWindowController {
    static let deviceSettingsMessageHandlerName = "openclawDeviceSettings"

    func receiveDeviceSettingsMessage(_ message: WKScriptMessage) {
        guard message.name == Self.deviceSettingsMessageHandlerName,
              message.webView === self.webView, message.frameInfo.isMainFrame
        else { return }
        let request = DeviceSettingsRequest(body: message.body)
        if self.isShowingFailurePage,
           message.frameInfo.request.url?.absoluteString == "about:blank",
           self.webView.url?.absoluteString == "about:blank",
           request == .open(.connection)
        {
            // Only this action belongs to the native-authored error document. It never receives device data.
            AppNavigationActions.openConnection()
            return
        }
        guard Self.isTrustedLinkSource(message.frameInfo.request.url, dashboardURL: self.currentURL) else { return }
        self.deviceSettingsMessageHandler.enqueue(request, sourceID: self.notificationSourceID)
    }

    func applyDeviceSettingsRequest(_ request: DeviceSettingsRequest?) async {
        switch request {
        case .status:
            await self.publishDeviceSettings()
            await BrowserProfileImportModel.shared.refreshAvailability()
        case let .set(key, value):
            if await self.confirmDeviceSetting(key, value: value) {
                await self.setDeviceSetting(key, value: value)
            }
        case let .requestPermission(id):
            _ = await PermissionManager.ensure([id.capability], interactive: true)
            await PermissionMonitor.shared.refreshNow()
        case let .openSystemSettings(id):
            SystemSettingsURLSupport.openPrivacySettings(for: id.capability)
        case let .open(panel):
            await self.openDeviceSettingsPanel(panel)
        case .checkForUpdates:
            if self.updater?.isAvailable == true { self.updater?.checkForUpdates(nil) }
        case nil:
            break
        }
        // All Gateway windows show settings for this Mac; mutations must update each open view.
        NotificationCenter.default.post(name: .openclawDeviceSettingsChanged, object: nil)
    }

    private func confirmDeviceSetting(_ key: DeviceSettingKey, value: DeviceSettingValue) async -> Bool {
        let state = AppStateStore.shared
        let locationMode = AppDefaults.standard.string(forKey: locationModeKey)
            .flatMap(OpenClawLocationMode.init(rawValue:)) ?? .off
        guard let consent = DeviceSettingsConsent.required(
            for: key,
            value: value,
            cookieSyncEnabled: state.cookieSyncEnabled,
            cookieDomains: state.cookieSyncDomains,
            cookieProfile: state.cookieSyncIntoProfile,
            locationMode: DeviceSettingsLocationMode(locationMode))
        else { return true }
        // Origin trust is not user intent: Gateway-authored pages cannot enable sensitive access on their own.
        let sourceID = self.notificationSourceID
        guard await self.deviceSettingsMessageHandler.confirm(consent) else { return false }
        return !Task.isCancelled && self.isWindowOpen && self.notificationSourceID == sourceID &&
            !self.isShowingFailurePage && Self.isTrustedLinkSource(self.webView.url, dashboardURL: self.currentURL)
    }

    private static let booleanStateSettings: [DeviceSettingKey: ReferenceWritableKeyPath<AppState, Bool>] = [
        .showDockIcon: \.showDockIcon,
        .iconAnimationsEnabled: \.iconAnimationsEnabled,
        .debugPaneEnabled: \.debugPaneEnabled,
        .peekabooBridgeEnabled: \.peekabooBridgeEnabled,
        .activeComputerPresenceEnabled: \.activeComputerPresenceEnabled,
        .cookieSyncEnabled: \.cookieSyncEnabled,
        .wakeTriggersTalkMode: \.voiceWakeTriggersTalkMode,
        .pushToTalkEnabled: \.voicePushToTalkEnabled,
        .talkPhaseSoundsEnabled: \.talkPhaseSoundsEnabled,
        .talkShiftToStopEnabled: \.talkShiftToStopEnabled,
        .realtimeRelayEnabled: \.talkRealtimeRelayEnabled,
    ]

    private func setDeviceSetting(_ key: DeviceSettingKey, value: DeviceSettingValue) async {
        switch value {
        case let .boolean(enabled):
            await self.setDeviceBoolean(key, enabled: enabled)
        case let .string(value):
            await self.setDeviceString(key, value: value)
        case let .strings(values):
            let state = AppStateStore.shared
            if key == .cookieSyncDomains {
                state.cookieSyncDomains = CookieSyncManager.normalizedDomains(values)
            } else if key == .localeAdditional {
                let available = Set(VoiceWakeDeviceOptions.locales().map(\.id))
                guard values.allSatisfy(available.contains) else { return }
                state.voiceWakeAdditionalLocaleIDs = values
            }
        case .null:
            guard key == .microphone else { return }
            AppStateStore.shared.voiceWakeMicName = ""
            AppStateStore.shared.voiceWakeMicID = ""
        }
    }

    private func setDeviceBoolean(_ key: DeviceSettingKey, enabled: Bool) async {
        let state = AppStateStore.shared
        let defaults = AppDefaults.standard
        if let keyPath = Self.booleanStateSettings[key] {
            state[keyPath: keyPath] = enabled
            return
        }
        switch key {
        case .launchAtLogin:
            guard !enabled || self.deviceLaunchAtLoginAvailable else { return }
            state.launchAtLogin = enabled
        case .quickChatEnabled:
            state.quickChatEnabled = enabled
            QuickChatController.shared.setEnabled(enabled)
        case .canvasEnabled:
            state.canvasEnabled = enabled
            if !enabled { CanvasManager.shared.hideAll() }
        case .cameraEnabled:
            defaults.set(enabled, forKey: cameraEnabledKey)
        case .computerControlEnabled:
            defaults.set(enabled, forKey: computerControlEnabledKey)
            state.applyComputerControlHostState()
        case .locationPrecise:
            defaults.set(enabled, forKey: locationPreciseKey)
        case .wakeEnabled:
            await state.setVoiceWakeEnabled(enabled)
        case .triggerChime:
            state.voiceWakeTriggerChime = Self.deviceChime(enabled: enabled, current: state.voiceWakeTriggerChime)
        case .sendChime:
            state.voiceWakeSendChime = Self.deviceChime(enabled: enabled, current: state.voiceWakeSendChime)
        case .automaticUpdates:
            guard let updater = self.updater, updater.isAvailable else { return }
            defaults.set(enabled, forKey: "autoUpdateEnabled")
            updater.automaticallyChecksForUpdates = enabled
            updater.automaticallyDownloadsUpdates = enabled
        default:
            break
        }
    }

    private func setDeviceString(_ key: DeviceSettingKey, value: String) async {
        let state = AppStateStore.shared
        let defaults = AppDefaults.standard
        switch key {
        case .computerControlProvider:
            guard value != ComputerControlProvider.cua.rawValue || CuaDriverArtifact.bundledExecutableURL != nil
            else { return }
            defaults.set(value, forKey: computerControlProviderKey)
            state.applyComputerControlHostState()
        case .cookieSyncTargetProfile:
            state.cookieSyncIntoProfile = value.trimmingCharacters(in: .whitespacesAndNewlines).nonEmpty ?? "imported"
        case .locationMode:
            guard let mode = DeviceSettingsLocationMode(rawValue: value),
                  await Self.requestDeviceLocationMode(mode.nativeMode), !Task.isCancelled
            else { return }
            defaults.set(mode.nativeMode.rawValue, forKey: locationModeKey)
        case .microphone:
            let devices = VoiceWakeDeviceOptions.microphones()
            guard devices.contains(where: { $0.id == value }) else { return }
            state.voiceWakeMicName = MicRefreshSupport.selectedMicName(
                selectedID: value, in: devices, uid: \.id, name: \.name)
            state.voiceWakeMicID = value
        case .localePrimary:
            guard VoiceWakeDeviceOptions.locales().contains(where: { $0.id == value }) else { return }
            // The System option carries a concrete locale identifier, as the native picker did; never store a sentinel.
            state.voiceWakeLocaleID = value
        default:
            break
        }
    }

    private static func deviceChime(enabled: Bool, current: VoiceWakeChime) -> VoiceWakeChime {
        guard enabled else { return .none }
        return current == .none ? .system(name: "Glass") : current
    }

    private static func requestDeviceLocationMode(_ mode: OpenClawLocationMode) async -> Bool {
        guard mode != .off else { return true }
        guard CLLocationManager.locationServicesEnabled() else {
            SystemSettingsURLSupport.openPrivacySettings(for: .location)
            return false
        }
        let requireAlways = mode == .always
        let status = await PermissionManager.locationAuthorizationStatus()
        if PermissionManager.isLocationAuthorized(status: status, requireAlways: requireAlways) { return true }
        let updated = await LocationPermissionRequester.shared.request(always: requireAlways)
        return PermissionManager.isLocationAuthorized(status: updated, requireAlways: requireAlways)
    }

    private func openDeviceSettingsPanel(_ panel: DeviceSettingsPanel) async {
        let publish: () -> Void = {
            NotificationCenter.default.post(name: .openclawDeviceSettingsChanged, object: nil)
        }
        switch panel {
        case .quickChatShortcut:
            DeviceSettingsPanels.shared.showQuickChatShortcut(parentWindow: self.window, onClose: publish)
        case .microphoneTest:
            DeviceSettingsPanels.shared.showMicrophoneTest(
                parentWindow: self.window, state: AppStateStore.shared, onClose: publish)
        case .browserImport:
            let outcome = await BrowserProfileImportModel.shared.refresh(force: true)
            guard !Task.isCancelled, self.isWindowOpen else { return }
            switch outcome {
            case .offering: self.show()
            case let .unavailable(title, message):
                let alert = NSAlert()
                alert.messageText = title
                alert.informativeText = message
                alert.addButton(withTitle: String(localized: "OK"))
                if let window = self.window { alert.beginSheetModal(for: window, completionHandler: nil) }
            }
        case .connection: AppNavigationActions.openConnection()
        case .gateways: AppNavigationActions.openConnection(tab: .gateways)
        case .debug:
            guard AppStateStore.shared.debugPaneEnabled else { return }
            AppNavigationActions.openConnection(tab: .debug)
        }
    }
}
