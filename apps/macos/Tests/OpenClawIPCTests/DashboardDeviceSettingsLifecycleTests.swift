import AppKit
import Testing
import WebKit
@testable import OpenClaw

@MainActor
extension DashboardWindowOwnershipTests {
    @Test(arguments: ["replacement", "committed", "provisional", "close"])
    func `device consent belongs to the displayed dashboard document`(_ transition: String) async throws {
        _ = AppKitTestSupport.application
        let server = try await DashboardHTTPFixture.start()
        defer { server.stop() }
        let autosaveName = "OpenClawDashboardWindow-Test-\(UUID().uuidString)"
        defer { NSWindow.removeFrame(usingName: autosaveName) }
        let auth = DashboardWindowAuth(gatewayUrl: server.websocketURL().absoluteString, token: nil, password: nil)
        let controller = DashboardWindowController(
            url: server.url(),
            auth: auth,
            websiteDataStore: .nonPersistent(),
            windowAutosaveName: autosaveName,
            requestBrowserProfileImportOffer: { _ in false })
        defer { controller.closeDashboard() }
        controller.show(url: server.url(), auth: auth)
        try #require(await Self
            .waitForConsentState { controller.canDeliverNativeCommands && !controller.webView.isLoading })
        let window = try #require(controller.window)
        let pending = Task { await controller.deviceSettingsMessageHandler.confirm(.activityReporting) }
        defer {
            if let sheet = window.attachedSheet { window.endSheet(sheet, returnCode: .cancel) }
            pending.cancel()
        }
        try #require(await Self.waitForConsentState { window.attachedSheet != nil })

        var replacement: DashboardWindowController?
        defer { replacement?.closeDashboard() }
        switch transition {
        case "replacement":
            let transferred = try #require(controller.detachWindowForReplacement())
            replacement = DashboardWindowController(
                url: server.url(),
                auth: auth,
                websiteDataStore: .nonPersistent(),
                windowAutosaveName: autosaveName,
                reusingWindow: transferred,
                requestBrowserProfileImportOffer: { _ in false })
            #expect(replacement?.window === window)
            replacement?.show(url: server.url(), auth: auth)
        case "committed": controller.webView(controller.webView, didCommit: nil)
        case "provisional": controller.webView(controller.webView, didStartProvisionalNavigation: nil)
        default:
            controller.closeDashboard()
            #expect(!controller.isWindowOpen)
        }

        if transition == "provisional" {
            #expect(window.attachedSheet != nil)
        } else {
            #expect(await Self.waitForConsentState { window.attachedSheet == nil })
        }
        // Join even a regressed, still-attached sheet: stale Allow must not survive retirement.
        if let sheet = window.attachedSheet { window.endSheet(sheet, returnCode: .alertSecondButtonReturn) }
        #expect(await pending.value == (transition == "provisional"))

        guard transition != "close" else { return }
        let current = replacement ?? controller
        if replacement != nil {
            try #require(await Self
                .waitForConsentState { current.canDeliverNativeCommands && !current.webView.isLoading })
        }
        let fresh = Task { await current.deviceSettingsMessageHandler.confirm(.activityReporting) }
        defer {
            if let sheet = window.attachedSheet { window.endSheet(sheet, returnCode: .cancel) }
            fresh.cancel()
        }
        try #require(await Self.waitForConsentState { window.attachedSheet != nil })
        let freshSheet = try #require(window.attachedSheet)
        window.endSheet(freshSheet, returnCode: .alertFirstButtonReturn)
        #expect(await fresh.value == false)
    }

    private static func waitForConsentState(_ condition: () -> Bool) async -> Bool {
        let deadline = ContinuousClock.now + .seconds(5)
        while !condition(), ContinuousClock.now < deadline {
            try? await Task.sleep(for: .milliseconds(10))
        }
        return condition()
    }
}
